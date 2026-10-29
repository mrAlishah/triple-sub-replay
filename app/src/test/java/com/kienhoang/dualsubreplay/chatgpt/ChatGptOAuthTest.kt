package com.kienhoang.dualsubreplay.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class ChatGptOAuthTest {
    private val attempt =
        ChatGptAttempt(state = "st4te", nonce = "n0nce", verifier = "ver1fier", redirectUri = "http://127.0.0.1:5000/auth/callback")

    @Test
    fun pkceChallengeMatchesTheRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun attemptsAreRandomAndUrlSafe() {
        val first = newChatGptAttempt(5000)
        val second = newChatGptAttempt(5000)
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.verifier, second.verifier)
        assertTrue(first.verifier.length >= 43)
        assertTrue(first.state.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals("http://127.0.0.1:5000/auth/callback", first.redirectUri)
    }

    @Test
    fun firstSignInRegistersAndAReturningOneUsesTheIssuedClient() {
        val first = chatGptAuthorizeUrl(CHATGPT_REGISTRATION_CLIENT, "urn:uuid:host", "TripleSub Replay", attempt)
        assertTrue(first.startsWith("$CHATGPT_AUTHORIZE_URL?"))
        assertTrue(first.contains("client_id=dynamic_agent_client"))
        assertTrue(first.contains("agent_name_hint=TripleSub%20Replay"))
        assertTrue(first.contains("ext_agent_host_id=urn%3Auuid%3Ahost"))
        assertTrue(first.contains("code_challenge_method=S256"))
        assertTrue(first.contains("scope=openid%20profile%20email%20offline_access%20resource.invoke%20chatgpt.tokens.use.direct"))
        assertTrue(first.contains("resource=https%3A%2F%2Fapi.openai.com%2Fv1"))
        assertTrue(first.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A5000%2Fauth%2Fcallback"))
        assertTrue(first.contains("state=st4te") && first.contains("nonce=n0nce"))

        val again = chatGptAuthorizeUrl("oaiapp_issued", "urn:uuid:host", null, attempt)
        assertTrue(again.contains("client_id=oaiapp_issued"))
        assertFalse(again.contains("agent_name_hint"))
        assertFalse(again.contains("prompt="))
        assertTrue(chatGptAuthorizeUrl("oaiapp_issued", "urn:uuid:host", null, attempt, forceConsent = true).contains("prompt=consent"))
    }

    @Test
    fun callbackWithTheRightStateGivesTheCodeAndIssuedClient() {
        val result = parseChatGptCallback("GET /auth/callback?code=abc&state=st4te&client_id=oaiapp_1 HTTP/1.1", "st4te")
        assertEquals(ChatGptCallback.Code("abc", "oaiapp_1"), result)
        assertEquals(ChatGptCallback.Code("abc", null), parseChatGptCallback("GET /auth/callback?code=abc&state=st4te HTTP/1.1", "st4te"))
    }

    @Test
    fun callbackIsRejectedWhenAnythingIsOff() {
        val bad =
            listOf(
                "GET /callback?code=abc&state=st4te HTTP/1.1",
                "GET /auth/callback/?code=abc&state=st4te HTTP/1.1",
                "POST /auth/callback?code=abc&state=st4te HTTP/1.1",
                "GET /auth/callback?code=abc&state=other HTTP/1.1",
                "GET /auth/callback?code=abc HTTP/1.1",
                "GET /auth/callback?state=st4te HTTP/1.1",
                "GET /auth/callback?code=a&code=b&state=st4te HTTP/1.1",
                "GET http://evil.example/auth/callback?code=abc&state=st4te HTTP/1.1",
                "garbage",
            )
        bad.forEach { assertTrue(it, parseChatGptCallback(it, "st4te") is ChatGptCallback.Invalid) }
    }

    @Test
    fun deniedNeedsTheRightStateToo() {
        assertEquals(ChatGptCallback.Denied, parseChatGptCallback("GET /auth/callback?error=access_denied&state=st4te HTTP/1.1", "st4te"))
        assertTrue(parseChatGptCallback("GET /auth/callback?error=access_denied&state=x HTTP/1.1", "st4te") is ChatGptCallback.Invalid)
    }

    @Test
    fun planUsageNeedsTheDirectScopeFromTheTokenResponse() {
        assertTrue(grantsChatGptPlanUsage("openid email offline_access resource.invoke chatgpt.tokens.use.direct"))
        assertFalse(grantsChatGptPlanUsage("openid profile email offline_access"))
        assertFalse(grantsChatGptPlanUsage("chatgpt.tokens.use.directly"))
    }

    // --- ID token ---

    private val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val now = 1_800_000_000L

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun jwks(kid: String = "k1"): String {
        val key = pair.public as RSAPublicKey
        val number = { n: java.math.BigInteger -> b64(n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()) }
        val jwk =
            JSONObject()
                .put(
                    "kty",
                    "RSA",
                ).put("alg", "RS256")
                .put("use", "sig")
                .put("kid", kid)
                .put("n", number(key.modulus))
                .put("e", number(key.publicExponent))
        return JSONObject().put("keys", JSONArray().put(jwk)).toString()
    }

    private fun idToken(
        iss: String = CHATGPT_ISSUER,
        aud: String = "oaiapp_1",
        exp: Long = now + 600,
        nonce: String = "n0nce",
        kid: String = "k1",
        alg: String = "RS256",
        signWith: KeyPair = pair,
    ): String {
        val header =
            b64(
                JSONObject()
                    .put("alg", alg)
                    .put("kid", kid)
                    .toString()
                    .toByteArray(),
            )
        val body =
            b64(
                JSONObject()
                    .put("iss", iss)
                    .put("aud", aud)
                    .put("exp", exp)
                    .put("iat", now - 5)
                    .put("nonce", nonce)
                    .put("sub", "user-1")
                    .put("email", "me@example.com")
                    .toString()
                    .toByteArray(),
            )
        val signature =
            Signature
                .getInstance("SHA256withRSA")
                .apply {
                    initSign(signWith.private)
                    update("$header.$body".toByteArray())
                }.sign()
        return "$header.$body.${b64(signature)}"
    }

    private fun check(token: String): ChatGptIdentity = validateChatGptIdToken(token, jwks(), "oaiapp_1", "n0nce", now)

    @Test
    fun aGoodIdTokenGivesTheVerifiedIdentity() {
        assertEquals(ChatGptIdentity("user-1", "me@example.com"), check(idToken()))
    }

    @Test
    fun audienceMayBeAnArrayHoldingTheClient() {
        val array = idToken().split(".")
        val body = JSONObject(String(Base64.getUrlDecoder().decode(array[1]))).put("aud", JSONArray().put("oaiapp_1"))
        val header = array[0]
        val payload = b64(body.toString().toByteArray())
        val sig =
            Signature
                .getInstance("SHA256withRSA")
                .apply {
                    initSign(pair.private)
                    update("$header.$payload".toByteArray())
                }.sign()
        assertEquals("user-1", check("$header.$payload.${b64(sig)}").subject)
    }

    @Test
    fun everyIdTokenCheckFailsClosed() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val bad =
            mapOf(
                "issuer" to idToken(iss = "https://evil.example"),
                "audience" to idToken(aud = "someone-else"),
                "expired" to idToken(exp = now - 60),
                "nonce" to idToken(nonce = "replayed"),
                "unknown key" to idToken(kid = "k2"),
                "algorithm" to idToken(alg = "none"),
                "signature" to idToken(signWith = other),
                "shape" to "not.a.jwt.at.all",
            )
        bad.forEach { (name, token) ->
            try {
                check(token)
                fail("$name was accepted")
            } catch (error: ChatGptException) {
                assertEquals(name, ChatGptErrorKind.SIGN_IN_FAILED, error.kind)
            }
        }
    }
}
