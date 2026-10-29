package com.kienhoang.dualsubreplay.chatgpt

import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.SecretStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private class MapStorage : SecretStorage {
    val map = mutableMapOf<String, String>()

    override fun get(name: String) = map[name]

    override fun put(values: Map<String, String>) {
        map.putAll(values)
    }

    override fun remove(names: List<String>) {
        names.forEach(map::remove)
    }
}

/** Reverses the bytes: not secure, but proves the stored text is not the plain record. */
private object ReverseCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain.reversedArray()

    override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
}

private class FakeApi(
    var refreshes: (String) -> ChatGptTokens = { throw IOException("no refresh planned") },
) : ChatGptApi {
    val refreshCalls = AtomicInteger()
    val revoked = mutableListOf<String>()

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
        delay(30)
        return refreshes(refreshToken)
    }

    override suspend fun revoke(
        clientId: String,
        token: String,
    ) {
        revoked += token
    }

    override suspend fun jwks() = error("unused")

    override suspend fun models(accessToken: String) = error("unused")

    override suspend fun stream(
        accessToken: String,
        body: String,
        onText: (String) -> Unit,
    ) = error("unused")
}

class ChatGptSessionTest {
    private val storage = MapStorage()
    private val store = ChatGptCredentialStore(storage, ReverseCipher)
    private var now = 1_000_000L

    private fun credentials(
        access: String = "access-1",
        refresh: String = "refresh-1",
        expiresAt: Long = now + 3_600_000,
        scopes: Set<String> = setOf("openid", CHATGPT_PLAN_SCOPE),
    ) = ChatGptCredentials("oaiapp_1", "sub-1", "me@example.com", "id-1", access, refresh, expiresAt, scopes, null)

    private fun session(api: FakeApi) = ChatGptSession(store, api) { now }

    @Test
    fun theRecordIsSealedAndSurvivesARestart() {
        store.save(credentials())
        assertFalse(storage.map.values.any { it.contains("access-1") || it.contains("refresh-1") })
        assertEquals(credentials(), ChatGptCredentialStore(storage, ReverseCipher).load())
    }

    @Test
    fun anUnreadableRecordCountsAsSignedOut() {
        store.save(credentials())
        storage.map.keys
            .filter { it != "host_id" }
            .forEach { storage.map[it] = "corrupt" }
        assertNull(store.load())
    }

    @Test
    fun theHostIdIsStableOpaqueAndKeptAcrossDisconnect() {
        val first = store.hostId()
        assertTrue(first.startsWith("urn:uuid:"))
        store.save(credentials())
        store.clear()
        assertNull(store.load())
        assertEquals(first, store.hostId())
    }

    @Test
    fun aFreshAccessTokenIsUsedWithoutTheNetwork() =
        runBlocking {
            store.save(credentials())
            val api = FakeApi()
            assertEquals("access-1", session(api).accessToken())
            assertEquals(0, api.refreshCalls.get())
        }

    @Test
    fun refreshReplacesTokensExpiryAndScopesTogetherAndKeepsTheIdentity() =
        runBlocking {
            store.save(credentials(expiresAt = now + 10_000))
            val api = FakeApi({ ChatGptTokens("access-2", "refresh-2", null, 3600, "openid chatgpt.tokens.use.direct", "later") })
            assertEquals("access-2", session(api).accessToken())
            val saved = store.load()!!
            assertEquals("refresh-2", saved.refreshToken)
            assertEquals("id-1", saved.idToken)
            assertEquals("sub-1", saved.subject)
            assertEquals(now + 3_600_000, saved.expiresAtMs)
            assertTrue(saved.grantsPlanUsage)
            assertEquals("later", saved.earliestRefreshAt)
        }

    @Test
    fun oneRefreshServesAllConcurrentCallers() =
        runBlocking {
            store.save(credentials(expiresAt = now + 10_000))
            val api = FakeApi({ ChatGptTokens("access-2", "refresh-2", null, 3600, "openid chatgpt.tokens.use.direct", null) })
            val shared = session(api)
            (1..8).map { async { shared.accessToken() } }.awaitAll()
            assertEquals(1, api.refreshCalls.get())
        }

    @Test
    fun aRevokedRefreshTokenSignsOutAndStopsUsingCredentials() =
        runBlocking {
            store.save(credentials(expiresAt = now - 1))
            val api = FakeApi({ throw ChatGptException(ChatGptErrorKind.SIGN_IN_REQUIRED, "invalid_grant") })
            val shared = session(api)
            try {
                shared.accessToken()
                fail()
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.SIGN_IN_REQUIRED, error.kind)
            }
            assertEquals("", store.load()?.refreshToken)
            assertEquals("oaiapp_1", store.load()?.clientId)
            assertEquals(
                ChatGptErrorKind.SIGN_IN_REQUIRED,
                runCatching {
                    shared.accessToken()
                }.exceptionOrNull().let { (it as ChatGptException).kind },
            )
            assertEquals(1, api.refreshCalls.get())
        }

    @Test
    fun aNetworkFailureKeepsTheCredentials() =
        runBlocking {
            store.save(credentials(expiresAt = now - 1))
            val api = FakeApi({ throw ChatGptException(ChatGptErrorKind.UNAVAILABLE, "offline") })
            try {
                session(api).accessToken()
                fail()
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.UNAVAILABLE, error.kind)
            }
            assertEquals("refresh-1", store.load()?.refreshToken)
        }

    @Test
    fun withoutPlanUsageNothingIsRequested() =
        runBlocking {
            store.save(credentials(scopes = setOf("openid")))
            try {
                session(FakeApi()).accessToken()
                fail()
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.PLAN_USAGE_NOT_GRANTED, error.kind)
            }
        }

    @Test
    fun disconnectRevokesBestEffortAndDeletesEverything() =
        runBlocking {
            store.save(credentials())
            val api = FakeApi()
            session(api).disconnect()
            assertNull(store.load())
            assertEquals(listOf("refresh-1"), api.revoked)
        }

    @Test
    fun theCacheScopeNeverHoldsThePlainIdentity() {
        val scope = credentials().cacheScope("gpt-x")
        assertTrue(scope.startsWith("chatgpt/gpt-x/"))
        assertFalse(scope.contains("sub-1") || scope.contains("oaiapp_1") || scope.contains("example.com"))
        assertEquals(scope, credentials(access = "other").cacheScope("gpt-x"))
        assertFalse(scope == credentials().copy(subject = "sub-2").cacheScope("gpt-x"))
    }

    @Test
    fun aRefreshReplyWithoutAScopeKeepsTheGrant() =
        runBlocking {
            store.save(credentials(expiresAt = now + 10_000))
            val api = FakeApi({ ChatGptTokens("access-2", "refresh-2", null, 3600, "", null) })
            assertEquals("access-2", session(api).accessToken())
            assertTrue(store.load()!!.grantsPlanUsage)
        }

    @Test
    fun aCancelledCallerStillKeepsTheRotatedRefreshToken() =
        runBlocking {
            store.save(credentials(expiresAt = now - 1))
            val api = FakeApi({ ChatGptTokens("access-2", "refresh-2", null, 3600, CHATGPT_PLAN_SCOPE, null) })
            val caller = launch { session(api).accessToken() }
            delay(10)
            caller.cancel()
            caller.join()
            assertEquals("refresh-2", store.load()?.refreshToken)
        }

    @Test
    fun renewRefreshesOnlyWhileTheRejectedTokenIsStillCurrent() =
        runBlocking {
            store.save(credentials())
            val api = FakeApi({ ChatGptTokens("access-2", "refresh-2", null, 3600, CHATGPT_PLAN_SCOPE, null) })
            val shared = session(api)
            shared.renew("some-older-token")
            assertEquals(0, api.refreshCalls.get())
            shared.renew("access-1")
            assertEquals(1, api.refreshCalls.get())
            assertEquals("access-2", shared.accessToken())
        }

    @Test
    fun aKeystoreFailureWhileSavingIsAChatGptErrorNotACrash() {
        val broken =
            object : SecretCipher {
                override fun encrypt(plain: ByteArray): ByteArray = throw java.security.GeneralSecurityException("keystore")

                override fun decrypt(sealed: ByteArray) = sealed
            }
        try {
            ChatGptCredentialStore(MapStorage(), broken).save(credentials())
            fail()
        } catch (error: ChatGptException) {
            assertEquals(ChatGptErrorKind.UNAVAILABLE, error.kind)
        }
    }
}
