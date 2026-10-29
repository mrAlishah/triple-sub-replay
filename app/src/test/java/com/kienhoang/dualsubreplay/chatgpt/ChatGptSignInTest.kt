package com.kienhoang.dualsubreplay.chatgpt

import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.SecretStorage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.concurrent.thread

private class SignInStorage : SecretStorage {
    val map = mutableMapOf<String, String>()

    override fun get(name: String) = map[name]

    override fun put(values: Map<String, String>) {
        map.putAll(values)
    }

    override fun remove(names: List<String>) {
        names.forEach(map::remove)
    }
}

private object Plain : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain

    override fun decrypt(sealed: ByteArray) = sealed
}

class ChatGptSignInTest {
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val now = 1_800_000_000_000L
    private val store = ChatGptCredentialStore(SignInStorage(), Plain)

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unsigned(n: BigInteger) = b64(n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())

    private fun idToken(
        nonce: String,
        clientId: String,
        subject: String,
    ): String {
        val header =
            b64(
                JSONObject()
                    .put("alg", "RS256")
                    .put("kid", "k1")
                    .toString()
                    .toByteArray(),
            )
        val body =
            b64(
                JSONObject()
                    .put("iss", CHATGPT_ISSUER)
                    .put("aud", clientId)
                    .put("exp", now / 1000 + 600)
                    .put("iat", now / 1000)
                    .put("nonce", nonce)
                    .put("sub", subject)
                    .put("email", "me@example.com")
                    .toString()
                    .toByteArray(),
            )
        val signature =
            Signature
                .getInstance("SHA256withRSA")
                .apply {
                    initSign(keys.private)
                    update("$header.$body".toByteArray())
                }.sign()
        return "$header.$body.${b64(signature)}"
    }

    private inner class SignInApi(
        private val subject: String = "sub-1",
        private val scope: String = CHATGPT_SCOPES,
    ) : ChatGptApi {
        var exchangedClient: String? = null
        var exchangedRedirect: String? = null
        var nonce = ""

        override suspend fun exchange(
            clientId: String,
            code: String,
            verifier: String,
            redirectUri: String,
        ): ChatGptTokens {
            exchangedClient = clientId
            exchangedRedirect = redirectUri
            assertEquals("good-code", code)
            return ChatGptTokens("access-1", "refresh-1", idToken(nonce, clientId, subject), 3600, scope, "later")
        }

        override suspend fun refresh(
            clientId: String,
            refreshToken: String,
        ) = error("unused")

        override suspend fun revoke(
            clientId: String,
            token: String,
        ) = Unit

        override suspend fun jwks(): String {
            val key = keys.public as RSAPublicKey
            val jwk =
                JSONObject()
                    .put("kty", "RSA")
                    .put("kid", "k1")
                    .put("n", unsigned(key.modulus))
                    .put("e", unsigned(key.publicExponent))
            return JSONObject().put("keys", JSONArray().put(jwk)).toString()
        }

        override suspend fun models(accessToken: String) = error("unused")

        override suspend fun stream(
            accessToken: String,
            body: String,
            onText: (String) -> Unit,
        ) = error("unused")
    }

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun get(url: String): Int =
        (URI(url).toURL().openConnection() as HttpURLConnection).run {
            connectTimeout = 3000
            readTimeout = 3000
            try {
                responseCode
            } finally {
                disconnect()
            }
        }

    /** Plays the browser: answers the authorization URL by calling the redirect URI, after any [first] stray requests. */
    private fun browser(
        api: SignInApi,
        callback: (state: String) -> String,
        first: (redirect: String) -> Unit = {},
        seen: MutableList<Map<String, String>> = mutableListOf(),
    ): (String) -> Unit =
        { url ->
            val parameters = query(url)
            seen += parameters
            api.nonce = parameters.getValue("nonce")
            thread {
                val redirect = parameters.getValue("redirect_uri")
                first(redirect)
                get(redirect + callback(parameters.getValue("state")))
            }
        }

    @Test
    fun aFirstSignInRegistersVerifiesAndStoresTheAccount() =
        runBlocking {
            val api = SignInApi()
            val seen = mutableListOf<Map<String, String>>()
            val credentials =
                ChatGptSignIn(store, api, "TripleSub Replay") {
                    now
                }.signIn(browser(api, { "?code=good-code&state=$it&client_id=oaiapp_9" }, seen = seen))
            assertEquals("dynamic_agent_client", seen.single().getValue("client_id"))
            assertEquals("TripleSub Replay", seen.single().getValue("agent_name_hint"))
            assertEquals("oaiapp_9", api.exchangedClient)
            assertTrue(api.exchangedRedirect!!.startsWith("http://127.0.0.1:"))
            assertEquals("sub-1", credentials.subject)
            assertTrue(credentials.grantsPlanUsage)
            assertEquals(credentials, store.load())
            // The listener is gone once the sign-in ends.
            val port = URI(api.exchangedRedirect!!).port
            try {
                Socket("127.0.0.1", port).close()
                fail("the listener is still open")
            } catch (_: ConnectException) {
            }
        }

    @Test
    fun strayAndWrongStateRequestsAreRejectedAndTheRealCallbackStillWorks() =
        runBlocking {
            val api = SignInApi()
            val statuses = mutableListOf<Int>()
            val credentials =
                ChatGptSignIn(store, api, "TripleSub Replay") { now }.signIn(
                    browser(
                        api,
                        { "?code=good-code&state=$it&client_id=oaiapp_9" },
                        first = { redirect ->
                            statuses += get(redirect + "?code=evil&state=wrong")
                            statuses += get(redirect.replace("/auth/callback", "/favicon.ico"))
                        },
                    ),
                )
            assertEquals(listOf(400, 400), statuses)
            assertEquals("oaiapp_9", credentials.clientId)
        }

    @Test
    fun deniedAndMissingRegistrationEndTheAttemptWithoutExchangingACode() {
        runBlocking {
            val denied = SignInApi()
            try {
                ChatGptSignIn(store, denied, "TripleSub Replay") { now }.signIn(browser(denied, { "?error=access_denied&state=$it" }))
                fail()
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.SIGN_IN_FAILED, error.kind)
            }
            assertNull(denied.exchangedClient)

            val noClient = SignInApi()
            try {
                ChatGptSignIn(store, noClient, "TripleSub Replay") { now }.signIn(browser(noClient, { "?code=good-code&state=$it" }))
                fail()
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.SIGN_IN_FAILED, error.kind)
            }
            assertNull(noClient.exchangedClient)
            assertNull(store.load())
        }
    }

    @Test
    fun aReturningSignInReusesTheIssuedClientAndNeverReplacesAnotherAccount() =
        runBlocking {
            val first = SignInApi()
            val original =
                ChatGptSignIn(store, first, "TripleSub Replay") {
                    now
                }.signIn(browser(first, { "?code=good-code&state=$it&client_id=oaiapp_9" }))

            val again = SignInApi()
            val seen = mutableListOf<Map<String, String>>()
            ChatGptSignIn(store, again, "TripleSub Replay") { now }.signIn(browser(again, { "?code=good-code&state=$it" }, seen = seen))
            assertEquals("oaiapp_9", seen.single().getValue("client_id"))
            assertFalse(seen.single().containsKey("agent_name_hint"))

            val other = SignInApi(subject = "someone-else")
            try {
                ChatGptSignIn(store, other, "TripleSub Replay") { now }.signIn(browser(other, { "?code=good-code&state=$it" }))
                fail()
            } catch (error: ChatGptException) {
                assertEquals(ChatGptErrorKind.SIGN_IN_FAILED, error.kind)
            }
            assertEquals(original.subject, store.load()?.subject)
        }

    @Test
    fun signInWithoutPlanUsageStaysConnectedButNotAuthorized() =
        runBlocking {
            val api = SignInApi(scope = "openid profile email offline_access")
            val credentials =
                ChatGptSignIn(store, api, "TripleSub Replay") {
                    now
                }.signIn(browser(api, { "?code=good-code&state=$it&client_id=oaiapp_9" }))
            assertFalse(credentials.grantsPlanUsage)
            assertTrue(credentials.signedIn)
        }
}
