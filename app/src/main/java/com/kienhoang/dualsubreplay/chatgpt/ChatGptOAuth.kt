package com.kienhoang.dualsubreplay.chatgpt

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

// Official contract: https://developers.openai.com/siwc/token-sharing-open-source (spec 2026-10-29).
internal const val CHATGPT_ISSUER = "https://auth.openai.com"
internal const val CHATGPT_AUTHORIZE_URL = "$CHATGPT_ISSUER/api/accounts/authorize"
internal const val CHATGPT_TOKEN_URL = "$CHATGPT_ISSUER/api/accounts/oauth/token"
internal const val CHATGPT_REVOKE_URL = "$CHATGPT_ISSUER/api/accounts/oauth/revoke"
internal const val CHATGPT_JWKS_URL = "$CHATGPT_ISSUER/.well-known/jwks.json"
internal const val CHATGPT_RESOURCE = "https://api.openai.com/v1"
internal const val CHATGPT_REGISTRATION_CLIENT = "dynamic_agent_client"
internal const val CHATGPT_SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
internal const val CHATGPT_PLAN_SCOPE = "chatgpt.tokens.use.direct"
internal const val CHATGPT_CALLBACK_PATH = "/auth/callback"
private const val CLOCK_TOLERANCE_SECONDS = 5L

internal enum class ChatGptErrorKind {
    SIGN_IN_FAILED,
    SIGN_IN_REQUIRED,
    PLAN_USAGE_NOT_GRANTED,
    NOT_ELIGIBLE,
    USAGE_LIMIT,
    UNSUPPORTED_MODEL,
    BAD_REQUEST,
    BAD_REPLY,
    UNAVAILABLE,
}

/** Extends [IOException] so the AI translator's existing "AI failed, Google takes over" path handles it. */
internal class ChatGptException(
    val kind: ChatGptErrorKind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Everything one sign-in attempt needs back at the callback. Kept in memory only. */
internal data class ChatGptAttempt(
    val state: String,
    val nonce: String,
    val verifier: String,
    val redirectUri: String,
)

internal data class ChatGptIdentity(
    val subject: String,
    val email: String?,
)

internal sealed interface ChatGptCallback {
    data class Code(
        val code: String,
        val issuedClientId: String?,
    ) : ChatGptCallback

    data object Denied : ChatGptCallback

    data class Invalid(
        val reason: String,
    ) : ChatGptCallback
}

private val secureRandom = SecureRandom()

private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun randomToken(): String = base64Url(ByteArray(32).also(secureRandom::nextBytes))

internal fun newChatGptAttempt(port: Int): ChatGptAttempt =
    ChatGptAttempt(randomToken(), randomToken(), randomToken(), "http://127.0.0.1:$port$CHATGPT_CALLBACK_PATH")

internal fun pkceChallenge(verifier: String): String =
    base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** [agentName] only on the first registration; [clientId] is [CHATGPT_REGISTRATION_CLIENT] then, the issued id after. */
internal fun chatGptAuthorizeUrl(
    clientId: String,
    hostId: String,
    agentName: String?,
    attempt: ChatGptAttempt,
    forceConsent: Boolean = false,
): String {
    val parameters =
        buildList {
            add("client_id" to clientId)
            if (agentName != null) add("agent_name_hint" to agentName)
            add("ext_agent_host_id" to hostId)
            add("response_type" to "code")
            add("redirect_uri" to attempt.redirectUri)
            add("scope" to CHATGPT_SCOPES)
            add("resource" to CHATGPT_RESOURCE)
            add("state" to attempt.state)
            add("nonce" to attempt.nonce)
            add("code_challenge_method" to "S256")
            add("code_challenge" to pkceChallenge(attempt.verifier))
            // Asked again after the user declined plan usage; the preview documents prompt=consent for this.
            if (forceConsent) add("prompt" to "consent")
        }
    return CHATGPT_AUTHORIZE_URL + "?" + parameters.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
}

/** Reads the browser's request line (`GET /auth/callback?… HTTP/1.1`). The state is checked before the code is used. */
internal fun parseChatGptCallback(
    requestLine: String,
    expectedState: String,
): ChatGptCallback {
    val parts = requestLine.trim().split(' ')
    if (parts.size != 3 || parts[0] != "GET") return ChatGptCallback.Invalid("Not a GET request.")
    val target = parts[1]
    if (!target.startsWith(CHATGPT_CALLBACK_PATH)) return ChatGptCallback.Invalid("Unexpected path.")
    val rest = target.removePrefix(CHATGPT_CALLBACK_PATH)
    if (rest.isNotEmpty() && !rest.startsWith("?")) return ChatGptCallback.Invalid("Unexpected path.")
    val values = mutableMapOf<String, String>()
    for (pair in rest.removePrefix("?").split('&').filter { it.isNotEmpty() }) {
        val name = pair.substringBefore('=')
        val value = pair.substringAfter('=', "")
        val decoded =
            try {
                URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            } catch (_: IllegalArgumentException) {
                return ChatGptCallback.Invalid("Bad encoding.")
            }
        if (values.put(decoded.first, decoded.second) != null) return ChatGptCallback.Invalid("Repeated parameter.")
    }
    val state = values["state"] ?: return ChatGptCallback.Invalid("No state.")
    if (!MessageDigest.isEqual(state.toByteArray(), expectedState.toByteArray())) return ChatGptCallback.Invalid("State does not match.")
    if (values["error"] == "access_denied") return ChatGptCallback.Denied
    if (values.containsKey("error")) return ChatGptCallback.Invalid("The sign-in failed.")
    val code = values["code"]?.takeIf { it.isNotEmpty() } ?: return ChatGptCallback.Invalid("No code.")
    return ChatGptCallback.Code(code, values["client_id"]?.takeIf { it.isNotEmpty() })
}

/** The token response's scope, not the callback's, decides whether plan usage was granted. */
internal fun grantsChatGptPlanUsage(scope: String): Boolean = scope.split(' ').contains(CHATGPT_PLAN_SCOPE)

private fun failed(reason: String): Nothing = throw ChatGptException(ChatGptErrorKind.SIGN_IN_FAILED, reason)

/** Verifies signature (RS256 against [jwks]), issuer, audience, expiry and nonce; returns the verified identity. */
internal fun validateChatGptIdToken(
    idToken: String,
    jwks: String,
    clientId: String,
    nonce: String,
    nowSeconds: Long,
): ChatGptIdentity {
    val parts = idToken.split('.')
    if (parts.size != 3) failed("The identity token is malformed.")
    try {
        val decoder = Base64.getUrlDecoder()
        val header = JSONObject(String(decoder.decode(parts[0])))
        val claims = JSONObject(String(decoder.decode(parts[1])))
        if (header.optString("alg") != "RS256") failed("The identity token uses an unsupported signature.")
        val key = rsaKey(jwks, header.optString("kid")) ?: failed("The identity token key is unknown.")
        val verified =
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(decoder.decode(parts[2]))
            }
        if (!verified) failed("The identity token signature is invalid.")
        if (claims.optString("iss") != CHATGPT_ISSUER) failed("The identity token has the wrong issuer.")
        if (!audienceHas(claims, clientId)) failed("The identity token is for another app.")
        if (!claims.has("exp") || claims.getLong("exp") + CLOCK_TOLERANCE_SECONDS < nowSeconds) failed("The identity token has expired.")
        if (claims.has("iat") &&
            claims.getLong("iat") - CLOCK_TOLERANCE_SECONDS > nowSeconds
        ) {
            failed("The identity token is from the future.")
        }
        if (claims.optString("nonce") != nonce) failed("The identity token nonce does not match.")
        val subject = claims.optString("sub").takeIf { it.isNotEmpty() } ?: failed("The identity token has no subject.")
        return ChatGptIdentity(subject, claims.optString("email").takeIf { it.isNotEmpty() })
    } catch (error: ChatGptException) {
        throw error
    } catch (error: Exception) {
        // Bad base64, bad JSON or a bad key all mean the same: do not trust the token.
        throw ChatGptException(ChatGptErrorKind.SIGN_IN_FAILED, "The identity token could not be verified.", error)
    }
}

private fun audienceHas(
    claims: JSONObject,
    clientId: String,
): Boolean =
    when (val audience = claims.opt("aud")) {
        is String -> audience == clientId
        is JSONArray -> (0 until audience.length()).any { audience.optString(it) == clientId }
        else -> false
    }

private fun rsaKey(
    jwks: String,
    kid: String,
): java.security.PublicKey? {
    val keys =
        try {
            JSONObject(jwks).getJSONArray("keys")
        } catch (_: JSONException) {
            return null
        }
    val decoder = Base64.getUrlDecoder()
    for (index in 0 until keys.length()) {
        val jwk = keys.getJSONObject(index)
        if (jwk.optString("kty") != "RSA" || jwk.optString("kid") != kid) continue
        val spec = RSAPublicKeySpec(BigInteger(1, decoder.decode(jwk.getString("n"))), BigInteger(1, decoder.decode(jwk.getString("e"))))
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }
    return null
}
