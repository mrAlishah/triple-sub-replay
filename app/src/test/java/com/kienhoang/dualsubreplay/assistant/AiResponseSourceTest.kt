package com.kienhoang.dualsubreplay.assistant

import com.kienhoang.dualsubreplay.chatgpt.CHATGPT_PLAN_SCOPE
import com.kienhoang.dualsubreplay.chatgpt.ChatGptApi
import com.kienhoang.dualsubreplay.chatgpt.ChatGptChatBackend
import com.kienhoang.dualsubreplay.chatgpt.ChatGptCredentialStore
import com.kienhoang.dualsubreplay.chatgpt.ChatGptCredentials
import com.kienhoang.dualsubreplay.chatgpt.ChatGptErrorKind
import com.kienhoang.dualsubreplay.chatgpt.ChatGptException
import com.kienhoang.dualsubreplay.chatgpt.ChatGptGate
import com.kienhoang.dualsubreplay.chatgpt.ChatGptModel
import com.kienhoang.dualsubreplay.chatgpt.ChatGptResult
import com.kienhoang.dualsubreplay.chatgpt.ChatGptSession
import com.kienhoang.dualsubreplay.chatgpt.ChatGptTokens
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class ProvStorage : SecretStorage {
    val map = mutableMapOf<String, String>()

    override fun get(name: String) = map[name]

    override fun put(values: Map<String, String>) {
        map.putAll(values)
    }

    override fun remove(names: List<String>) {
        names.forEach(map::remove)
    }
}

private object ProvCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain.reversedArray()

    override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
}

private class ProvApi : ChatGptApi {
    var answer: suspend () -> ChatGptResult = { ChatGptResult("ok") }
    var calls = 0

    override suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ) = error("unused")

    override suspend fun refresh(
        clientId: String,
        refreshToken: String,
    ): ChatGptTokens = error("unused")

    override suspend fun revoke(
        clientId: String,
        token: String,
    ) = Unit

    override suspend fun jwks() = error("unused")

    override suspend fun models(accessToken: String): List<ChatGptModel> = error("unused")

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
        calls++
        return answer()
    }
}

private fun chatGptRequest(model: String = "gpt-x") =
    AiChatRequest(
        "",
        model,
        "",
        listOf(AiWireMessage(AiRole.USER, "Hi")),
        format = AiWireFormat.CHATGPT_RESPONSES,
        providerId = CHATGPT_ACCESS_ID,
    )

private fun keyedRequest(
    provider: AiProvider,
    key: String = "AIza-SECRET-KEY-0000",
) = AiChatRequest(
    provider.baseUrl,
    "m-1",
    key,
    listOf(AiWireMessage(AiRole.USER, "Hi")),
    format = provider.format,
    providerId = provider.key,
)

class AiResponseSourceTest {
    private val store = ChatGptCredentialStore(ProvStorage(), ProvCipher)
    private val api = ProvApi()
    private val session = ChatGptSession(store, api) { 1_000_000L }
    private val gate = ChatGptGate { 1_000_000L }

    private fun signIn(scopes: Set<String> = setOf("openid", CHATGPT_PLAN_SCOPE)) {
        store.save(ChatGptCredentials("oaiapp_1", "sub-1", "me@example.com", "id-1", "access-SECRET", "refresh-SECRET", 1_000_000L + 3_600_000, scopes, null))
    }

    private fun chatGptTransport() = ChatGptChatTransport(ChatGptChatBackend(session, api, gate, listOf(1L)) { })

    // --- the ChatGPT transport states what it did ----------------------------------------------------------------------

    @Test
    fun aChatGptAnswerIsStampedOauthResponsesWithTheModelSentAndTheOneTheServerNamed() =
        runBlocking {
            signIn()
            api.answer = { ChatGptResult("Hallo", responseModel = "gpt-x-2026-10-01") }
            val reply = chatGptTransport().complete(chatGptRequest("gpt-x"))
            assertEquals(AiExecution(CHATGPT_ACCESS_ID, AiAuthMethod.OAUTH, AiApiFamily.CHATGPT_RESPONSES, "gpt-x", planUsageVerified = true), reply.execution)
            assertEquals("the server's model, not the requested one", "gpt-x-2026-10-01", reply.model)
        }

    @Test
    fun aServerThatNamesNoModelLeavesTheReportedModelEmptyInsteadOfInventingOne() =
        runBlocking {
            signIn()
            api.answer = { ChatGptResult("Hallo") }
            assertEquals("", chatGptTransport().complete(chatGptRequest("gpt-x")).model)
        }

    @Test
    fun aFailureAfterTheRequestWasSentStillSaysChatGptWasTheOneThatFailed() {
        signIn()
        api.answer = { throw ChatGptException(ChatGptErrorKind.USAGE_LIMIT, "limit") }
        val error = runCatching { runBlocking { chatGptTransport().complete(chatGptRequest()) } }.exceptionOrNull() as AiChatException
        assertEquals(AiErrorKind.PLAN_USAGE_LIMIT, error.kind)
        assertEquals(CHATGPT_ACCESS_ID, error.execution?.providerId)
        assertEquals(AiAuthMethod.OAUTH, error.execution?.auth)
        assertEquals(1, api.calls)
    }

    @Test
    fun aFailureBeforeAnythingWasSentCarriesNoExecutionSoNoSuccessIsImplied() {
        // Signed out: the session refuses, no request leaves the phone.
        val signedOut = runCatching { runBlocking { chatGptTransport().complete(chatGptRequest()) } }.exceptionOrNull() as AiChatException
        assertNull(signedOut.execution)
        // Signed in without the plan-usage permission: never asked for inference either.
        signIn(scopes = setOf("openid"))
        val noPlan = runCatching { runBlocking { chatGptTransport().complete(chatGptRequest()) } }.exceptionOrNull() as AiChatException
        assertNull(noPlan.execution)
        assertEquals("nothing was sent to OpenAI", 0, api.calls)
    }

    // --- the router stamps keyed requests, and cannot mix the two ---------------------------------------------------------

    @Test
    fun aKeyedAnswerIsStampedWithItsProviderAuthAndApiFamilyAndNeverTheKey() =
        runBlocking {
            val router = AiTransportRouter(AiChatTransport { AiReply("hi", model = "gemini-flash-2") })
            val gemini = router.complete(keyedRequest(AiProvider.GEMINI))
            assertEquals(AiExecution(AiProvider.GEMINI.key, AiAuthMethod.API_KEY, AiApiFamily.OPENAI_CHAT, "m-1"), gemini.execution)
            assertEquals("gemini-flash-2", gemini.model)
            val claude = router.complete(keyedRequest(AiProvider.CLAUDE))
            assertEquals(AiApiFamily.ANTHROPIC_MESSAGES, claude.execution?.api)
            assertFalse("AIza-SECRET-KEY-0000" in gemini.execution.toString())
        }

    @Test
    fun aKeyedFailureIsStampedToo() {
        val router = AiTransportRouter(AiChatTransport { throw AiChatException(AiErrorKind.NETWORK) })
        val error = runCatching { runBlocking { router.complete(keyedRequest(AiProvider.OPENROUTER)) } }.exceptionOrNull() as AiChatException
        assertEquals(AiProvider.OPENROUTER.key, error.execution?.providerId)
        assertEquals(AiAuthMethod.API_KEY, error.execution?.auth)
    }

    @Test
    fun theRouterKeepsWhatTheChatGptTransportSaidAboutItself() =
        runBlocking {
            val own = AiExecution(CHATGPT_ACCESS_ID, AiAuthMethod.OAUTH, AiApiFamily.CHATGPT_RESPONSES, "gpt-x", true)
            val router = AiTransportRouter(AiChatTransport { error("a keyed transport must not see a ChatGPT request") })
            router.chatGpt = AiChatTransport { AiReply("hi", execution = own) }
            assertEquals(own, router.complete(chatGptRequest()).execution)
        }

    // --- the stored record ---------------------------------------------------------------------------------------------

    private val chatGptSource =
        AiResponseSource(
            providerId = CHATGPT_ACCESS_ID,
            auth = AiAuthMethod.OAUTH,
            api = AiApiFamily.CHATGPT_RESPONSES,
            requestedModel = "gpt-x",
            reportedModel = "gpt-x-2026-10-01",
            status = AiResponseStatus.COMPLETED,
            selectedProviderId = CHATGPT_ACCESS_ID,
            requests = 2,
            planUsageVerified = true,
        )

    @Test
    fun aSourceSurvivesAHistoryReloadOnItsMessage() {
        val message = AiChatMessage("m1", AiRole.ASSISTANT, "Hallo", 5L, source = chatGptSource)
        val reloaded = decodeAiChats(encodeAiChats(listOf(AiChat("c1", 1L, 2L, listOf(message))))).single().messages.single()
        assertEquals(chatGptSource, reloaded.source)
        AiResponseStatus.entries.forEach { status ->
            val source = chatGptSource.copy(status = status)
            val back = decodeAiChats(encodeAiChats(listOf(AiChat("c", 1L, 2L, listOf(message.copy(source = source)))))).single().messages.single().source
            assertEquals(status, back?.status)
        }
    }

    @Test
    fun aMessageSavedBeforeThisFeatureHasNoSourceAndStaysReadable() {
        val old = """{"version":1,"chats":[{"id":"c","created":1,"updated":2,"messages":[{"id":"m","role":"assistant","text":"Old","time":3}]}]}"""
        val message = decodeAiChats(old).single().messages.single()
        assertEquals("Old", message.text)
        assertNull(message.source)
    }

    @Test
    fun theStoredRecordHasOnlyTheWhitelistedFieldsSoNoSecretCanBeInIt() {
        val json = encodeAiResponseSource(chatGptSource)
        assertEquals(
            setOf("provider", "auth", "api", "requested", "reported", "status", "selected", "requests", "plan"),
            json.keys().asSequence().toSet(),
        )
        val text = encodeAiChats(listOf(AiChat("c", 1L, 2L, listOf(AiChatMessage("m", AiRole.ASSISTANT, "x", 1L, source = chatGptSource)))))
        listOf("access-SECRET", "refresh-SECRET", "AIza-SECRET-KEY-0000", "Bearer", "Authorization").forEach { assertFalse(it in text) }
    }

    @Test
    fun anUnreadableRecordIsDroppedNotGuessed() {
        assertNull(decodeAiResponseSource(JSONObject("""{"provider":"gemini","status":"nonsense"}""")))
        assertNull(decodeAiResponseSource(JSONObject("""{"status":"completed"}""")))
        assertNotNull(decodeAiResponseSource(encodeAiResponseSource(chatGptSource)))
    }

    // --- what the record says ------------------------------------------------------------------------------------------

    @Test
    fun aFallbackIsAProviderThatDiffersFromTheSelectedOneAndNothingElse() {
        assertFalse(chatGptSource.fallback)
        assertTrue(chatGptSource.copy(providerId = AiProvider.GEMINI.key).fallback)
        assertFalse("no provider answered, so nothing fell back", chatGptSource.copy(providerId = "", auth = null, api = null).fallback)
    }

    @Test
    fun onlyACompletedRealRequestCountsAsSuccessful() {
        assertTrue(chatGptSource.successful)
        AiResponseStatus.entries.filter { it != AiResponseStatus.COMPLETED }.forEach { assertFalse(chatGptSource.copy(status = it).successful) }
        assertFalse("a completed status without a request is not a success", chatGptSource.copy(auth = null, api = null).successful)
        assertFalse(chatGptSource.copy(auth = null, api = null).network)
    }
}
