package com.kienhoang.dualsubreplay.chatgpt

import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.SecretStorage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class ModelStorage : SecretStorage {
    val map = mutableMapOf<String, String>()

    override fun get(name: String) = map[name]

    override fun put(values: Map<String, String>) {
        map.putAll(values)
    }

    override fun remove(names: List<String>) {
        names.forEach(map::remove)
    }
}

private object Open : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain

    override fun decrypt(sealed: ByteArray) = sealed
}

private class StreamApi(
    private val replies: MutableList<() -> String>,
) : ChatGptApi {
    val calls = AtomicInteger()
    val bodies = mutableListOf<String>()
    val tokens = mutableListOf<String>()

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
        calls.incrementAndGet()
        bodies += body
        tokens += accessToken
        return replies.removeAt(0)().also(onText)
    }
}

class ChatGptTranslationModelTest {
    private val now = 1_000_000L
    private val store = ChatGptCredentialStore(ModelStorage(), Open)
    private val credentials =
        ChatGptCredentials("oaiapp_1", "sub-1", null, "id", "access-1", "refresh-1", now + 3_600_000, setOf(CHATGPT_PLAN_SCOPE), null)

    private fun model(
        api: StreamApi,
        gate: ChatGptGate = ChatGptGate { now },
        waits: MutableList<Long> = mutableListOf(),
    ): ChatGptTranslationModel {
        store.save(credentials)
        return ChatGptTranslationModel(
            "gpt-x",
            credentials.cacheScope("gpt-x"),
            ChatGptSession(store, api) {
                now
            },
            api,
            gate,
            retryDelaysMs = listOf(1, 3),
        ) {
            waits +=
                it
        }
    }

    private fun fail(kind: ChatGptErrorKind): () -> String = { throw ChatGptException(kind, "x") }

    @Test
    fun theTranslationInstructionsTravelAsInstructionsWithTheBearerToken() =
        runBlocking {
            val api = StreamApi(mutableListOf({ "Hallo" }))
            val pieces = mutableListOf<String>()
            assertEquals("Hallo", model(api).ask("Translate.", "Ich", pieces::add))
            val body = JSONObject(api.bodies.single())
            assertEquals("Translate.", body.getString("instructions"))
            assertEquals("gpt-x", body.getString("model"))
            assertEquals(listOf("access-1"), api.tokens)
            assertEquals(listOf("Hallo"), pieces)
        }

    @Test
    fun theScopeNamesProviderAndModelButNeverTheAccount() {
        val scope = model(StreamApi(mutableListOf())).scope
        assertTrue(scope.startsWith("chatgpt/gpt-x/"))
        assertFalse(scope.contains("sub-1") || scope.contains("oaiapp_1"))
    }

    @Test
    fun aPassingFailureIsRetriedWithBoundedWaitsThenSucceeds() =
        runBlocking {
            val api = StreamApi(mutableListOf(fail(ChatGptErrorKind.UNAVAILABLE), fail(ChatGptErrorKind.UNAVAILABLE), { "ok" }))
            val waits = mutableListOf<Long>()
            assertEquals("ok", model(api, waits = waits).ask("s", "u"))
            assertEquals(listOf(1L, 3L), waits)
            assertEquals(3, api.calls.get())
        }

    @Test
    fun retriesStopAfterTheBoundAndTheFailureSurfaces() =
        runBlocking {
            val api =
                StreamApi(
                    mutableListOf(
                        fail(
                            ChatGptErrorKind.UNAVAILABLE,
                        ),
                        fail(ChatGptErrorKind.UNAVAILABLE),
                        fail(ChatGptErrorKind.UNAVAILABLE),
                        {
                            "never"
                        },
                    ),
                )
            try {
                model(api).ask("s", "u")
                fail()
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.UNAVAILABLE, error.kind)
            }
            assertEquals(3, api.calls.get())
        }

    @Test
    fun ineligibleBadRequestAndUnsupportedModelAreNeverRetried() =
        runBlocking {
            for (kind in listOf(
                ChatGptErrorKind.NOT_ELIGIBLE,
                ChatGptErrorKind.BAD_REQUEST,
                ChatGptErrorKind.UNSUPPORTED_MODEL,
            )) {
                val api = StreamApi(mutableListOf(fail(kind), { "never" }))
                try {
                    model(api, gate = ChatGptGate { now }).ask("s", "u")
                    fail()
                } catch (error: ChatGptException) {
                    assertEquals(kind, error.kind)
                }
                assertEquals(kind.name, 1, api.calls.get())
            }
        }

    @Test
    fun ineligibleAndUsageLimitStopFurtherRequestsUntilReset() =
        runBlocking {
            val gate = ChatGptGate { now }
            val api = StreamApi(mutableListOf(fail(ChatGptErrorKind.USAGE_LIMIT), { "never" }))
            val translation = model(api, gate)
            repeat(3) {
                try {
                    translation.ask("s", "u")
                    fail()
                } catch (error: ChatGptException) {
                    assertEquals(ChatGptErrorKind.USAGE_LIMIT, error.kind)
                }
            }
            assertEquals("later asks never reached OpenAI", 1, api.calls.get())
            gate.reset()
            assertEquals(null, gate.blocked())
        }

    @Test
    fun aUsageLimitRestsOnlyForAWhile() {
        var clock = now
        val gate = ChatGptGate { clock }
        gate.record(ChatGptException(ChatGptErrorKind.USAGE_LIMIT, "x"))
        assertEquals(ChatGptErrorKind.USAGE_LIMIT, gate.blocked()?.kind)
        clock += 6 * 60_000
        assertEquals(null, gate.blocked())
    }

    @Test
    fun aPassingFailureDoesNotBlockTheGate() {
        val gate = ChatGptGate { now }
        gate.record(ChatGptException(ChatGptErrorKind.UNAVAILABLE, "x"))
        assertEquals(null, gate.blocked())
    }

    @Test
    fun partialTextBlocksARetryBecauseItWouldBeHandedOverTwice() =
        runBlocking {
            val api =
                StreamApi(
                    mutableListOf({ throw ChatGptException(ChatGptErrorKind.UNAVAILABLE, "cut") }, { "second" }),
                )
            val seen = mutableListOf<String>()
            val partial =
                object : ChatGptApi by api {
                    var first = true

                    override suspend fun stream(
                        accessToken: String,
                        body: String,
                        onText: (String) -> Unit,
                    ): String {
                        if (first) {
                            first = false
                            onText("half")
                            throw ChatGptException(ChatGptErrorKind.UNAVAILABLE, "cut")
                        }
                        return api.stream(accessToken, body, onText)
                    }
                }
            store.save(credentials)
            val translation =
                ChatGptTranslationModel(
                    "gpt-x",
                    "chatgpt/gpt-x/abc",
                    ChatGptSession(store, partial) {
                        now
                    },
                    partial,
                    ChatGptGate { now },
                    listOf(1, 3),
                ) { }
            try {
                translation.ask("s", "u", seen::add)
                fail()
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.UNAVAILABLE, error.kind)
            }
            assertEquals(listOf("half"), seen)
            assertEquals(0, api.calls.get())
        }

    @Test
    fun aRejectedTokenIsRenewedOnceAndTheSameRequestRetried() =
        runBlocking {
            val inner = StreamApi(mutableListOf(fail(ChatGptErrorKind.SIGN_IN_REQUIRED), { "ok" }))
            val api =
                object : ChatGptApi by inner {
                    override suspend fun refresh(
                        clientId: String,
                        refreshToken: String,
                    ) = ChatGptTokens("access-2", "refresh-2", null, 3600, CHATGPT_PLAN_SCOPE, null)
                }
            store.save(credentials)
            val translation =
                ChatGptTranslationModel(
                    "gpt-x",
                    "chatgpt/gpt-x/abc",
                    ChatGptSession(store, api) { now },
                    api,
                    ChatGptGate { now },
                    listOf(1, 3),
                ) { }
            assertEquals("ok", translation.ask("s", "u"))
            assertEquals(listOf("access-1", "access-2"), inner.tokens)
        }

    @Test
    fun aSecondRejectionAfterRenewalEndsTheSessionAndBlocks() =
        runBlocking {
            val inner =
                StreamApi(mutableListOf(fail(ChatGptErrorKind.SIGN_IN_REQUIRED), fail(ChatGptErrorKind.SIGN_IN_REQUIRED), { "never" }))
            val api =
                object : ChatGptApi by inner {
                    override suspend fun refresh(
                        clientId: String,
                        refreshToken: String,
                    ) = ChatGptTokens("access-2", "refresh-2", null, 3600, CHATGPT_PLAN_SCOPE, null)
                }
            store.save(credentials)
            val gate = ChatGptGate { now }
            val translation =
                ChatGptTranslationModel("gpt-x", "chatgpt/gpt-x/abc", ChatGptSession(store, api) { now }, api, gate, listOf(1, 3)) { }
            try {
                translation.ask("s", "u")
                fail()
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.SIGN_IN_REQUIRED, error.kind)
            }
            assertEquals(2, inner.calls.get())
            assertEquals(ChatGptErrorKind.SIGN_IN_REQUIRED, gate.blocked()?.kind)
        }

    @Test
    fun aRefusedRequestRestsOnlyAMinuteAndTheSettingsHearAboutBlocks() {
        var clock = now
        val gate = ChatGptGate { clock }
        val heard = mutableListOf<ChatGptErrorKind>()
        gate.onBlocked = { heard += it.kind }
        gate.record(ChatGptException(ChatGptErrorKind.UNAVAILABLE, "x"))
        gate.record(ChatGptException(ChatGptErrorKind.BAD_REQUEST, "x"))
        assertEquals(ChatGptErrorKind.BAD_REQUEST, gate.blocked()?.kind)
        clock += 61_000
        assertEquals(null, gate.blocked())
        gate.record(ChatGptException(ChatGptErrorKind.NOT_ELIGIBLE, "x"))
        assertEquals(listOf(ChatGptErrorKind.BAD_REQUEST, ChatGptErrorKind.NOT_ELIGIBLE), heard)
    }
}
