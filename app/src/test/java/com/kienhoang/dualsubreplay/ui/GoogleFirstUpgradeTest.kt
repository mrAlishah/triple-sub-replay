package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SentenceSlice
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.Translated
import com.kienhoang.dualsubreplay.translation.TranslationDiagnostics
import com.kienhoang.dualsubreplay.translation.TranslationEventKind
import com.kienhoang.dualsubreplay.translation.TranslationOrigin
import com.kienhoang.dualsubreplay.translation.TranslationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/**
 * Google first, then the AI: Google's lines show at once and every sentence is replaced by the AI's as soon as the AI has it
 * (owner device test 2026-10-29). These tests drive the real window loop, [AiWindowTranslation] and [AiSubtitleTranslator] with a
 * controllable AI whose text is deliberately different from Google's.
 */
class GoogleFirstUpgradeTest {
    private val plain = List(12) { SubtitleSegment(it.toLong(), it * 3000L, (it + 1) * 3000L, "Satz $it.") }
    private val longVideo = List(100) { SubtitleSegment(it.toLong(), it * 3000L, (it + 1) * 3000L, "Satz $it.") }

    /** Six sentences, each split over two rows as the caption splitter does for long sentences. */
    private val sliced =
        (0 until 6).flatMap { n ->
            val first = "Das ist der erste Teil $n"
            val sentence = "$first und das ist der zweite Teil $n."
            val cut = first.length
            listOf(
                SubtitleSegment(
                    (2 * n).toLong(),
                    n * 6000L,
                    n * 6000L + 3000L,
                    first,
                    sentence = SentenceSlice(sentence, listOf(cut), 0),
                ),
                SubtitleSegment(
                    (2 * n + 1).toLong(),
                    n * 6000L + 3000L,
                    (n + 1) * 6000L,
                    sentence.substring(cut).trim(),
                    sentence = SentenceSlice(sentence, listOf(cut), 1),
                ),
            )
        }

    /** The AI: a different text per sentence, an optional delay, optional failures first, and a request counter. */
    private class Ai(
        override val scope: String = "chatgpt/gpt-x/abc",
        val prefix: String = "AI:",
        val delayMs: Long = 100,
        var failFirst: Int = 0,
        val sameAsGoogle: Boolean = false,
        val gapBetweenSentencesMs: Long = 0,
    ) : AiTranslationModel {
        val requests =
            java.util.concurrent.atomic
                .AtomicInteger()

        override suspend fun ask(
            system: String,
            user: String,
            onText: (String) -> Unit,
        ): String {
            val call = requests.incrementAndGet()
            delay(delayMs)
            if (call <= failFirst) throw IOException("transient")
            val sentences = JSONObject(user).getJSONArray("sentences")
            val out = JSONArray()
            for (i in 0 until sentences.length()) {
                val s = sentences.getJSONObject(i)
                val r = s.getJSONArray("rows")
                val rows = (0 until r.length()).map { (if (sameAsGoogle) "G:" else prefix) + r.getString(it) }
                out.put(JSONObject().put("id", s.getInt("id")).put("rows", JSONArray(rows)))
                if (gapBetweenSentencesMs > 0) {
                    onText(JSONObject().put("id", s.getInt("id")).put("rows", JSONArray(rows)).toString())
                    delay(gapBetweenSentencesMs)
                }
            }
            val full = JSONObject().put("sentences", out).toString()
            if (gapBetweenSentencesMs == 0L) full.chunked(24).forEach(onText)
            return full
        }
    }

    private class Subtitle(
        val shown: MutableStateFlow<List<SubtitleSegment>> = MutableStateFlow(emptyList()),
        val job: Job,
    )

    private class Env(
        val requests: MutableStateFlow<CaptionPlaybackRequest>,
        val clock: AtomicLong = AtomicLong(0),
        val diagnostics: TranslationDiagnostics = TranslationDiagnostics(),
    ) {
        val translator = AiSubtitleTranslator(cacheDirectory = null, diagnostics = diagnostics, now = { clock.get() })
    }

    private fun env(
        playing: Boolean = true,
        startMs: Long = 0,
    ) = Env(MutableStateFlow(CaptionPlaybackRequest(startMs, paused = !playing, enabled = true)))

    private suspend fun withStore(
        rows: List<SubtitleSegment>,
        block: suspend (SubtitleStore) -> Unit,
    ) {
        val directory = Files.createTempDirectory("google-first").toFile()
        try {
            SubtitleStore.create(directory, rows).use { block(it) }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Subtitle 1 the way `followGoogleTranslation` wires it: Google's line, the AI's when cached, upgrades and the level-triggered ensure. */
    private fun CoroutineScope.subtitleOne(
        env: Env,
        store: SubtitleStore,
        ai: AiTranslationModel,
        google: suspend (String) -> String = { "G:$it" },
        ensure: Boolean = true,
        target: String = "en",
    ): Subtitle {
        val shown = MutableStateFlow(emptyList<SubtitleSegment>())
        val job =
            launch {
                coroutineScope {
                    val languages = "de" to target
                    val window = AiWindowTranslation(this, env.translator, ai, languages, retryMs = 100) { emptyList() }
                    val upgrades = aiTranslationUpgrades(env.translator, env.diagnostics, ai, "de", target, window.takeIf { ensure })
                    translatePlaybackWindowTracked(
                        store,
                        env.requests,
                        { text ->
                            window.cached(text)?.let { Translated(it, window.originOf(text)) }
                                ?: Translated(google(text), TranslationOrigin.GOOGLE)
                        },
                        prefetch = { texts -> window.ask(texts) },
                        upgrades = upgrades,
                    ) { w, _ -> shown.value = w }
                }
            }
        return Subtitle(shown, job)
    }

    /** Subtitle 2 the way `startThirdLine` wires it. */
    private fun CoroutineScope.subtitleTwo(
        env: Env,
        store: SubtitleStore,
        ai: AiTranslationModel,
        target: String = "fa",
        history: MutableList<String?> = java.util.concurrent.CopyOnWriteArrayList(),
    ): Pair<Job, MutableStateFlow<Pair<Map<Long, String>, Map<Long, TranslationOrigin>>>> {
        val lines = MutableStateFlow<Pair<Map<Long, String>, Map<Long, TranslationOrigin>>>(emptyMap<Long, String>() to emptyMap())
        val job =
            launch {
                coroutineScope {
                    val window = AiWindowTranslation(this, env.translator, ai, "de" to target, retryMs = 100) { emptyList() }
                    val upgrades = aiTranslationUpgrades(env.translator, env.diagnostics, ai, "de", target, window)
                    runThirdLanguageTracked(
                        store,
                        env.requests,
                        { text ->
                            window.cached(text)?.let { Translated(it, window.originOf(text)) }
                                ?: Translated("G2:$text", TranslationOrigin.GOOGLE)
                        },
                        { texts -> window.ask(texts) },
                        upgrades,
                    ) { l, o ->
                        history += l[0L]
                        lines.value = l to o
                    }
                }
            }
        return job to lines
    }

    private suspend fun MutableStateFlow<List<SubtitleSegment>>.untilFirst(check: (SubtitleSegment) -> Boolean) =
        withTimeout(15000) { first { w -> w.firstOrNull()?.let(check) == true } }

    private fun SubtitleSegment.google() = translationOrigin == TranslationOrigin.GOOGLE

    private fun SubtitleSegment.chatGpt() = translationOrigin?.provider == TranslationProvider.CHATGPT

    @Test
    fun googleShowsFirstThenTheDifferentAiTextReplacesItAndTheLabelChanges() =
        runBlocking {
            withStore(plain) { store ->
                val env = env()
                val ai = Ai(delayMs = 400)
                val one = subtitleOne(env, store, ai)
                try {
                    val google = one.shown.untilFirst { it.google() }
                    assertEquals("G:Satz 0.", google.first().translatedText)
                    val upgraded = one.shown.untilFirst { it.chatGpt() }
                    assertEquals("AI:Satz 0.", upgraded.first().translatedText)
                    assertEquals(false, upgraded.first().translationOrigin?.cached)
                    assertEquals(1, ai.requests.get())
                } finally {
                    one.job.cancelAndJoin()
                }
            }
        }

    @Test
    fun rowsSplitFromOneSentenceUpgradeTogether() =
        runBlocking {
            withStore(sliced) { store ->
                val env = env()
                val one = subtitleOne(env, store, Ai())
                try {
                    one.shown.untilFirst { it.google() }
                    val upgraded =
                        withTimeout(15000) {
                            one.shown.first { w ->
                                w.take(2).all { it.translatedText?.startsWith("AI:") == true }
                            }
                        }
                    assertTrue(upgraded.take(2).all { it.chatGpt() })
                } finally {
                    one.job.cancelAndJoin()
                }
            }
        }

    @Test
    fun aPausedVideoIsUpgradedToo() =
        runBlocking {
            withStore(plain) { store ->
                val env = env(playing = false)
                val one = subtitleOne(env, store, Ai(delayMs = 300))
                try {
                    one.shown.untilFirst { it.google() }
                    val upgraded = one.shown.untilFirst { it.chatGpt() }
                    assertEquals("AI:Satz 0.", upgraded.first().translatedText)
                } finally {
                    one.job.cancelAndJoin()
                }
            }
        }

    @Test
    fun anAiTranslationAlreadyStoredShowsAtOnceWithoutGoogle() =
        runBlocking {
            withStore(plain) { store ->
                val env = env()
                val ai = Ai()
                env.translator.translateAll(ai, "de", "en", plain.map { it.originalText })
                val asked = ai.requests.get()
                val one = subtitleOne(env, store, ai, google = { error("Google must not be needed") })
                try {
                    val first = one.shown.untilFirst { it.translatedText != null }
                    assertEquals("AI:Satz 0.", first.first().translatedText)
                    assertTrue(first.first().chatGpt())
                    assertEquals("a stored answer is not asked again", asked, ai.requests.get())
                } finally {
                    one.job.cancelAndJoin()
                }
            }
        }

    @Test
    fun aSlowGoogleDoesNotStopTheAiFromReplacingItsLines() =
        runBlocking {
            withStore(plain) { store ->
                val env = env()
                val one =
                    subtitleOne(
                        env,
                        store,
                        Ai(delayMs = 100),
                        google = {
                            delay(1500)
                            "G:$it"
                        },
                    )
                try {
                    val shown = one.shown.untilFirst { it.translatedText != null }
                    // Whichever arrives first, the AI's text is what stays.
                    val final = withTimeout(15000) { one.shown.first { w -> w.firstOrNull()?.translatedText == "AI:Satz 0." } }
                    assertTrue(final.first().chatGpt())
                    assertTrue(shown.first().translatedText in setOf("G:Satz 0.", "AI:Satz 0."))
                } finally {
                    one.job.cancelAndJoin()
                }
            }
        }

    @Test
    fun sentencesUpgradeOneByOneWhileTheReplyStreams() =
        runBlocking {
            withStore(plain) { store ->
                val env = env()
                val one = subtitleOne(env, store, Ai(delayMs = 50, gapBetweenSentencesMs = 400))
                try {
                    one.shown.untilFirst { it.google() }
                    val partial =
                        withTimeout(15000) {
                            one.shown.first { w -> w.first().chatGpt() && w.drop(8).any { it.translatedText?.startsWith("G:") == true } }
                        }
                    assertTrue("the first sentence is upgraded before the last one is written", partial.first().chatGpt())
                } finally {
                    one.job.cancelAndJoin()
                }
            }
        }

    @Test
    fun aFailedRequestLeavesGoogleAndTheAiIsAskedAgainWithoutASeek() =
        runBlocking {
            withStore(plain) { store ->
                val env = env(playing = false)
                val ai = Ai(delayMs = 50, failFirst = 1)
                val one = subtitleOne(env, store, ai)
                try {
                    one.shown.untilFirst { it.google() }
                    delay(400)
                    assertEquals(
                        "Google stays while the AI is resting",
                        "G:Satz 0.",
                        one.shown.value
                            .first()
                            .translatedText,
                    )
                    assertTrue(
                        one.shown.value
                            .first()
                            .google(),
                    )
                    env.clock.set(60_000)
                    val upgraded = one.shown.untilFirst { it.chatGpt() }
                    assertEquals("AI:Satz 0.", upgraded.first().translatedText)
                    assertEquals(2, ai.requests.get())
                    val kinds =
                        env.diagnostics.events.value
                            .map { it.kind }
                    assertTrue(TranslationEventKind.AI_FAILED in kinds && TranslationEventKind.AI_UPGRADED in kinds)
                } finally {
                    one.job.cancelAndJoin()
                }
            }
        }

    @Test
    fun aSeekWhileTheAiIsBusyStillUpgradesTheNewPosition() =
        runBlocking {
            withStore(longVideo) { store ->
                val env = env()
                val one = subtitleOne(env, store, Ai(delayMs = 1500))
                try {
                    one.shown.untilFirst { it.google() }
                    env.requests.value = env.requests.value.copy(timeMs = 200_000, seekGeneration = 1)
                    val at = { w: List<SubtitleSegment> -> w.filter { it.startMs <= 200_000 && it.endMs > 200_000 } }
                    withTimeout(15000) { one.shown.first { w -> at(w).any { it.google() } } }
                    val upgraded = withTimeout(20000) { one.shown.first { w -> at(w).any { it.chatGpt() } } }
                    assertTrue(at(upgraded).first().translatedText!!.startsWith("AI:"))
                } finally {
                    one.job.cancelAndJoin()
                }
            }
        }

    @Test
    fun identicalTextKeepsGoogleLabelRecordsAgreesAndStopsAsking() =
        runBlocking {
            withStore(plain) { store ->
                val env = env()
                val ai = Ai(sameAsGoogle = true)
                val one = subtitleOne(env, store, ai)
                try {
                    one.shown.untilFirst { it.google() }
                    withTimeout(15000) {
                        env.diagnostics.events.first { events ->
                            events.any { it.kind == TranslationEventKind.AI_IDENTICAL }
                        }
                    }
                    delay(500)
                    assertTrue(
                        one.shown.value
                            .first()
                            .google(),
                    )
                    assertEquals("one request, no endless asking", 1, ai.requests.get())
                } finally {
                    one.job.cancelAndJoin()
                }
            }
        }

    @Test
    fun withoutEnsureAFailedRequestIsNotRetriedWhichIsTheAiOnlyPath() =
        runBlocking {
            withStore(plain) { store ->
                val env = env(playing = false)
                val ai = Ai(delayMs = 50, failFirst = 1)
                val one = subtitleOne(env, store, ai, ensure = false)
                try {
                    one.shown.untilFirst { it.google() }
                    delay(300)
                    env.clock.set(60_000)
                    delay(700)
                    assertEquals(1, ai.requests.get())
                } finally {
                    one.job.cancelAndJoin()
                }
            }
        }

    @Test
    fun theOldAnswerOfAChangedProviderNeverReachesTheNewRun() =
        runBlocking {
            withStore(plain) { store ->
                val env = env()
                val old = Ai(scope = "chatgpt/old/abc", prefix = "OLD:", delayMs = 600)
                val first = subtitleOne(env, store, old)
                first.shown.untilFirst { it.google() }
                first.job.cancelAndJoin()
                val new = Ai(scope = "claude/new", prefix = "NEW:", delayMs = 100)
                val second = subtitleOne(env, store, new)
                try {
                    val upgraded =
                        withTimeout(15000) {
                            second.shown.first { w ->
                                w.firstOrNull()?.translatedText?.startsWith("NEW:") == true
                            }
                        }
                    assertEquals("NEW:Satz 0.", upgraded.first().translatedText)
                    delay(800)
                    assertFalse(second.shown.value.any { it.translatedText?.startsWith("OLD:") == true })
                } finally {
                    second.job.cancelAndJoin()
                }
            }
        }

    @Test
    fun bothLanguagesUpgradeIndependentlyFromTheOriginal() =
        runBlocking {
            withStore(plain) { store ->
                val env = env()
                val english = Ai(prefix = "EN:", delayMs = 100)
                val persian = Ai(scope = "chatgpt/gpt-x/abc", prefix = "FA:", delayMs = 500)
                val one = subtitleOne(env, store, english, target = "en")
                val history = java.util.concurrent.CopyOnWriteArrayList<String?>()
                val (twoJob, two) = subtitleTwo(env, store, persian, target = "fa", history = history)
                try {
                    withTimeout(15000) { one.shown.first { w -> w.firstOrNull()?.translatedText == "EN:Satz 0." } }
                    // Subtitle 1 is the AI's while Subtitle 2 still waits for its own request: independent slots.
                    val done = withTimeout(20000) { two.first { (lines, _) -> lines[0L] == "FA:Satz 0." } }
                    // Whatever the timing, Subtitle 2 showed Google's line before the AI's replaced it.
                    assertTrue(history.indexOf("G2:Satz 0.") in 0 until history.indexOf("FA:Satz 0."))
                    assertTrue(done.second[0L]?.provider == TranslationProvider.CHATGPT)
                    assertEquals(
                        "EN:Satz 0.",
                        one.shown.value
                            .first()
                            .translatedText,
                    )
                    assertEquals("Subtitle 2 asks for its own target", 1, persian.requests.get())
                } finally {
                    one.job.cancelAndJoin()
                    twoJob.cancelAndJoin()
                }
            }
        }
}
