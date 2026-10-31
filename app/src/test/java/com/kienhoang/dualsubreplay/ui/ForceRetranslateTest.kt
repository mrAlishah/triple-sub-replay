package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.chatgpt.ChatGptErrorKind
import com.kienhoang.dualsubreplay.chatgpt.ChatGptException
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.Translated
import com.kienhoang.dualsubreplay.translation.TranslationDiagnostics
import com.kienhoang.dualsubreplay.translation.TranslationEventKind
import com.kienhoang.dualsubreplay.translation.TranslationOrigin
import com.kienhoang.dualsubreplay.translation.TranslationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** "Re-translate with AI": a deliberate fresh request that ignores stored and earlier answers and never blanks the subtitles. */
class ForceRetranslateTest {
    private val rows = List(12) { SubtitleSegment(it.toLong(), it * 3000L, (it + 1) * 3000L, "Satz $it.") }

    /** The AI: its text carries a version, so a fresh answer is demonstrably different; it records what it was asked. */
    private class Ai(
        override val scope: String = "chatgpt/gpt-x/abc",
        version: String = "v1",
        delayMs: Long = 0,
        failWith: Exception? = null,
        garbage: Boolean = false,
        skipIds: Set<Int> = emptySet(),
    ) : AiTranslationModel {
        // Set by the test thread, read by the coroutine's thread.
        @Volatile var version = version

        @Volatile var delayMs = delayMs

        @Volatile var failWith = failWith

        @Volatile var garbage = garbage

        @Volatile var skipIds = skipIds

        val calls = AtomicInteger()
        val running = AtomicInteger()

        @Volatile var maxRunning = 0
        val systems = mutableListOf<String>()
        val users = mutableListOf<String>()

        @Volatile var gate: CompletableDeferred<Unit>? = null

        override suspend fun ask(
            system: String,
            user: String,
            onText: (String) -> Unit,
        ): String {
            calls.incrementAndGet()
            systems += system
            users += user
            val now = running.incrementAndGet()
            maxRunning = maxOf(maxRunning, now)
            try {
                gate?.await()
                if (delayMs > 0) delay(delayMs)
                failWith?.let { throw it }
                if (garbage) return "not json at all"
                val sentences = JSONObject(user).getJSONArray("sentences")
                val out = JSONArray()
                for (i in 0 until sentences.length()) {
                    val s = sentences.getJSONObject(i)
                    if (s.getInt("id") in skipIds) continue
                    val r = s.getJSONArray("rows")
                    out.put(
                        JSONObject().put("id", s.getInt("id")).put(
                            "rows",
                            JSONArray(
                                (0 until r.length()).map {
                                    "$version:" +
                                        r.getString(it)
                                },
                            ),
                        ),
                    )
                }
                return JSONObject().put("sentences", out).toString().also(onText)
            } finally {
                running.decrementAndGet()
            }
        }
    }

    private class Env(
        val clock: AtomicLong = AtomicLong(0),
        val diagnostics: TranslationDiagnostics = TranslationDiagnostics(),
        directory: java.io.File? = null,
    ) {
        val translator = AiSubtitleTranslator(cacheDirectory = directory, diagnostics = diagnostics, now = { clock.get() })
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun context(
        env: Env,
        second: Ai?,
        third: Ai?,
        current: () -> Boolean = { true },
        texts: suspend (RefreshScope) -> List<String> = { rows.map { it.originalText } },
    ) = RefreshContext(
        targets =
            buildMap {
                if (second != null) put(SubtitleSlot.SECOND, RefreshTarget(second, "de", "en"))
                if (third != null) put(SubtitleSlot.THIRD, RefreshTarget(third, "de", "fa"))
            },
        texts = texts,
        isCurrent = current,
    )

    private suspend fun RefreshState.untilIdle(refresher: TranslationRefresher) =
        withTimeout(15000) {
            refresher.state.first { it.running.isEmpty() }
        }

    // 1. A new request even though the sentence is stored in memory and on disk and was answered before.
    @Test
    fun forceAsksTheProviderAgainWhateverIsStoredAnsweredOrOnDisk() =
        runBlocking {
            val directory = Files.createTempDirectory("force").toFile()
            try {
                val env = Env(directory = directory)
                val ai = Ai()
                val texts = listOf("Satz 0.")
                env.translator.translateAll(ai, "de", "en", texts)
                assertEquals("v1:Satz 0.", env.translator.cachedTranslation(ai, "de", "en", "Satz 0."))
                // A normal translateAll would now do nothing: stored and answered.
                env.translator.translateAll(ai, "de", "en", texts)
                assertEquals(1, ai.calls.get())

                ai.version = "v2"
                assertEquals(1, env.translator.forceTranslate(ai, "de", "en", texts))
                assertEquals("a new request reached the provider", 2, ai.calls.get())
                assertEquals("v2:Satz 0.", env.translator.cachedTranslation(ai, "de", "en", "Satz 0."))
                // The disk entry was replaced too: a fresh translator reads the new text.
                val later = AiSubtitleTranslator(cacheDirectory = directory)
                assertEquals("v2:Satz 0.", later.cachedTranslation(ai, "de", "en", "Satz 0."))
                assertEquals("read back from disk", true, later.originOf(ai, "de", "en", "Satz 0.")?.cached)
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun aFailureChangesNothingAndTheLocalRestDoesNotBlockAnExplicitRetry() =
        runBlocking {
            val env = Env()
            val ai = Ai()
            env.translator.translateAll(ai, "de", "en", listOf("Satz 0."))
            ai.failWith = IOException("down")
            try {
                env.translator.forceTranslate(ai, "de", "en", listOf("Satz 0."))
                throw AssertionError("the failure should surface")
            } catch (_: IOException) {
            }
            assertEquals("v1:Satz 0.", env.translator.cachedTranslation(ai, "de", "en", "Satz 0."))
            // The rest (30 s, virtual clock still at 0) would block a normal request but not a deliberate one.
            ai.failWith = null
            ai.version = "v3"
            assertEquals(1, env.translator.forceTranslate(ai, "de", "en", listOf("Satz 0.")))
            assertEquals("v3:Satz 0.", env.translator.cachedTranslation(ai, "de", "en", "Satz 0."))
        }

    @Test
    fun anUnreadableOrPartialReplyNeverReplacesAnything() =
        runBlocking {
            val env = Env()
            val ai = Ai()
            val two = listOf("Satz 0.", "Satz 1.")
            env.translator.translateAll(ai, "de", "en", two)
            ai.version = "v2"
            ai.garbage = true
            try {
                env.translator.forceTranslate(ai, "de", "en", two)
                throw AssertionError("a garbage reply is a failure")
            } catch (_: IOException) {
            }
            assertEquals("v1:Satz 0.", env.translator.cachedTranslation(ai, "de", "en", "Satz 0."))
            // A reply that leaves one sentence out replaces only the sentence it translated.
            ai.garbage = false
            ai.skipIds = setOf(2)
            assertEquals(1, env.translator.forceTranslate(ai, "de", "en", two))
            assertEquals("v2:Satz 0.", env.translator.cachedTranslation(ai, "de", "en", "Satz 0."))
            assertEquals("v1:Satz 1.", env.translator.cachedTranslation(ai, "de", "en", "Satz 1."))
        }

    @Test
    fun otherLanguagesModelsAndSentencesKeepTheirEntries() =
        runBlocking {
            val env = Env()
            val ai = Ai(scope = "chatgpt/gpt-x/abc")
            val other = Ai(scope = "chatgpt/gpt-y/abc", version = "other")
            val two = listOf("Satz 0.", "Satz 1.")
            env.translator.translateAll(ai, "de", "en", two)
            env.translator.translateAll(ai, "de", "fa", two)
            env.translator.translateAll(other, "de", "en", two)
            ai.version = "v2"
            env.translator.forceTranslate(ai, "de", "en", listOf("Satz 0."))
            assertEquals("v2:Satz 0.", env.translator.cachedTranslation(ai, "de", "en", "Satz 0."))
            assertEquals("v1:Satz 1.", env.translator.cachedTranslation(ai, "de", "en", "Satz 1."))
            assertEquals("v1:Satz 0.", env.translator.cachedTranslation(ai, "de", "fa", "Satz 0."))
            assertEquals("other:Satz 0.", env.translator.cachedTranslation(other, "de", "en", "Satz 0."))
        }

    // A card's menu: only that segment, only the lines chosen, a stored answer does not stop it, other segments keep theirs.
    @Test
    fun aSingleSegmentRefreshAsksOnlyForThatSegmentAndTheChosenLine() =
        runBlocking {
            val env = Env()
            val second = Ai(scope = "chatgpt/second/abc")
            val third = Ai(scope = "chatgpt/third/abc")
            env.translator.translateAll(second, "de", "en", rows.map { it.originalText })
            val before = second.calls.get()
            second.version = "v2"
            val refresher = TranslationRefresher(env.translator, env.diagnostics, scope)
            val context = context(env, second, third).forRow(rows[4])
            assertEquals(true, refresher.start(context, setOf(SubtitleSlot.SECOND), RefreshScope.SENTENCE))
            refresher.state.value.untilIdle(refresher)
            assertEquals("one more request", before + 1, second.calls.get())
            assertEquals("the other line was not asked", 0, third.calls.get())
            assertEquals("v2:Satz 4.", env.translator.cachedTranslation(second, "de", "en", "Satz 4."))
            assertEquals("v1:Satz 3.", env.translator.cachedTranslation(second, "de", "en", "Satz 3."))
            assertEquals("v1:Satz 5.", env.translator.cachedTranslation(second, "de", "en", "Satz 5."))
            assertEquals(false, second.users.last().contains("Satz 3."))
        }

    // Rapid taps and a request already running: one forced request, never two at once.
    @Test
    fun repeatedTapsStartOneRequestAndNeverRunTwoAtOnce() =
        runBlocking {
            val env = Env()
            val ai = Ai(delayMs = 300)
            val refresher = TranslationRefresher(env.translator, env.diagnostics, scope)
            val context = context(env, ai, null)
            assertTrue(refresher.start(context, setOf(SubtitleSlot.SECOND), RefreshScope.SENTENCE))
            assertFalse(
                "a second tap while it runs starts nothing",
                refresher.start(context, setOf(SubtitleSlot.SECOND), RefreshScope.SENTENCE),
            )
            RefreshState().untilIdle(refresher)
            assertEquals(1, ai.calls.get())
            assertEquals(1, ai.maxRunning)
        }

    @Test
    fun aForcedRequestWaitsForARunningOneInsteadOfRunningBesideIt() =
        runBlocking {
            val env = Env()
            val ai = Ai(delayMs = 300)
            val normal = scope.launch { env.translator.translateAll(ai, "de", "en", listOf("Satz 5.")) }
            delay(100)
            val forced = scope.launch { env.translator.forceTranslate(ai, "de", "en", listOf("Satz 0.")) }
            normal.join()
            forced.join()
            assertEquals(2, ai.calls.get())
            assertEquals("one request on the wire at a time", 1, ai.maxRunning)
        }

    // Slots and scopes.
    @Test
    fun eachChosenLineAsksItsOwnTargetStraightFromTheOriginal() =
        runBlocking {
            val env = Env()
            val english = Ai(version = "EN")
            val persian = Ai(version = "FA")
            val refresher = TranslationRefresher(env.translator, env.diagnostics, scope)
            val context = context(env, english, persian)

            refresher.start(context, setOf(SubtitleSlot.SECOND), RefreshScope.SENTENCE)
            RefreshState().untilIdle(refresher)
            assertEquals(1, english.calls.get())
            assertEquals("Subtitle 1 only", 0, persian.calls.get())

            refresher.start(context, setOf(SubtitleSlot.THIRD), RefreshScope.SENTENCE)
            RefreshState().untilIdle(refresher)
            assertEquals(1, english.calls.get())
            assertEquals("Subtitle 2 only", 1, persian.calls.get())

            refresher.start(context, setOf(SubtitleSlot.SECOND, SubtitleSlot.THIRD), RefreshScope.SENTENCE)
            RefreshState().untilIdle(refresher)
            assertEquals(2, english.calls.get())
            assertEquals(2, persian.calls.get())
            // Both requests carry the German original, never the other line's translation.
            assertTrue((english.users + persian.users).all { it.contains("Satz") && !it.contains("EN:") && !it.contains("FA:") })
            assertTrue(english.systems.all { it.contains("English") && !it.contains("Persian") })
            assertTrue(persian.systems.all { it.contains("Persian") && !it.contains("English") })
        }

    @Test
    fun aLineThatDoesNotUseTheAiIsNotStarted() =
        runBlocking {
            val env = Env()
            val refresher = TranslationRefresher(env.translator, env.diagnostics, scope)
            assertFalse(refresher.start(context(env, Ai(), null), setOf(SubtitleSlot.THIRD), RefreshScope.WINDOW))
            assertFalse(refresher.start(context(env, null, null), setOf(SubtitleSlot.SECOND), RefreshScope.WINDOW))
        }

    @Test
    fun theScopeDecidesHowManySentencesAreAsked() =
        runBlocking {
            val env = Env()
            val ai = Ai()
            val refresher = TranslationRefresher(env.translator, env.diagnostics, scope)
            val context =
                context(env, ai, null) { scopeAsked ->
                    when (scopeAsked) {
                        RefreshScope.SENTENCE -> listOf("Satz 0.")
                        RefreshScope.WINDOW -> rows.take(5).map { it.originalText }
                        RefreshScope.VIDEO -> rows.map { it.originalText }
                    }
                }
            val counts =
                RefreshScope.entries.map { chosen ->
                    ai.users.clear()
                    refresher.start(context, setOf(SubtitleSlot.SECOND), chosen)
                    RefreshState().untilIdle(refresher)
                    JSONObject(ai.users.single()).getJSONArray("sentences").length()
                }
            assertEquals(listOf(1, 5, 12), counts)
        }

    // Failures, cancellation, staleness.
    @Test
    fun aUsageLimitKeepsTheOldTextAndReportsASafeReason() =
        runBlocking {
            val env = Env()
            val ai = Ai()
            env.translator.translateAll(ai, "de", "en", listOf("Satz 0."))
            ai.failWith = ChatGptException(ChatGptErrorKind.USAGE_LIMIT, "limit")
            val refresher = TranslationRefresher(env.translator, env.diagnostics, scope)
            refresher.start(context(env, ai, null), setOf(SubtitleSlot.SECOND), RefreshScope.SENTENCE)
            val done = RefreshState().untilIdle(refresher)
            assertEquals("USAGE_LIMIT", done.failed[SubtitleSlot.SECOND])
            assertEquals("v1:Satz 0.", env.translator.cachedTranslation(ai, "de", "en", "Satz 0."))
            val failure =
                env.diagnostics.events.value
                    .last { it.kind == TranslationEventKind.REFRESH_FAILED }
            assertEquals("USAGE_LIMIT", failure.reason)
        }

    @Test
    fun cancellingMidRequestChangesNothing() =
        runBlocking {
            val env = Env()
            val ai = Ai()
            env.translator.translateAll(ai, "de", "en", listOf("Satz 0."))
            ai.gate = CompletableDeferred()
            ai.version = "v2"
            val refresher = TranslationRefresher(env.translator, env.diagnostics, scope)
            refresher.start(context(env, ai, null), setOf(SubtitleSlot.SECOND), RefreshScope.SENTENCE)
            withTimeout(3000) { refresher.state.first { it.running.isNotEmpty() } }
            refresher.cancel()
            // The cancelled request has left the provider before the gate opens, so the order is not a race.
            withTimeout(3000) { while (ai.running.get() > 0) delay(10) }
            ai.gate!!.complete(Unit)
            delay(200)
            assertTrue(
                refresher.state.value.running
                    .isEmpty(),
            )
            assertEquals("v1:Satz 0.", env.translator.cachedTranslation(ai, "de", "en", "Satz 0."))
            // And the sentence can be refreshed again afterwards.
            ai.gate = null
            refresher.start(context(env, ai, null), setOf(SubtitleSlot.SECOND), RefreshScope.SENTENCE)
            RefreshState().untilIdle(refresher)
            assertEquals("v2:Satz 0.", env.translator.cachedTranslation(ai, "de", "en", "Satz 0."))
        }

    @Test
    fun aRunThatIsNoLongerCurrentReportsNoFailure() =
        runBlocking {
            val env = Env()
            val ai = Ai(failWith = IOException("down"), delayMs = 100)
            val current =
                java.util.concurrent.atomic
                    .AtomicBoolean(true)
            val refresher = TranslationRefresher(env.translator, env.diagnostics, scope)
            refresher.start(context(env, ai, null, current = { current.get() }), setOf(SubtitleSlot.SECOND), RefreshScope.SENTENCE)
            current.set(false)
            val done = RefreshState().untilIdle(refresher)
            assertTrue(done.failed.isEmpty())
        }

    @Test
    fun theEventsTellTheStoryWithoutAnyText() =
        runBlocking {
            val env = Env()
            val ai = Ai()
            val refresher = TranslationRefresher(env.translator, env.diagnostics, scope)
            refresher.start(context(env, ai, null), setOf(SubtitleSlot.SECOND), RefreshScope.SENTENCE)
            RefreshState().untilIdle(refresher)
            val kinds =
                env.diagnostics.events.value
                    .map { it.kind }
            assertEquals(
                listOf(
                    TranslationEventKind.REFRESH_REQUESTED,
                    TranslationEventKind.CACHE_BYPASSED,
                    TranslationEventKind.AI_REQUEST,
                    TranslationEventKind.AI_SUCCEEDED,
                    TranslationEventKind.REFRESH_SUCCEEDED,
                ),
                kinds,
            )
            assertTrue(
                env.diagnostics.events.value
                    .all { it.target == "en" && it.origin?.model == "gpt-x" },
            )
        }

    // The screen: the visible text changes without a reload, and nothing is blanked while waiting.
    @Test
    fun theVisibleTextChangesAfterTheRefreshAndStaysWhileItRuns() =
        runBlocking {
            val directory = Files.createTempDirectory("force-ui").toFile()
            try {
                SubtitleStore.create(directory, rows).use { store ->
                    val env = Env()
                    val ai = Ai(delayMs = 50)
                    val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                    val shown = MutableStateFlow(emptyList<SubtitleSegment>())
                    val job =
                        scope.launch {
                            coroutineScope {
                                val window = AiWindowTranslation(this, env.translator, ai, "de" to "en", retryMs = 100) { emptyList() }
                                val upgrades = aiTranslationUpgrades(env.translator, env.diagnostics, ai, "de", "en", window)
                                translatePlaybackWindowTracked(
                                    store,
                                    requests,
                                    { text ->
                                        window.cached(text)?.let { Translated(it, window.originOf(text)) }
                                            ?: Translated("G:$text", TranslationOrigin.GOOGLE)
                                    },
                                    prefetch = { texts -> window.ask(texts) },
                                    upgrades = upgrades,
                                ) { w, _ -> shown.value = w }
                            }
                        }
                    try {
                        withTimeout(15000) { shown.first { it.firstOrNull()?.translatedText == "v1:Satz 0." } }
                        val firstLine = shown.value.first()
                        assertEquals(TranslationProvider.CHATGPT, firstLine.translationOrigin?.provider)

                        ai.version = "v2"
                        ai.delayMs = 400
                        val refresher = TranslationRefresher(env.translator, env.diagnostics, scope)
                        refresher.start(
                            context(env, ai, null, texts = { rows.map { it.originalText } }),
                            setOf(SubtitleSlot.SECOND),
                            RefreshScope.WINDOW,
                        )
                        withTimeout(3000) { refresher.state.first { it.running.isNotEmpty() } }
                        delay(150)
                        assertEquals("the old text stays while the request runs", "v1:Satz 0.", shown.value.first().translatedText)
                        val fresh = withTimeout(15000) { shown.first { it.firstOrNull()?.translatedText == "v2:Satz 0." } }
                        assertEquals(false, fresh.first().translationOrigin?.cached)
                        assertEquals(TranslationProvider.CHATGPT, fresh.first().translationOrigin?.provider)
                        assertNull(refresher.state.value.failed[SubtitleSlot.SECOND])
                    } finally {
                        job.cancelAndJoin()
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun aCancelDuringTheCommitNeverLeavesMemoryAndDiskDifferent() =
        runBlocking {
            val directory = Files.createTempDirectory("force-commit").toFile()
            try {
                repeat(20) {
                    val env = Env(directory = directory)
                    val ai = Ai(version = "v$it")
                    val job = scope.launch { env.translator.forceTranslate(ai, "de", "en", listOf("Satz 0.")) }
                    delay((it % 3).toLong())
                    job.cancel()
                    job.join()
                    val inMemory = env.translator.shownTranslation(ai, "de", "en", "Satz 0.")
                    val onDisk = AiSubtitleTranslator(cacheDirectory = directory).cachedTranslation(ai, "de", "en", "Satz 0.")
                    // Either the whole commit happened (memory and disk hold the new text) or none of it (memory is empty).
                    assertEquals("memory and disk agree after a cancel", inMemory, onDisk.takeIf { inMemory != null })
                    directory.listFiles()?.forEach { f -> f.deleteRecursively() }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun stoppingAndStartingAgainAtOnceStillRefreshesTheSentence() =
        runBlocking {
            val env = Env()
            val ai = Ai(delayMs = 200)
            val refresher = TranslationRefresher(env.translator, env.diagnostics, scope)
            val context = context(env, ai, null)
            refresher.start(context, setOf(SubtitleSlot.SECOND), RefreshScope.SENTENCE)
            delay(50)
            refresher.cancel()
            // Started again immediately: the cancelled run's cleanup must neither clear this run nor block its sentence.
            assertTrue(refresher.start(context, setOf(SubtitleSlot.SECOND), RefreshScope.SENTENCE))
            val done = RefreshState().untilIdle(refresher)
            assertTrue(done.failed.isEmpty())
            assertEquals("v1:Satz 0.", env.translator.cachedTranslation(ai, "de", "en", "Satz 0."))
        }

    @Test
    fun aLastingRefusalIsNotAskedAgain() =
        runBlocking {
            val env = Env()
            val blocked = BlockedAi()
            val window = AiWindowTranslation(scope, env.translator, blocked, "de" to "en", retryMs = 50) { emptyList() }
            assertTrue(window.ensure(listOf("Satz 0.")) != null)
            delay(100)
            window.ensure(listOf("Satz 0."))
            assertEquals("a blocked model is never even asked", 0, blocked.calls.get())
        }

    private class BlockedAi : AiTranslationModel {
        override val scope = "chatgpt/gpt-x/abc"
        val calls = AtomicInteger()

        override fun isBlocked() = true

        override suspend fun ask(
            system: String,
            user: String,
            onText: (String) -> Unit,
        ): String {
            calls.incrementAndGet()
            throw IOException("blocked")
        }
    }

    @Test
    fun inGoogleOnlyAManualRefreshBringsTheAiTextWithoutAnyAutomaticRequest() =
        runBlocking {
            val directory = Files.createTempDirectory("force-google-only").toFile()
            try {
                SubtitleStore.create(directory, rows).use { store ->
                    val env = Env()
                    val ai = Ai(delayMs = 20)
                    val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                    val shown = MutableStateFlow(emptyList<SubtitleSegment>())
                    val job =
                        scope.launch {
                            // Google only: no window, no prefetch, no ensure; the model is there only to show a manual result.
                            val upgrades = aiTranslationUpgrades(env.translator, env.diagnostics, ai, "de", "en", null)
                            translatePlaybackWindowTracked(
                                store,
                                requests,
                                { text -> Translated("G:$text", TranslationOrigin.GOOGLE) },
                                upgrades = upgrades,
                            ) { w, _ -> shown.value = w }
                        }
                    try {
                        withTimeout(15000) { shown.first { it.firstOrNull()?.translatedText == "G:Satz 0." } }
                        delay(500)
                        assertEquals("Google only never asks the AI by itself", 0, ai.calls.get())
                        val refresher = TranslationRefresher(env.translator, env.diagnostics, scope)
                        refresher.start(
                            context(env, ai, null, texts = { rows.map { it.originalText } }),
                            setOf(SubtitleSlot.SECOND),
                            RefreshScope.WINDOW,
                        )
                        val fresh = withTimeout(15000) { shown.first { it.firstOrNull()?.translatedText == "v1:Satz 0." } }
                        assertEquals(TranslationProvider.CHATGPT, fresh.first().translationOrigin?.provider)
                        assertEquals("only the manual request reached the AI", 1, ai.calls.get())
                    } finally {
                        job.cancelAndJoin()
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }
}
