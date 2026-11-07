package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.SecretStorage
import com.kienhoang.dualsubreplay.chatgpt.CHATGPT_PLAN_SCOPE
import com.kienhoang.dualsubreplay.chatgpt.ChatGptApi
import com.kienhoang.dualsubreplay.chatgpt.ChatGptCredentialStore
import com.kienhoang.dualsubreplay.chatgpt.ChatGptCredentials
import com.kienhoang.dualsubreplay.chatgpt.ChatGptErrorKind
import com.kienhoang.dualsubreplay.chatgpt.ChatGptException
import com.kienhoang.dualsubreplay.chatgpt.ChatGptGate
import com.kienhoang.dualsubreplay.chatgpt.ChatGptModel
import com.kienhoang.dualsubreplay.chatgpt.ChatGptSession
import com.kienhoang.dualsubreplay.chatgpt.ChatGptTranslationModel
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Request-count audit of AI subtitle translation (upstream PR #205 integrated into the fork), with fake models that count
 * the requests they receive: the keyed-service path (a plain [AiTranslationModel]) and the real [ChatGptTranslationModel]
 * behind a fake [ChatGptApi]. Every scenario prints one `AUDIT` line with the measured numbers.
 */
class AiRequestEfficiencyTest {
    // A sentence every 3 s for 30 minutes.
    private val rows = List(600) { SubtitleSegment(it.toLong(), it * 3_000L, (it + 1) * 3_000L, "Line number $it is spoken here.") }

    /** Who received what: the target language of each request (from the system text) and its sentences. */
    private class RequestLog {
        class Request(
            val pair: String,
            val sentences: List<String>,
        )

        val requests = CopyOnWriteArrayList<Request>()

        fun total() = requests.size

        fun forPair(pair: String) = requests.filter { it.pair == pair }

        fun sentenceCount(pair: String) = forPair(pair).sumOf { it.sentences.size }

        /** Sentences sent more than once for the same pair (what a perfect cache and dedup would make zero). */
        fun duplicates(pair: String): Int {
            val seen = HashSet<String>()
            return forPair(pair).sumOf { request -> request.sentences.count { !seen.add(it) } }
        }

        fun record(
            system: String,
            user: String,
        ): String {
            val pair = if (system.contains("French")) "fr" else "vi"
            val sentences = JSONObject(user).getJSONArray("sentences")
            val texts = List(sentences.length()) { sentences.getJSONObject(it).getJSONArray("rows").join(" ") }
            requests += Request(pair, texts)
            val answers = JSONArray()
            for (index in 0 until sentences.length()) {
                val sentence = sentences.getJSONObject(index)
                val sentenceRows = sentence.getJSONArray("rows")
                answers.put(
                    JSONObject()
                        .put("id", sentence.getInt("id"))
                        .put("rows", JSONArray(List(sentenceRows.length()) { "T:${sentenceRows.getString(it)}" })),
                )
            }
            return JSONObject().put("sentences", answers).toString()
        }
    }

    /** The keyed-service path: any [AiTranslationModel], answered locally. */
    private class FakeModel(
        val log: RequestLog,
        override val scope: String = "fake/model",
        private val failFirst: Int = 0,
    ) : AiTranslationModel {
        private val calls = AtomicInteger()

        override suspend fun ask(
            system: String,
            user: String,
            onText: (String) -> Unit,
        ): String {
            if (calls.incrementAndGet() <= failFirst) {
                log.requests += RequestLog.Request(if (system.contains("French")) "fr" else "vi", emptyList())
                throw IOException("service busy")
            }
            return log.record(system, user).also(onText)
        }
    }

    private class MemoryStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()

        override fun get(name: String) = map[name]

        override fun put(values: Map<String, String>) {
            map.putAll(values)
        }

        override fun remove(names: List<String>) {
            names.forEach(map::remove)
        }
    }

    private object PlainCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    /** ChatGPT Subscription's HTTP side; [limitAfter] requests succeed, then the plan answers "usage limit". */
    private class FakeChatGptApi(
        val log: RequestLog,
        private val limitAfter: Int = Int.MAX_VALUE,
    ) : ChatGptApi {
        val streams = AtomicInteger()

        override suspend fun exchange(
            clientId: String,
            code: String,
            verifier: String,
            redirectUri: String,
        ) = error("unused")

        override suspend fun refresh(
            clientId: String,
            refreshToken: String,
        ) = error("unused")

        override suspend fun revoke(
            clientId: String,
            token: String,
        ) = Unit

        override suspend fun jwks() = error("unused")

        override suspend fun models(accessToken: String) = emptyList<ChatGptModel>()

        override suspend fun stream(
            accessToken: String,
            body: String,
            onText: (String) -> Unit,
        ): String {
            if (streams.incrementAndGet() > limitAfter) throw ChatGptException(ChatGptErrorKind.USAGE_LIMIT, "usage limit")
            val request = JSONObject(body)
            val user = request.getJSONArray("input").getJSONObject(0).getString("content")
            return log.record(request.getString("instructions"), user).also(onText)
        }
    }

    private fun chatGpt(
        api: FakeChatGptApi,
        subject: String = "sub-1",
    ): ChatGptTranslationModel {
        val credentials =
            ChatGptCredentials("oaiapp_1", subject, null, "id", "access", "refresh", Long.MAX_VALUE / 2, setOf(CHATGPT_PLAN_SCOPE), null)
        val store = ChatGptCredentialStore(MemoryStorage(), PlainCipher).also { it.save(credentials) }
        return ChatGptTranslationModel("gpt-x", credentials.cacheScope("gpt-x"), ChatGptSession(store, api), api, ChatGptGate())
    }

    private fun windowTexts(timeMs: Long) = translationTexts(rows.filter { it.startMs >= timeMs && it.startMs < timeMs + PREFETCH_AHEAD_MS })

    /** Plays [fromMs]..[toMs] in 3 s steps like the app: the window asks for the playing minute, the background loop for minutes 2 to 5. */
    private suspend fun play(
        store: SubtitleStore,
        translator: AiSubtitleTranslator,
        model: AiTranslationModel,
        lines: List<String>,
        times: Iterable<Long>,
        backgroundGuard: Boolean = true,
        requests: MutableStateFlow<CaptionPlaybackRequest> = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true)),
    ) = coroutineScope {
        val fill: suspend () -> List<String> = {
            val timeMs = requests.value.timeMs
            val indices = store.indicesBetween(timeMs + PREFETCH_AHEAD_MS, timeMs + BACKGROUND_PREFETCH_AHEAD_MS, 240)
            translationTexts(store.read(indices))
        }
        val windows = lines.map { target -> AiWindowTranslation(this, translator, model, "en" to target, ahead = fill) }
        // Only Subtitle 1 has a background loop; Subtitle 2's work is its window (see ThirdLineTranslation.start).
        val background =
            launch {
                prefetchInBackground(store, requests, pauseMs = { 1 }, restMs = 1) { texts ->
                    if (backgroundGuard) {
                        translator.translateNextBatch(model, "en", lines.first(), texts)
                    } else {
                        // The pre-optimization behavior, approximately: whatever is missing is sent at once.
                        translator.translateAll(model, "en", lines.first(), texts)
                        texts
                    }
                }
            }
        try {
            for (timeMs in times) {
                requests.value = requests.value.copy(timeMs = timeMs)
                val texts = windowTexts(timeMs)
                windows.forEach { it.ask(texts) }
                windows.forEach { window -> aiSentencesFirst(texts)?.let { window.await(it) } }
                delay(3)
            }
        } finally {
            background.cancelAndJoin()
        }
    }

    private fun aiSentencesFirst(texts: List<String>) = texts.firstOrNull()

    private fun <T> withStore(block: suspend (SubtitleStore, File) -> T): T {
        val directory = Files.createTempDirectory("ai-request-efficiency").toFile()
        return try {
            runBlocking { SubtitleStore.create(File(directory, "store").also { it.mkdirs() }, rows).use { block(it, directory) } }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun audit(
        name: String,
        log: RequestLog,
        vararg pairs: String,
    ) {
        val parts =
            pairs.joinToString(" ") { pair ->
                val sized = log.forPair(pair)
                "$pair: requests=${sized.size} sentences=${log.sentenceCount(pair)} " +
                    "avg=${if (sized.isEmpty()) 0 else log.sentenceCount(pair) / sized.size} " +
                    "min=${sized.minOfOrNull { it.sentences.size } ?: 0} dup=${log.duplicates(pair)}"
            }
        println("AUDIT $name total=${log.total()} | $parts")
    }

    private val thirtyMinutes = 0L..(25 * 60_000L) step 3_000L

    @Test
    fun keyedServiceOneLineThirtyMinutes() =
        withStore { store, _ ->
            val log = RequestLog()
            play(store, AiSubtitleTranslator(null), FakeModel(log), listOf("vi"), thirtyMinutes)
            audit("keyed.oneLine", log, "vi")
            assertEquals(0, log.duplicates("vi"))
            assertTrue("requests ${log.total()}", log.total() <= 16)
        }

    @Test
    fun chatGptSubscriptionOneLineThirtyMinutesMatchesTheKeyedPath() =
        withStore { store, _ ->
            val keyedLog = RequestLog()
            play(store, AiSubtitleTranslator(null), FakeModel(keyedLog), listOf("vi"), thirtyMinutes)
            val log = RequestLog()
            val api = FakeChatGptApi(log)
            play(store, AiSubtitleTranslator(null), chatGpt(api), listOf("vi"), thirtyMinutes)
            audit("chatgpt.oneLine", log, "vi")
            assertEquals(0, log.duplicates("vi"))
            assertEquals("every request went through the model once", log.total(), api.streams.get())
            assertTrue("chatgpt ${log.total()} vs keyed ${keyedLog.total()}", log.total() <= keyedLog.total() + 2)
        }

    @Test
    fun chatGptSubscriptionTwoLinesAreTwoIndependentLegitimateStreamsWithoutDuplicates() =
        withStore { store, _ ->
            val log = RequestLog()
            play(store, AiSubtitleTranslator(null), chatGpt(FakeChatGptApi(log)), listOf("vi", "fr"), thirtyMinutes)
            audit("chatgpt.twoLines", log, "vi", "fr")
            assertEquals(0, log.duplicates("vi"))
            assertEquals(0, log.duplicates("fr"))
            assertTrue("fr requests ${log.forPair("fr").size}", log.forPair("fr").size <= 16)
            assertTrue("vi requests ${log.forPair("vi").size}", log.forPair("vi").size <= 16)
        }

    @Test
    fun theGuardCutsBackgroundRequestsAgainstTheUnguardedBaseline() =
        withStore { store, _ ->
            val guarded = RequestLog()
            play(store, AiSubtitleTranslator(null), FakeModel(guarded), listOf("vi"), thirtyMinutes)
            val baseline = RequestLog()
            play(store, AiSubtitleTranslator(null), FakeModel(baseline), listOf("vi"), thirtyMinutes, backgroundGuard = false)
            audit("guarded", guarded, "vi")
            audit("unguarded.baseline", baseline, "vi")
            assertTrue("guarded ${guarded.total()} vs baseline ${baseline.total()}", guarded.total() < baseline.total())
        }

    @Test
    fun frequentSeeksNeverResendASentence() =
        withStore { store, _ ->
            val log = RequestLog()
            // Jumps back and forth across the video, revisiting places.
            val jumps = listOf(0L, 600_000L, 30_000L, 900_000L, 610_000L, 20_000L, 1_200_000L, 905_000L, 40_000L, 1_500_000L, 615_000L)
            val times = jumps.flatMap { start -> (start..(start + 60_000L) step 3_000L).toList() }
            play(store, AiSubtitleTranslator(null), chatGpt(FakeChatGptApi(log)), listOf("vi", "fr"), times)
            audit("chatgpt.seeks", log, "vi", "fr")
            assertEquals(0, log.duplicates("vi"))
            assertEquals(0, log.duplicates("fr"))
        }

    @Test
    fun aPausedVideoSendsNoBackgroundRequests() =
        withStore { store, _ ->
            coroutineScope {
                val log = RequestLog()
                val translator = AiSubtitleTranslator(null)
                val model = FakeModel(log)
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = true, enabled = true))
                val job: Job =
                    launch {
                        prefetchInBackground(store, requests, pauseMs = { 1 }, restMs = 1) { texts ->
                            translator.translateNextBatch(model, "en", "vi", texts)
                        }
                    }
                delay(150)
                assertEquals(0, log.total())
                job.cancelAndJoin()
                audit("paused", log, "vi")
            }
        }

    @Test
    fun replayingWithTheDiskCacheSendsNothing() =
        withStore { store, directory ->
            val cacheDirectory = File(directory, "ai-cache").also { it.mkdirs() }
            val first = RequestLog()
            play(store, AiSubtitleTranslator(cacheDirectory), FakeModel(first), listOf("vi"), 0L..(5 * 60_000L) step 3_000L)
            val second = RequestLog()
            play(store, AiSubtitleTranslator(cacheDirectory), FakeModel(second), listOf("vi"), 0L..(4 * 60_000L) step 3_000L)
            audit("diskCache.first", first, "vi")
            audit("diskCache.second", second, "vi")
            assertTrue("first run sent requests", first.total() > 0)
            assertEquals("a second translator on the same cache asks nothing for the same minutes", 0, second.total())
        }

    @Test
    fun aFailedRequestRestsBeforeTheNextOneAndThenRetriesOnce() =
        runBlocking {
            val log = RequestLog()
            var clock = 1_000_000L
            val translator = AiSubtitleTranslator(null, now = { clock })
            val model = FakeModel(log, failFirst = 1)
            val texts = windowTexts(0)
            try {
                translator.translateAll(model, "en", "vi", texts)
            } catch (_: IOException) {
            }
            repeat(5) { translator.translateAll(model, "en", "vi", texts) }
            assertEquals("no new request while the AI rests", 1, log.total())
            clock += 31_000L
            translator.translateAll(model, "en", "vi", texts)
            translator.translateAll(model, "en", "vi", texts)
            audit("failureRetry", log, "vi")
            assertEquals("one retry after the rest, none after it worked", 2, log.total())
        }

    @Test
    fun chatGptUsageLimitStopsRequestsLocallyForEveryPath() =
        runBlocking {
            val log = RequestLog()
            val api = FakeChatGptApi(log, limitAfter = 0)
            var clock = 1_000_000L
            val translator = AiSubtitleTranslator(null, now = { clock })
            val model = chatGpt(api)
            val many = translationTexts(rows.take(80))
            repeat(3) {
                try {
                    translator.translateAll(model, "en", "vi", many)
                } catch (_: IOException) {
                }
                try {
                    translator.translateNextBatch(model, "en", "vi", many)
                } catch (_: IOException) {
                }
                try {
                    translator.translateAll(model, "en", "fr", many)
                } catch (_: IOException) {
                }
            }
            println("AUDIT chatgpt.usageLimit network calls=${api.streams.get()}")
            assertEquals("only the first request reached the network; the gate blocks the rest locally", 1, api.streams.get())
            clock += 31_000L
            assertTrue("the plan still reports blocked after the translator's own rest", model.isBlocked())
        }

    @Test
    fun concurrentWindowsAndCallersForTheSameSentencesSendOneRequest() =
        runBlocking {
            val log = RequestLog()
            val translator = AiSubtitleTranslator(null)
            val model = FakeModel(log)
            val texts = windowTexts(0)
            coroutineScope {
                val window = AiWindowTranslation(this, translator, model, "en" to "vi") { emptyList() }
                List(8) { launch { translator.translateAll(model, "en", "vi", texts) } }.joinAll()
                repeat(8) { window.ask(texts) }
                window.await(texts.first())
            }
            audit("concurrent", log, "vi")
            assertEquals(1, log.total())
        }

    @Test
    fun concurrentCallersStartedTogetherAreStillOneRequest() =
        runBlocking {
            val log = RequestLog()
            val translator = AiSubtitleTranslator(null)
            val model = FakeModel(log)
            val texts = windowTexts(0)
            coroutineScope {
                val jobs: List<Job> = List(8) { launch(kotlinx.coroutines.Dispatchers.Default) { translator.translateAll(model, "en", "vi", texts) } }
                jobs.joinAll()
            }
            audit("concurrent.parallel", log, "vi")
            assertEquals(1, log.total())
        }

    @Test
    fun anotherModelOrLanguageIsAnotherCacheScopeAndTheSameOneIsNot() =
        runBlocking {
            val log = RequestLog()
            val translator = AiSubtitleTranslator(null)
            val texts = windowTexts(0)
            val a = FakeModel(log, scope = "gpt/a")
            val b = FakeModel(log, scope = "gpt/b")
            translator.translateAll(a, "en", "vi", texts)
            translator.translateAll(a, "en", "vi", texts)
            translator.translateAll(b, "en", "vi", texts)
            translator.translateAll(a, "en", "fr", texts)
            translator.translateAll(a, "de", "vi", texts)
            audit("scopes", log, "vi", "fr")
            assertEquals("a:en>vi, b:en>vi, a:en>fr, a:de>vi", 4, log.total())
        }
}
