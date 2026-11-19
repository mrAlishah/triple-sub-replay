package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.GoogleWebTranslator
import com.kienhoang.dualsubreplay.translation.TranslationDiagnostics
import com.kienhoang.dualsubreplay.translation.TranslationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Subtitle 2 (the third line) with the selected Translation AI Service, keyed or not: the model it is given asks the AI
 * straight from the Original, in its own language, and the modes keep their meaning. Synthetic offline model only.
 */
class ThirdLineTranslationTest {
    private val rows = List(20) { SubtitleSegment(it.toLong(), it * 3_000L, (it + 1) * 3_000L, "Line number $it is spoken here.") }

    /** A keyed service answered locally: every row comes back as "T:<row>", and each request is kept. */
    private class FakeKeyedModel : AiTranslationModel {
        override val scope = "fake/keyed"
        val systems = CopyOnWriteArrayList<String>()
        val sentences = CopyOnWriteArrayList<String>()

        override suspend fun ask(
            system: String,
            user: String,
            onText: (String) -> Unit,
        ): String {
            systems += system
            val asked = JSONObject(user).getJSONArray("sentences")
            val answers = JSONArray()
            for (index in 0 until asked.length()) {
                val sentence = asked.getJSONObject(index)
                val sentenceRows = sentence.getJSONArray("rows")
                sentences += List(sentenceRows.length()) { sentenceRows.getString(it) }
                answers.put(
                    JSONObject()
                        .put("id", sentence.getInt("id"))
                        .put("rows", JSONArray(List(sentenceRows.length()) { "T:${sentenceRows.getString(it)}" })),
                )
            }
            return JSONObject().put("sentences", answers).toString().also(onText)
        }
    }

    private fun runThird(
        ai: AiTranslationModel?,
        stateOf: () -> DualSubUiState,
        onTrack: (TranscriptTrack) -> Unit = {},
        google: GoogleWebTranslator = GoogleWebTranslator(),
        googleWaitMs: Long = 1L,
        check: suspend (MutableStateFlow<DualSubUiState>) -> Unit,
    ) {
        val directory = Files.createTempDirectory("third-line").toFile()
        try {
            runBlocking {
                SubtitleStore.create(File(directory, "store").also { it.mkdirs() }, rows).use { store ->
                    val state = MutableStateFlow(stateOf())
                    val scope = CoroutineScope(Dispatchers.Default + Job())
                    val third =
                        ThirdLineTranslation(
                            state = state,
                            isCurrentLoad = { _, _, _ -> true },
                            aiTranslator = AiSubtitleTranslator(null),
                            diagnostics = TranslationDiagnostics(),
                            googleTranslator = google,
                            playbackRequests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true)),
                            aiFillTexts = { emptyList() },
                            translateOnDevice = { _, _, _, _, text -> "D:$text" },
                            googleWaitMs = { googleWaitMs },
                        )
                    val job = third.start(store, "v", 1L, "en", scope, ai, null, onTrack)
                    try {
                        check(state)
                    } finally {
                        job?.cancelAndJoin()
                        scope.coroutineContext[Job]?.cancelAndJoin()
                    }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun waitFor(
        state: MutableStateFlow<DualSubUiState>,
        condition: (String?) -> Boolean,
    ) = withTimeout(30_000) { while (!condition(state.value.thirdLines.textFor("v", 0L))) delay(5) }

    /** Subtitle 2 on, French, Original English, Subtitle 1 Vietnamese. */
    private fun base(
        aiOnly: Boolean,
        engine: TranslationEngine = TranslationEngine.ON_DEVICE,
    ) = DualSubUiState(
        tripleSubtitlesEnabled = true,
        thirdLanguage = "fr",
        targetLanguage = "vi",
        translationEngine = engine,
        aiTranslationOnly = aiOnly,
    )

    @Test
    fun aKeyedServiceTranslatesSubtitle2FromTheOriginalInItsOwnLanguage() {
        val model = FakeKeyedModel()
        runThird(model, { base(aiOnly = true) }) { state ->
            waitFor(state) { it == "T:Line number 0 is spoken here." }
            assertTrue("asked in French", model.systems.all { it.contains("French") })
            assertTrue("from the Original, never from Subtitle 1", model.sentences.all { it.startsWith("Line number") })
        }
    }

    @Test
    fun withoutAnAiModelSubtitle2KeepsTheDeviceTranslationAndAsksNoAi() {
        runThird(null, { base(aiOnly = false) }) { state ->
            waitFor(state) { it == "D:Line number 0 is spoken here." }
            assertEquals(
                null,
                state.value.thirdLines
                    .originFor("v", 0L)
                    ?.takeIf { it.provider.name == "AI_PROVIDER" },
            )
        }
    }

    @Test
    fun theTranscriptTrackTranslatesAnyLineOfTheVideoFromTheOriginalWithTheSameFallbacks() {
        var track: TranscriptTrack? = null
        runThird(null, { base(aiOnly = false) }, { track = it }) {
            val whole = checkNotNull(track)
            assertEquals("fr", whole.language)
            // A line far from the playing window, translated on the device straight from the Original.
            withTimeout(30_000) { whole.fill(listOf("Line number 17 is spoken here.")) }
            assertEquals("D:Line number 17 is spoken here.", whole.known("Line number 17 is spoken here."))
        }
    }

    @Test
    fun theTranscriptTrackAsksTheSelectedAiServiceInSubtitle2sLanguageFromTheOriginal() {
        val model = FakeKeyedModel()
        var track: TranscriptTrack? = null
        runThird(model, { base(aiOnly = true) }, { track = it }) {
            val whole = checkNotNull(track)
            withTimeout(30_000) { whole.fill(listOf("Line number 17 is spoken here.")) }
            assertEquals("T:Line number 17 is spoken here.", whole.known("Line number 17 is spoken here."))
            assertTrue("asked in French", model.systems.all { it.contains("French") })
            assertTrue("from the Original, never from Subtitle 1", model.sentences.all { it.startsWith("Line number") })
        }
    }

    /** Answers every Google request locally ("fr:<text>"), keeping the texts each request carried. */
    private fun fakeGoogle(requests: MutableList<List<String>>): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val form = request.body as FormBody
                    val texts = (0 until form.size).filter { form.name(it) == "q" }.map(form::value)
                    requests += texts
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("test")
                        .body(JSONArray(texts.map { "fr:$it" }).toString().toResponseBody("application/json".toMediaType()))
                        .build()
                },
            ).build()

    @Test
    fun theTranscriptTrackAsksGoogleForABatchInOneRequestAndNeverUsesTheAi() {
        val requests = CopyOnWriteArrayList<List<String>>()
        val batch = listOf("Export sentence A.", "Export sentence B.", "Export sentence C.")
        var track: TranscriptTrack? = null
        runThird(null, {
            base(aiOnly = false, engine = TranslationEngine.GOOGLE_WEB)
        }, { track = it }, GoogleWebTranslator(client = fakeGoogle(requests)), 5_000L) {
            val whole = checkNotNull(track)
            assertFalse("no AI model, so no AI quota is used", whole.usesAi)
            withTimeout(30_000) { whole.fill(batch) }
            assertEquals("one request for the whole batch", listOf(batch), requests.filter { request -> batch.any { it in request } })
            assertEquals("fr:Export sentence B.", whole.known("Export sentence B."))
        }
    }

    @Test
    fun anAiOnlyTranscriptTrackSaysItUsesTheAiAndSendsNothingToGoogle() {
        val requests = CopyOnWriteArrayList<List<String>>()
        val model = FakeKeyedModel()
        var track: TranscriptTrack? = null
        runThird(model, {
            base(aiOnly = true, engine = TranslationEngine.GOOGLE_WEB)
        }, { track = it }, GoogleWebTranslator(client = fakeGoogle(requests)), 5_000L) {
            val whole = checkNotNull(track)
            assertTrue(whole.usesAi)
            withTimeout(30_000) { whole.fill(listOf("Export sentence A.")) }
            assertEquals("T:Export sentence A.", whole.known("Export sentence A."))
            assertTrue("Google is not asked for AI-only lines", requests.none { "Export sentence A." in it })
        }
    }

    @Test
    fun noTranscriptTrackWhenSubtitle2IsOffOrRepeatsAnotherLine() {
        var tracks = 0
        runThird(null, { base(aiOnly = false).copy(tripleSubtitlesEnabled = false) }, { tracks++ }) { }
        runThird(null, { base(aiOnly = false).copy(thirdLanguage = "vi") }, { tracks++ }) { }
        runThird(null, { base(aiOnly = false).copy(thirdLanguage = "en") }, { tracks++ }) { }
        assertEquals(0, tracks)
    }

    @Test
    fun withTheAiAndAnOnDeviceFallbackTheAiLineReplacesTheDeviceLine() {
        val model = FakeKeyedModel()
        runThird(model, { base(aiOnly = false) }) { state ->
            waitFor(state) { it == "T:Line number 0 is spoken here." }
            delay(200)
            assertEquals("T:Line number 0 is spoken here.", state.value.thirdLines.textFor("v", 0L))
            assertEquals("each sentence asked once", model.sentences.distinct().size, model.sentences.size)
        }
    }
}
