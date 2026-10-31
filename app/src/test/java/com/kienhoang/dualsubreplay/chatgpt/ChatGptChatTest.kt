package com.kienhoang.dualsubreplay.chatgpt

import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.SecretStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class ChatStorage : SecretStorage {
    val map = mutableMapOf<String, String>()

    override fun get(name: String) = map[name]

    override fun put(values: Map<String, String>) {
        map.putAll(values)
    }

    override fun remove(names: List<String>) {
        names.forEach(map::remove)
    }
}

private object ChatCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain.reversedArray()

    override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
}

/** Plays scripted `respond` answers and counts the refreshes the session asks for. */
private class ChatFakeApi : ChatGptApi {
    val refreshCalls = AtomicInteger()
    val tokensSeen = mutableListOf<String>()
    var answers = ArrayDeque<suspend (String, (String) -> Unit) -> ChatGptResult>()

    override suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ) = error("unused")

    override suspend fun refresh(
        clientId: String,
        refreshToken: String,
    ): ChatGptTokens {
        refreshCalls.incrementAndGet()
        delay(20)
        return ChatGptTokens(
            "access-${refreshCalls.get() + 1}",
            "refresh-${refreshCalls.get() + 1}",
            null,
            3600,
            "openid $CHATGPT_PLAN_SCOPE",
            null,
        )
    }

    override suspend fun revoke(
        clientId: String,
        token: String,
    ) = Unit

    override suspend fun jwks() = error("unused")

    override suspend fun models(accessToken: String) = error("unused")

    override suspend fun stream(
        accessToken: String,
        body: String,
        onText: (String) -> Unit,
    ) = respond(accessToken, body, onText).text

    override suspend fun respond(
        accessToken: String,
        body: String,
        onText: (String) -> Unit,
    ): ChatGptResult {
        tokensSeen += accessToken
        return (answers.removeFirstOrNull() ?: error("no answer planned"))(body, onText)
    }
}

private fun lines(vararg events: String): () -> String? {
    val queue = ArrayDeque(events.map { "data: $it" })
    return { queue.removeFirstOrNull() }
}

class ChatGptChatTest {
    private val store = ChatGptCredentialStore(ChatStorage(), ChatCipher)
    private val api = ChatFakeApi()
    private var now = 1_000_000L
    private val session = ChatGptSession(store, api) { now }
    private val gate = ChatGptGate { now }

    private fun signIn(expiresAt: Long = now + 3_600_000) {
        store.save(
            ChatGptCredentials(
                "oaiapp_1",
                "sub-1",
                "me@example.com",
                "id-1",
                "access-1",
                "refresh-1",
                expiresAt,
                setOf("openid", CHATGPT_PLAN_SCOPE),
                null,
            ),
        )
    }

    private fun backend(delays: List<Long> = listOf(1L, 1L)) = ChatGptChatBackend(session, api, gate, delays) { }

    // --- stream parsing -------------------------------------------------------------------------------------------

    @Test
    fun textArrivesInOrderAndIsReturnedOnlyAfterCompletion() {
        val seen = mutableListOf<String>()
        val result =
            readChatGptResponse(
                lines(
                    """{"type":"response.created"}""",
                    """{"type":"response.output_text.delta","delta":"Hal"}""",
                    """{"type":"response.reasoning_summary_text.delta","delta":"ignored"}""",
                    """{"type":"response.output_text.delta","delta":"lo"}""",
                    """{"type":"response.completed","response":{"output":[]}}""",
                ),
                seen::add,
            )
        assertEquals(listOf("Hal", "lo"), seen)
        assertEquals("Hallo", result.text)
        assertTrue(result.calls.isEmpty())
    }

    @Test
    fun functionCallsAreCollectedOnceWithTheirCallIdAndNamespace() {
        val call =
            """{"type":"function_call","id":"fc_1","call_id":"call_9","name":"set_speed",""" +
                """"namespace":"triplesub","arguments":"{\"speed\":1.5}"}"""
        val result =
            readChatGptResponse(
                lines(
                    """{"type":"response.output_item.done","item":$call}""",
                    """{"type":"response.completed","response":{"output":[$call]}}""",
                ),
            ) { }
        assertEquals(1, result.calls.size)
        assertEquals(ChatGptFunctionCall("call_9", "set_speed", """{"speed":1.5}""", "triplesub"), result.calls.single())
    }

    @Test
    fun aCallThatOnlyTheCompletedEventCarriesIsStillFound() {
        val call = """{"type":"function_call","call_id":"c1","name":"pause","arguments":"{}"}"""
        val result = readChatGptResponse(lines("""{"type":"response.completed","response":{"output":[$call]}}""")) { }
        assertEquals(listOf("c1"), result.calls.map { it.callId })
        assertEquals(null, result.calls.single().namespace)
    }

    @Test
    fun failedIncompleteAndCutStreamsNeverBecomeResults() {
        fun fails(vararg events: String): ChatGptException {
            try {
                readChatGptResponse(lines(*events)) { }
            } catch (error: ChatGptException) {
                return error
            }
            fail("expected a failure")
            error("unreachable")
        }
        assertEquals(
            ChatGptErrorKind.USAGE_LIMIT,
            fails("""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}""").kind,
        )
        assertEquals(ChatGptErrorKind.BAD_REPLY, fails("""{"type":"response.incomplete"}""").kind)
        assertEquals(
            ChatGptErrorKind.UNAVAILABLE,
            fails("""{"type":"response.output_text.delta","delta":"half"}""").kind,
        )
    }

    // --- shared session, gate, retries ------------------------------------------------------------------------------

    @Test
    fun aValidSessionIsReusedWithoutAnyRefresh() =
        runBlocking {
            signIn()
            api.answers += { _, onText -> onText("Hi").let { ChatGptResult("Hi") } }
            val seen = mutableListOf<String>()
            assertEquals("Hi", backend().respond("{}", seen::add).text)
            assertEquals(listOf("access-1"), api.tokensSeen)
            assertEquals(0, api.refreshCalls.get())
            assertEquals(listOf("Hi"), seen)
        }

    @Test
    fun anExpiredTokenRefreshesAndANewTokenIsUsed() =
        runBlocking {
            signIn(expiresAt = now - 1)
            api.answers += { _, _ -> ChatGptResult("ok") }
            assertEquals("ok", backend().respond("{}") { }.text)
            assertEquals(1, api.refreshCalls.get())
            assertEquals(listOf("access-2"), api.tokensSeen)
        }

    @Test
    fun aRejectedTokenIsRenewedOnceThenTheSameRequestIsRepeated() =
        runBlocking {
            signIn()
            api.answers += { _, _ -> throw ChatGptException(ChatGptErrorKind.SIGN_IN_REQUIRED, "401") }
            api.answers += { _, _ -> ChatGptResult("again") }
            assertEquals("again", backend().respond("{}") { }.text)
            assertEquals(1, api.refreshCalls.get())
            assertEquals(listOf("access-1", "access-2"), api.tokensSeen)
        }

    @Test
    fun aBlockedGateStopsChatBeforeAnyNetworkCall() =
        runBlocking {
            signIn()
            gate.record(ChatGptException(ChatGptErrorKind.NOT_ELIGIBLE, "no"))
            try {
                backend().respond("{}") { }
                fail("expected the gate to block")
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.NOT_ELIGIBLE, error.kind)
            }
            assertTrue(api.tokensSeen.isEmpty())
        }

    @Test
    fun aUsageLimitIsRememberedSoTheNextRequestIsNotSent() =
        runBlocking {
            signIn()
            api.answers += { _, _ -> throw ChatGptException(ChatGptErrorKind.USAGE_LIMIT, "limit") }
            repeat(2) {
                try {
                    backend().respond("{}") { }
                    fail("expected the limit")
                } catch (error: ChatGptException) {
                    assertEquals(ChatGptErrorKind.USAGE_LIMIT, error.kind)
                }
            }
            assertEquals("the second request never left the phone", 1, api.tokensSeen.size)
            // Translation shares the same gate, so it stops too.
            assertNotNull(gate.blocked())
        }

    @Test
    fun aRefusedChatRequestIsNotRememberedSoTheRetryWithoutToolsAndTranslationGoOut() =
        runBlocking {
            signIn()
            api.answers += { _, _ -> throw ChatGptException(ChatGptErrorKind.BAD_REQUEST, "400") }
            api.answers += { _, _ -> ChatGptResult("plain answer") }
            try {
                backend().respond("{}") { }
                fail("the refusal reaches the caller")
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.BAD_REQUEST, error.kind)
            }
            assertEquals("the shared gate stays open for translation", null, gate.blocked())
            assertEquals("plain answer", backend().respond("{}") { }.text)
            assertEquals(2, api.tokensSeen.size)
        }

    @Test
    fun aPassingFailureBeforeAnyTextIsRetriedButNotAfterTextStarted() =
        runBlocking {
            signIn()
            api.answers += { _, _ -> throw ChatGptException(ChatGptErrorKind.UNAVAILABLE, "503") }
            api.answers += { _, onText -> onText("A").let { ChatGptResult("A") } }
            assertEquals("A", backend().respond("{}") { }.text)
            assertEquals(2, api.tokensSeen.size)

            api.tokensSeen.clear()
            api.answers += { _, onText ->
                onText("half")
                throw ChatGptException(ChatGptErrorKind.UNAVAILABLE, "cut")
            }
            try {
                backend().respond("{}") { }
                fail("a cut stream is an error")
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.UNAVAILABLE, error.kind)
            }
            assertEquals("text already shown is never sent twice", 1, api.tokensSeen.size)
        }

    @Test
    fun stoppingCancelsTheCallAndRecordsNoFailure() =
        runBlocking {
            signIn()
            val started = CompletableDeferred<Unit>()
            api.answers += { _, _ ->
                started.complete(Unit)
                delay(60_000)
                ChatGptResult("never")
            }
            val job = launch { backend().respond("{}") { } }
            started.await()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(null, gate.blocked())
        }

    @Test
    fun disconnectingTheAccountStopsChatBeforeAnyRequestIsSent() =
        runBlocking {
            signIn()
            session.disconnect()
            try {
                backend().respond("{}") { }
                fail("a disconnected account cannot be used")
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.SIGN_IN_REQUIRED, error.kind)
            }
            assertTrue("no request left the phone", api.tokensSeen.isEmpty())
        }

    @Test
    fun anAccountWithoutPlanUsageIsNeverAskedForInference() =
        runBlocking {
            store.save(
                ChatGptCredentials(
                    "oaiapp_1",
                    "sub-1",
                    "me@example.com",
                    "id-1",
                    "access-1",
                    "refresh-1",
                    now + 3_600_000,
                    setOf("openid"),
                    null,
                ),
            )
            try {
                backend().respond("{}") { }
                fail("plan usage was not granted")
            } catch (error: ChatGptException) {
                assertTrue(error.kind in setOf(ChatGptErrorKind.PLAN_USAGE_NOT_GRANTED, ChatGptErrorKind.SIGN_IN_REQUIRED))
            }
            assertTrue(api.tokensSeen.isEmpty())
        }

    @Test
    fun chatAndTranslationRenewingTogetherRefreshOnce() =
        runBlocking {
            signIn(expiresAt = now - 1)
            repeat(2) { api.answers += { _, _ -> ChatGptResult("ok") } }
            val results = (1..2).map { async { backend().respond("{}") { }.text } }.awaitAll()
            assertEquals(listOf("ok", "ok"), results)
            assertEquals("one refresh token, one rotation", 1, api.refreshCalls.get())
            assertFalse(api.tokensSeen.contains("access-1"))
        }
}
