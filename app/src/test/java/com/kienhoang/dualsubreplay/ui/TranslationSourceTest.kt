package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.chatgpt.ChatGptErrorKind
import com.kienhoang.dualsubreplay.chatgpt.ChatGptException
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.Translated
import com.kienhoang.dualsubreplay.translation.TranslationDiagnostics
import com.kienhoang.dualsubreplay.translation.TranslationEvent
import com.kienhoang.dualsubreplay.translation.TranslationEventKind
import com.kienhoang.dualsubreplay.translation.TranslationOrigin
import com.kienhoang.dualsubreplay.translation.TranslationProvider
import com.kienhoang.dualsubreplay.translation.aiTranslationOrigin
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Show translation source: the label follows the text on screen, never the selected settings (owner request 2026-10-29). */
class TranslationSourceTest {
    private val chatGpt = TranslationOrigin(TranslationProvider.CHATGPT, cached = false, model = "gpt-x")
    private val chatGptCached = chatGpt.copy(cached = true)
    private val rows = List(60) { SubtitleSegment(it.toLong(), it * 3400L, (it + 1) * 3400L, "Caption $it") }

    private class Model(
        override val scope: String,
        private val answer: suspend (user: String, onText: (String) -> Unit) -> String,
    ) : AiTranslationModel {
        override suspend fun ask(
            system: String,
            user: String,
            onText: (String) -> Unit,
        ): String = answer(user, onText)
    }

    /** What a model answers: every sentence id with its rows, each prefixed by [tag]; at most [upTo] sentences. */
    private fun reply(
        user: String,
        tag: String,
        upTo: Int = Int.MAX_VALUE,
    ): String {
        val sentences = JSONObject(user).getJSONArray("sentences")
        return JSONObject()
            .put(
                "sentences",
                JSONArray().apply {
                    for (index in 0 until minOf(sentences.length(), upTo)) {
                        val sentence = sentences.getJSONObject(index)
                        val source = sentence.getJSONArray("rows")
                        val translated = JSONArray((0 until source.length()).map { "$tag:${source.getString(it)}" })
                        put(JSONObject().put("id", sentence.getInt("id")).put("rows", translated))
                    }
                },
            ).toString()
    }

    private fun gpt(scope: String = "chatgpt/gpt-x/abc123") = Model(scope) { user, onText -> reply(user, "gpt").also(onText) }

    private suspend fun withStore(block: suspend (SubtitleStore) -> Unit) {
        val directory = Files.createTempDirectory("translation-source-test").toFile()
        try {
            SubtitleStore.create(directory, rows).use { block(it) }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun playing() = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))

    // 1. Google translates first and the label says Google.
    @Test
    fun googleFirstIsLabeledGoogle() =
        runBlocking {
            withStore { store ->
                val shown = MutableStateFlow(emptyList<SubtitleSegment>())
                val job =
                    launch {
                        translatePlaybackWindowTracked(
                            store,
                            playing(),
                            { Translated("g:$it", TranslationOrigin.GOOGLE) },
                        ) { window, _ -> shown.value = window }
                    }
                try {
                    val first = withTimeout(5000) { shown.first { it.firstOrNull()?.translatedText != null } }.first()
                    assertEquals("g:Caption 0", first.translatedText)
                    assertEquals(TranslationOrigin.GOOGLE, first.translationOrigin)
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    // 2. ChatGPT's result replaces Google's and the label changes with the text.
    @Test
    fun chatGptReplacesGoogleAndTheLabelChangesWithIt() =
        runBlocking {
            withStore { store ->
                val shown = MutableStateFlow(emptyList<SubtitleSegment>())
                val version = MutableStateFlow(0L)
                val ai = ConcurrentHashMap<String, String>()
                val upgrades = TranslationUpgrades(version, originOf = { chatGpt }) { ai[it] }
                val job =
                    launch {
                        translatePlaybackWindowTracked(
                            store,
                            playing(),
                            { Translated("g:$it", TranslationOrigin.GOOGLE) },
                            upgrades = upgrades,
                        ) { window, _ -> shown.value = window }
                    }
                try {
                    withTimeout(5000) { shown.first { it.firstOrNull()?.translationOrigin == TranslationOrigin.GOOGLE } }
                    ai["Caption 0"] = "gpt:Caption 0"
                    version.value = 1
                    val after = withTimeout(5000) { shown.first { it.firstOrNull()?.translationOrigin == chatGpt } }
                    assertEquals("gpt:Caption 0", after.first().translatedText)
                    // The AI has not written the others: they keep Google's text and Google's label.
                    assertTrue(after.drop(1).filter { it.translatedText != null }.all { it.translationOrigin == TranslationOrigin.GOOGLE })
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    // 3. ChatGPT fails: Google stays, is still labeled Google, and the diagnostics say what happened.
    @Test
    fun whenChatGptFailsGoogleStaysLabeledGoogleAndTheFailureIsRecorded() =
        runBlocking {
            val diagnostics = TranslationDiagnostics { 7L }
            val translator = AiSubtitleTranslator(cacheDirectory = null, diagnostics = diagnostics)
            val failing = Model("chatgpt/gpt-x/abc123") { _, _ -> throw ChatGptException(ChatGptErrorKind.UNAVAILABLE, "down") }
            try {
                translator.translateAll(failing, "de", "en", listOf("Hallo Welt."))
                fail("the request should have failed")
            } catch (_: IOException) {
            }
            val googleRows =
                listOf(SubtitleSegment(0, 0, 1000, "Hallo Welt.", "Hello world.", translationOrigin = TranslationOrigin.GOOGLE))
            val upgraded =
                withUpgrades(googleRows, { translator.originOf(failing, "de", "en", it) }) {
                    translator.shownTranslation(failing, "de", "en", it)
                }
            assertSame(googleRows, upgraded)
            assertEquals(
                listOf(TranslationEventKind.AI_SCHEDULED, TranslationEventKind.AI_REQUEST, TranslationEventKind.AI_FAILED),
                diagnostics.events.value.map { it.kind },
            )
            val failure = diagnostics.events.value.last()
            assertEquals("UNAVAILABLE", failure.reason)
            assertEquals("en", failure.target)
            assertEquals("gpt-x", failure.origin?.model)
        }

    // 4. and 5. AI only: ChatGPT when it has the text, the real fallback when it does not.
    @Test
    fun aiOnlyUsesChatGptAndOtherwiseLabelsTheActualFallback() =
        runBlocking {
            var googleAsked = false
            val fromChatGpt =
                chooseTranslation(
                    aiOnly = true,
                    fromAi = { wait -> Translated("gpt", chatGpt).takeIf { wait } },
                    fromGoogle = {
                        googleAsked = true
                        Translated("g", TranslationOrigin.GOOGLE)
                    },
                    onDevice = { Translated("d", TranslationOrigin.ON_DEVICE) },
                )
            assertEquals(chatGpt, fromChatGpt.origin)
            assertFalse(googleAsked)

            val fallback =
                chooseTranslation(
                    aiOnly = true,
                    fromAi = { null },
                    fromGoogle = { Translated("g", TranslationOrigin.GOOGLE) },
                    onDevice = { Translated("d", TranslationOrigin.ON_DEVICE) },
                )
            assertEquals(TranslationOrigin.GOOGLE, fallback.origin)

            val blank =
                chooseTranslation(
                    aiOnly = true,
                    fromAi = { null },
                    fromGoogle = { null },
                    onDevice = { Translated("d", TranslationOrigin.ON_DEVICE) },
                )
            assertEquals(TranslationOrigin.ON_DEVICE, blank.origin)

            try {
                chooseTranslation(true, { null }, { throw IOException("no address") }, { Translated("d", TranslationOrigin.ON_DEVICE) })
                fail("AI only lets Google's failure through")
            } catch (_: IOException) {
            }
        }

    @Test
    fun googleFirstTakesTheAiOnlyWhenGoogleIsUnreachableAndTheAiHasIt() =
        runBlocking {
            val cachedFirst = chooseTranslation(false, { Translated("gpt", chatGptCached) }, { error("unused") }, { error("unused") })
            assertEquals(chatGptCached, cachedFirst.origin)

            val waited =
                chooseTranslation(
                    aiOnly = false,
                    fromAi = { wait -> Translated("gpt", chatGpt).takeIf { wait } },
                    fromGoogle = { throw IOException("slow") },
                    onDevice = { error("unused") },
                )
            assertEquals(chatGpt, waited.origin)

            val google = chooseTranslation(false, { null }, { Translated("g", TranslationOrigin.GOOGLE) }, { error("unused") })
            assertEquals(TranslationOrigin.GOOGLE, google.origin)
        }

    // 6. and 7. Each slot keeps its own label.
    @Test
    fun subtitleOneAndSubtitleTwoCarryIndependentOrigins() =
        runBlocking {
            for ((second, third) in listOf(
                chatGpt to TranslationOrigin.GOOGLE,
                chatGpt to chatGpt,
                TranslationOrigin.GOOGLE to chatGptCached,
            )) {
                withStore { store ->
                    val requests = playing()
                    val one = MutableStateFlow(emptyList<SubtitleSegment>())
                    val two = MutableStateFlow<Pair<Map<Long, String>, Map<Long, TranslationOrigin>>?>(null)
                    val first =
                        launch {
                            translatePlaybackWindowTracked(store, requests, { Translated("en:$it", second) }) { window, _ ->
                                one.value =
                                    window
                            }
                        }
                    val other =
                        launch {
                            runThirdLanguageTracked(store, requests, { Translated("fa:$it", third) }) { lines, origins ->
                                two.value =
                                    lines to origins
                            }
                        }
                    try {
                        withTimeout(5000) { one.first { it.firstOrNull()?.translatedText != null } }
                        val line = withTimeout(5000) { two.first { it?.second?.containsKey(0L) == true } }!!
                        assertEquals(second, one.value.first().translationOrigin)
                        assertEquals("fa:Caption 0", line.first[0L])
                        assertEquals(third, line.second[0L])
                    } finally {
                        first.cancelAndJoin()
                        other.cancelAndJoin()
                    }
                }
            }
        }

    // 8. A cached ChatGPT translation is labeled cached and still names the model.
    @Test
    fun aDiskCacheHitKeepsItsChatGptProvenanceAndIsLabeledCached() =
        runBlocking {
            val directory = Files.createTempDirectory("translation-source-cache").toFile()
            try {
                val model = gpt()
                val written = AiSubtitleTranslator(cacheDirectory = directory)
                written.translateAll(model, "de", "en", listOf("Hallo Welt."))
                assertEquals(chatGpt, written.originOf(model, "de", "en", "Hallo Welt."))

                val later = AiSubtitleTranslator(cacheDirectory = directory)
                assertNull("nothing is held before the cache is read", later.originOf(model, "de", "en", "Hallo Welt."))
                assertEquals("gpt:Hallo Welt.", later.cachedTranslation(model, "de", "en", "Hallo Welt."))
                assertEquals(chatGptCached, later.originOf(model, "de", "en", "Hallo Welt."))
                assertEquals(R.string.translation_source_chatgpt_cached, translationSourceRes(chatGptCached))
            } finally {
                directory.deleteRecursively()
            }
        }

    // 9. A label never crosses a language, a video or a seek.
    @Test
    fun anotherLanguageOrVideoNeverReusesALabel() =
        runBlocking {
            val translator = AiSubtitleTranslator(cacheDirectory = null)
            val model = gpt()
            translator.translateAll(model, "de", "en", listOf("Hallo Welt."))
            assertEquals(chatGpt, translator.originOf(model, "de", "en", "Hallo Welt."))
            assertNull(translator.originOf(model, "de", "fa", "Hallo Welt."))
            assertNull(translator.originOf(gpt("chatgpt/gpt-y/abc123"), "de", "en", "Hallo Welt."))

            val state =
                DualSubUiState(
                    activeVideoId = "v1",
                    thirdLanguage = "fa",
                    thirdLines = ThirdLines("v1", mapOf(7L to "x"), "fa", mapOf(7L to TranslationOrigin.GOOGLE)),
                )
            assertEquals(TranslationOrigin.GOOGLE, state.thirdOriginFor(7))
            assertNull(state.copy(thirdLanguage = "ar").thirdOriginFor(7))
            assertNull(state.copy(activeVideoId = "v2").thirdOriginFor(7))
            assertNull(state.thirdOriginFor(8))
        }

    @Test
    fun aSeekStartsANewWindowWithoutTheOldLabels() =
        runBlocking {
            withStore { store ->
                val requests = playing()
                val shown = MutableStateFlow(emptyList<SubtitleSegment>())
                val seeked = AtomicBoolean(false)
                val job =
                    launch {
                        translatePlaybackWindowTracked(
                            store,
                            requests,
                            { Translated("t:$it", if (seeked.get()) TranslationOrigin.ON_DEVICE else TranslationOrigin.GOOGLE) },
                        ) { window, _ -> shown.value = window }
                    }
                try {
                    withTimeout(5000) { shown.first { it.firstOrNull()?.translationOrigin == TranslationOrigin.GOOGLE } }
                    seeked.set(true)
                    requests.value = requests.value.copy(timeMs = 150_000, seekGeneration = 1)
                    val after =
                        withTimeout(5000) {
                            shown.first { window ->
                                window.any { it.startMs <= 150_000 && it.endMs > 150_000 && it.translatedText != null }
                            }
                        }
                    assertTrue(after.none { it.translationOrigin == TranslationOrigin.GOOGLE })
                    assertTrue(after.filter { it.translatedText != null }.all { it.translationOrigin == TranslationOrigin.ON_DEVICE })
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    // 10. A stream that dies half way never produces a success.
    @Test
    fun anIncompleteStreamLabelsOnlyWhatTheModelFinishedAndRecordsNoSuccess() =
        runBlocking {
            val diagnostics = TranslationDiagnostics { 7L }
            val translator = AiSubtitleTranslator(cacheDirectory = null, diagnostics = diagnostics)
            val dying =
                Model("chatgpt/gpt-x/abc123") { user, onText ->
                    val full = reply(user, "gpt", upTo = 1)
                    onText(full.substring(0, full.indexOf("]}") + 2))
                    throw ChatGptException(ChatGptErrorKind.UNAVAILABLE, "cut")
                }
            try {
                translator.translateAll(dying, "de", "en", listOf("Erster Satz.", "Zweiter Satz."))
                fail("the stream was cut")
            } catch (_: IOException) {
            }
            // The first sentence was written whole before the cut, so it is ChatGPT's; the second was never written.
            assertEquals(chatGpt, translator.originOf(dying, "de", "en", "Erster Satz."))
            assertNull(translator.originOf(dying, "de", "en", "Zweiter Satz."))
            val kinds = diagnostics.events.value.map { it.kind }
            assertEquals(listOf(TranslationEventKind.AI_SCHEDULED, TranslationEventKind.AI_REQUEST, TranslationEventKind.AI_FAILED), kinds)
            assertEquals(
                1,
                diagnostics.events.value
                    .last()
                    .count,
            )
        }

    @Test
    fun aCompletedRequestIsRecordedAsSucceeded() =
        runBlocking {
            val diagnostics = TranslationDiagnostics { 7L }
            val translator = AiSubtitleTranslator(cacheDirectory = null, diagnostics = diagnostics)
            translator.translateAll(gpt(), "de", "en", listOf("Erster Satz.", "Zweiter Satz."))
            assertEquals(
                listOf(TranslationEventKind.AI_SCHEDULED, TranslationEventKind.AI_REQUEST, TranslationEventKind.AI_SUCCEEDED),
                diagnostics.events.value.map { it.kind },
            )
            assertEquals(
                2,
                diagnostics.events.value
                    .last()
                    .count,
            )
        }

    // Identical text keeps the label of whoever wrote the text on screen.
    @Test
    fun anIdenticalAiResultDoesNotTakeOverGoogleLabel() {
        val shown = listOf(SubtitleSegment(0, 0, 1000, "Hallo Welt.", "Hello world.", translationOrigin = TranslationOrigin.GOOGLE))
        val agreed = mutableListOf<TranslationOrigin>()
        val upgraded = withUpgrades(shown, { chatGpt }, { agreed += it }) { "Hello world." }
        assertSame(shown, upgraded)
        assertEquals(listOf(chatGpt), agreed)
        // A row that already shows the AI's text is not reported again.
        val aiRow = shown.map { it.copy(translationOrigin = chatGpt) }
        withUpgrades(aiRow, { chatGpt }, { agreed += it }) { "Hello world." }
        assertEquals(listOf(chatGpt), agreed)
    }

    @Test
    fun aRowWithoutARecordedOriginReadsAsUnknown() {
        assertEquals(R.string.translation_source_unknown, translationSourceRes(null))
        assertEquals(R.string.translation_source_unknown, translationSourceRes(TranslationOrigin.UNKNOWN))
        val unlabeled = withUpgrades(listOf(SubtitleSegment(0, 0, 1000, "Hallo.")), { null }) { "Hi." }.single()
        assertEquals("Hi.", unlabeled.translatedText)
        assertNull(unlabeled.translationOrigin)
    }

    @Test
    fun everySourceHasItsOwnLabel() {
        assertEquals(R.string.translation_source_chatgpt, translationSourceRes(chatGpt))
        assertEquals(R.string.translation_source_chatgpt_cached, translationSourceRes(chatGptCached))
        assertEquals(R.string.translation_source_google, translationSourceRes(TranslationOrigin.GOOGLE))
        assertEquals(R.string.translation_source_on_device, translationSourceRes(TranslationOrigin.ON_DEVICE))
        assertEquals(R.string.translation_source_youtube, translationSourceRes(TranslationOrigin.YOUTUBE))
        assertEquals(R.string.translation_source_ai_provider, translationSourceRes(aiTranslationOrigin("openai/gpt-4o", false)))
        assertEquals(R.string.translation_source_ai_provider_cached, translationSourceRes(aiTranslationOrigin("openai/gpt-4o", true)))
    }

    @Test
    fun theCacheScopeNamesTheProviderAndModelButNeverTheAccount() {
        val origin = aiTranslationOrigin("chatgpt/gpt-5.1/0a1b2c3d4e5f", cached = true)
        assertEquals(TranslationProvider.CHATGPT, origin.provider)
        assertEquals("gpt-5.1", origin.model)
        assertTrue(origin.cached)
        assertFalse(origin.toString().contains("0a1b2c3d4e5f"))
        val keyed = aiTranslationOrigin("gemini/flash-lite", cached = false)
        assertEquals(TranslationProvider.AI_PROVIDER, keyed.provider)
        assertEquals("gemini/flash-lite", keyed.model)
    }

    @Test
    fun theDiagnosticsKeepRecentEventsWithoutRepeatsOrText() {
        val diagnostics = TranslationDiagnostics { 1L }
        repeat(5) { diagnostics.record(TranslationEventKind.FALLBACK, "en", TranslationOrigin.GOOGLE) }
        diagnostics.record(TranslationEventKind.FALLBACK, "fa", TranslationOrigin.GOOGLE)
        diagnostics.record(TranslationEventKind.AI_IDENTICAL, "en", chatGpt)
        diagnostics.record(TranslationEventKind.AI_IDENTICAL, "en", chatGpt)
        assertEquals(3, diagnostics.events.value.size)
        repeat(100) { diagnostics.record(TranslationEventKind.AI_REQUEST, "en", chatGpt, count = it) }
        assertEquals(60, diagnostics.events.value.size)
        assertEquals(
            99,
            diagnostics.events.value
                .last()
                .count,
        )
        diagnostics.clear()
        assertTrue(diagnostics.events.value.isEmpty())
    }

    @Test
    fun theEventsOfOneLanguageAreListedNewestFirstAndLabeled() {
        val strings =
            object : UiStrings {
                override fun get(
                    id: Int,
                    vararg formatArgs: Any,
                ) = "$id:" + formatArgs.joinToString(",")
            }
        val events =
            listOf(
                TranslationEvent(1, TranslationEventKind.AI_REQUEST, "en", chatGpt, count = 4),
                TranslationEvent(2, TranslationEventKind.AI_REQUEST, "fa", chatGpt, count = 4),
                TranslationEvent(3, TranslationEventKind.AI_FAILED, "en", chatGpt, count = 1, reason = "UNAVAILABLE"),
                TranslationEvent(4, TranslationEventKind.FALLBACK, "en", TranslationOrigin.GOOGLE),
            )
        val shown = eventsFor(events, "en")
        assertEquals(listOf(4L, 3L, 1L), shown.map { it.atMs })
        assertEquals("${R.string.translation_source_event_failed}:UNAVAILABLE,1", translationEventText(shown[1], strings))
        assertEquals(
            "${R.string.translation_source_event_fallback}:${R.string.translation_source_google}:",
            translationEventText(shown[0], strings),
        )
    }

    @Test
    fun everyEventKindHasATextBuiltFromCountsAndCodesOnly() {
        val strings =
            object : UiStrings {
                override fun get(
                    id: Int,
                    vararg formatArgs: Any,
                ) = "$id:" + formatArgs.joinToString(",")
            }
        TranslationEventKind.entries.forEach { kind ->
            val event = TranslationEvent(1, kind, "en", chatGpt, count = 3, reason = "USAGE_LIMIT")
            val text = translationEventText(event, strings)
            assertTrue(kind.name, text.isNotEmpty())
        }
        val failed = TranslationEvent(1, TranslationEventKind.REFRESH_FAILED, "en", chatGpt, reason = "USAGE_LIMIT")
        assertEquals("${R.string.translation_source_event_refresh_failed}:USAGE_LIMIT", translationEventText(failed, strings))
    }
}
