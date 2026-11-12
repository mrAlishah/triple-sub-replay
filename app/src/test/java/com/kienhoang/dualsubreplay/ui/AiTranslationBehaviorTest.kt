package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.assistant.AI_PROVIDER_PREFERENCE
import com.kienhoang.dualsubreplay.assistant.AI_TRANSLATION_MODEL_PREFERENCE
import com.kienhoang.dualsubreplay.assistant.AI_TRANSLATION_PROVIDER_PREFERENCE
import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.SecretStorage
import com.kienhoang.dualsubreplay.assistant.aiTranslationChoiceKeys
import com.kienhoang.dualsubreplay.chatgpt.AI_ACCESS_METHOD_PREFERENCE
import com.kienhoang.dualsubreplay.chatgpt.CHATGPT_MODEL_PREFERENCE
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
import com.kienhoang.dualsubreplay.translation.AI_TRANSLATION_ONLY_PREFERENCE
import com.kienhoang.dualsubreplay.translation.AI_TRANSLATION_PREFERENCE
import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.TRANSLATION_ENGINE_PREFERENCE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behavior of the AI translation window and translator around the request-reduction work: Google first and AI only, per-tab
 * choices, cancellation and stale results, failure handling and the background/window lock. Models are fakes; no network, no
 * real time (a fake clock where time matters).
 */
class AiTranslationBehaviorTest {
    private val rows = List(100) { SubtitleSegment(it.toLong(), it * 3_000L, (it + 1) * 3_000L, "Line number $it is spoken here.") }

    private fun texts(range: IntRange) = translationTexts(rows.slice(range))

    /** A model that records the sentences of each request and can hold or fail them. */
    private inner class GateModel(
        override val scope: String = "fake/gate",
        private val prefix: String = "T:",
    ) : AiTranslationModel {
        val requests = CopyOnWriteArrayList<List<String>>()

        @Volatile var hold: CompletableDeferred<Unit>? = null

        @Volatile var failNext = 0

        @Volatile var garbage = false

        override suspend fun ask(
            system: String,
            user: String,
            onText: (String) -> Unit,
        ): String {
            val sentences = JSONObject(user).getJSONArray("sentences")
            requests +=
                List(sentences.length()) { index ->
                    val sentenceRows = sentences.getJSONObject(index).getJSONArray("rows")
                    List(sentenceRows.length()) { sentenceRows.getString(it) }.joinToString(" ")
                }
            hold?.await()
            if (failNext > 0) {
                failNext--
                throw IOException("busy")
            }
            if (garbage) return "this is not json".also(onText)
            return answer(user, prefix).also(onText)
        }
    }

    private suspend fun waitForRequest(model: GateModel) = withTimeout(5_000) { while (model.requests.isEmpty()) yield() }

    @Test
    fun googleFirstTheWindowNeverBlocksOnTheAiAndASecondAskAddsNothing() =
        runBlocking {
            val model = GateModel().apply { hold = CompletableDeferred() }
            val texts = texts(0..19)
            coroutineScope {
                val window = AiWindowTranslation(this, AiSubtitleTranslator(null), model, "en" to "vi") { emptyList() }
                window.ask(texts)
                window.ask(texts)
                waitForRequest(model)
                assertNull("Google's line shows while the AI is still working", window.cached(texts.first()))
                model.hold!!.complete(Unit)
                assertEquals("T:${texts.first()}", window.await(texts.first()))
            }
            assertEquals("a request in flight is never doubled", 1, model.requests.size)
        }

    @Test
    fun aiOnlyWaitsForTheFirstSentenceInOneRequest() =
        runBlocking {
            val model = GateModel()
            val texts = texts(0..19)
            coroutineScope {
                val window = AiWindowTranslation(this, AiSubtitleTranslator(null), model, "en" to "vi") { emptyList() }
                window.first(texts)
                assertNotNull(window.cached(texts.first()))
            }
            assertEquals(1, model.requests.size)
        }

    @Test
    fun aiOnlyDoesNotHammerTheServiceAfterAFailedFirstRequest() =
        runBlocking {
            val model = GateModel().apply { failNext = 1 }
            coroutineScope {
                val window = AiWindowTranslation(this, AiSubtitleTranslator(null), model, "en" to "vi") { emptyList() }
                window.first(texts(0..19))
            }
            assertEquals("first() asks twice at most, and the failure rest stops the second", 1, model.requests.size)
        }

    @Test
    fun aTranslatorThatNeverFailedHasNoRestAndTheLevelTriggeredLookAsksAtOnce() =
        runBlocking {
            // A realistic wall clock: the rest used to be computed as MIN_VALUE - now, which wrapped to a huge number.
            val translator = AiSubtitleTranslator(null, now = { 1_780_000_000_000L })
            assertEquals(0L, translator.restMs())
            val model = GateModel()
            val texts = texts(0..19)
            coroutineScope {
                val window = AiWindowTranslation(this, translator, model, "en" to "vi", clock = { 1_780_000_000_000L }) { emptyList() }
                window.ensure(texts)
                window.await(texts.first())
            }
            assertEquals(1, model.requests.size)
        }

    @Test
    fun aTabOwnsItsAiTranslationChoicesButNotTheChatServiceOrKeys() {
        val owned =
            listOf(
                TRANSLATION_ENGINE_PREFERENCE,
                AI_TRANSLATION_PREFERENCE,
                AI_TRANSLATION_ONLY_PREFERENCE,
                AI_TRANSLATION_PROVIDER_PREFERENCE,
                AI_ACCESS_METHOD_PREFERENCE,
                AI_TRANSLATION_MODEL_PREFERENCE,
                CHATGPT_MODEL_PREFERENCE,
            )
        owned.forEach { assertTrue("$it should be per tab", it in TAB_SETTING_KEYS) }
        assertTrue(aiTranslationChoiceKeys().all { it in TAB_SETTING_KEYS })
        assertFalse("the Chat AI Service stays global", AI_PROVIDER_PREFERENCE in TAB_SETTING_KEYS)

        val first = TabSettingsProfile().with(AI_TRANSLATION_ONLY_PREFERENCE, true).with(AI_TRANSLATION_PROVIDER_PREFERENCE, "gemini")
        val second = TabSettingsProfile().with(AI_TRANSLATION_ONLY_PREFERENCE, false).with(AI_TRANSLATION_PROVIDER_PREFERENCE, "openai")
        assertEquals(true, first.values[AI_TRANSLATION_ONLY_PREFERENCE])
        assertEquals(false, second.values[AI_TRANSLATION_ONLY_PREFERENCE])
        assertEquals(first, first.with(AI_PROVIDER_PREFERENCE, "claude"))
        assertEquals(first, decodeTabSettings(encodeTabSettings(first)))
    }

    @Test
    fun cancellingTheRunningRequestFreesTheLockAndTheNextAskSendsOneMore() =
        runBlocking {
            val model = GateModel().apply { hold = CompletableDeferred() }
            val translator = AiSubtitleTranslator(null)
            val texts = texts(0..19)
            coroutineScope {
                val job = launch { translator.translateAll(model, "en", "vi", texts) }
                waitForRequest(model)
                job.cancelAndJoin()
            }
            model.hold = null
            translator.translateAll(model, "en", "vi", texts)
            assertEquals("the cancelled request and exactly one new one", 2, model.requests.size)
            assertNotNull(translator.cachedTranslation(model, "en", "vi", texts.first()))
        }

    @Test
    fun aNewVideoAsksOnlyForLinesTheCacheDoesNotHave() =
        runBlocking {
            val model = GateModel()
            val translator = AiSubtitleTranslator(null)
            val firstVideo = texts(0..19)
            translator.translateAll(model, "en", "vi", firstVideo)
            val secondVideo = firstVideo.takeLast(10) + texts(20..29)
            coroutineScope {
                val window = AiWindowTranslation(this, translator, model, "en" to "vi") { emptyList() }
                window.ask(secondVideo)
                window.await(secondVideo.last())
            }
            assertEquals(2, model.requests.size)
            assertEquals("only the new lines go out", texts(20..29), model.requests[1])
        }

    @Test
    fun anotherModelOrLanguageNeverReadsOrOverwritesTheFirstOnesLines() =
        runBlocking {
            val translator = AiSubtitleTranslator(null)
            val texts = texts(0..4)
            val a = GateModel(scope = "gpt/a", prefix = "A:")
            val b = GateModel(scope = "gpt/b", prefix = "B:")
            translator.translateAll(a, "en", "vi", texts)
            assertNull(translator.cachedTranslation(b, "en", "vi", texts.first()))
            assertNull(translator.cachedTranslation(a, "en", "fr", texts.first()))
            translator.translateAll(b, "en", "vi", texts)
            assertEquals("A:${texts.first()}", translator.cachedTranslation(a, "en", "vi", texts.first()))
            assertEquals("B:${texts.first()}", translator.cachedTranslation(b, "en", "vi", texts.first()))
        }

    @Test
    fun afterAFailureTheWindowWaitsOutTheRestAndAsksOnceMore() =
        runBlocking {
            var clock = 1_000_000L
            val translator = AiSubtitleTranslator(null, now = { clock })
            val model = GateModel().apply { failNext = 1 }
            val texts = texts(0..19)
            coroutineScope {
                val window = AiWindowTranslation(this, translator, model, "en" to "vi", clock = { clock }) { emptyList() }
                window.ensure(texts)
                window.await(texts.first())
                assertEquals(1, model.requests.size)
                repeat(6) {
                    clock += 2_000
                    window.ensure(texts)
                }
                assertEquals("no request while the AI rests", 1, model.requests.size)
                clock += 40_000
                window.ensure(texts)
                window.await(texts.first())
                assertEquals(2, model.requests.size)
                repeat(3) {
                    clock += 5_000
                    assertNull("nothing is owed any more", window.ensure(texts))
                }
            }
            assertEquals(2, model.requests.size)
        }

    @Test
    fun anUnreadableReplyIsNotAskedAgainForTheSameLines() =
        runBlocking {
            val model = GateModel().apply { garbage = true }
            val translator = AiSubtitleTranslator(null)
            val texts = texts(0..19)
            repeat(5) {
                try {
                    translator.translateAll(model, "en", "vi", texts)
                } catch (_: IOException) {
                }
            }
            assertEquals(1, model.requests.size)
        }

    private class UsageLimitApi : ChatGptApi {
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
            streams.incrementAndGet()
            throw ChatGptException(ChatGptErrorKind.USAGE_LIMIT, "usage limit")
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

    @Test
    fun aBlockedChatGptPlanIsNotAskedByTheWindowEvenAfterTheTranslatorsOwnRestEnds() =
        runBlocking {
            val api = UsageLimitApi()
            val credentials =
                ChatGptCredentials(
                    "oaiapp_1",
                    "sub-1",
                    null,
                    "id",
                    "access",
                    "refresh",
                    Long.MAX_VALUE / 2,
                    setOf(CHATGPT_PLAN_SCOPE),
                    null,
                )
            val store = ChatGptCredentialStore(MemoryStorage(), PlainCipher).also { it.save(credentials) }
            val model = ChatGptTranslationModel("gpt-x", credentials.cacheScope("gpt-x"), ChatGptSession(store, api), api, ChatGptGate())
            var clock = 1_000_000L
            val translator = AiSubtitleTranslator(null, now = { clock })
            val texts = texts(0..19)
            coroutineScope {
                val window = AiWindowTranslation(this, translator, model, "en" to "vi", clock = { clock }) { emptyList() }
                window.ensure(texts)
                window.await(texts.first())
                assertEquals(1, api.streams.get())
                repeat(6) {
                    clock += 40_000
                    window.ensure(texts)
                    window.await(texts.first())
                }
            }
            assertEquals("the usage limit reached the network once", 1, api.streams.get())
        }

    @Test
    fun aBackgroundStepDuringTheWindowsFillReadTakesTheLockFirstAtTheCostOfOneRequest() =
        runBlocking {
            val needed = texts(0..19)
            val fill = texts(20..79)
            // Window first: one request carries the playing lines and 40 ahead; the 20 left over do not fill a background request.
            val windowFirst = GateModel()
            val quiet = AiSubtitleTranslator(null)
            quiet.translateAll(windowFirst, "en", "vi", needed) { fill }
            assertEquals(emptyList<String>(), quiet.translateNextBatch(windowFirst, "en", "vi", fill))
            assertEquals(listOf(60), windowFirst.requests.map { it.size })

            // Background step while the window is still reading its fill: it takes the lock first, the window then asks for its own lines.
            val interleaved = GateModel()
            val translator = AiSubtitleTranslator(null)
            val reading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coroutineScope {
                val window =
                    launch {
                        translator.translateAll(interleaved, "en", "vi", needed) {
                            reading.complete(Unit)
                            release.await()
                            fill
                        }
                    }
                reading.await()
                assertEquals(fill, translator.translateNextBatch(interleaved, "en", "vi", fill))
                release.complete(Unit)
                window.join()
            }
            println("AUDIT contention windowFirst=${windowFirst.requests.size} interleaved=${interleaved.requests.size}")
            assertEquals(listOf(60, 20), interleaved.requests.map { it.size })
            assertEquals(
                "no sentence is sent twice",
                80,
                interleaved.requests
                    .flatten()
                    .toSet()
                    .size,
            )
        }

    private fun answer(
        user: String,
        prefix: String,
    ): String {
        val sentences = JSONObject(user).getJSONArray("sentences")
        val answers = JSONArray()
        for (index in 0 until sentences.length()) {
            val sentence = sentences.getJSONObject(index)
            val sentenceRows = sentence.getJSONArray("rows")
            answers.put(
                JSONObject()
                    .put("id", sentence.getInt("id"))
                    .put("rows", JSONArray(List(sentenceRows.length()) { "$prefix${sentenceRows.getString(it)}" })),
            )
        }
        return JSONObject().put("sentences", answers).toString()
    }
}
