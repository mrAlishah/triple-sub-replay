package com.kienhoang.dualsubreplay.chatgpt

import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.SecretStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Preferences file for the sealed ChatGPT session. Backups leave it out, like the AI keys. */
internal const val CHATGPT_PREFERENCES = "chatgpt_subscription"

private const val RECORD = "credentials"
private const val HOST_ID = "host_id"
private const val REFRESH_MARGIN_MS = 60_000L

/** The token endpoint's reply. [earliestRefreshAt] is kept as received; its meaning is not published (spec). */
internal data class ChatGptTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSeconds: Long,
    val scope: String,
    val earliestRefreshAt: String?,
)

/** Every network call the sign-in and the translation need, so tests can stand in for OpenAI. */
internal interface ChatGptApi {
    suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): ChatGptTokens

    /** Throws [ChatGptErrorKind.SIGN_IN_REQUIRED] when the refresh token is unusable, [ChatGptErrorKind.UNAVAILABLE] on a passing failure. */
    suspend fun refresh(
        clientId: String,
        refreshToken: String,
    ): ChatGptTokens

    suspend fun revoke(
        clientId: String,
        token: String,
    )

    suspend fun jwks(): String

    suspend fun models(accessToken: String): List<ChatGptModel>

    /** One Responses request; returns the text only after `response.completed`. */
    suspend fun stream(
        accessToken: String,
        body: String,
        onText: (String) -> Unit,
    ): String
}

/**
 * One signed-in account. An empty [refreshToken] means the tokens were found unusable: the issued client and
 * identity stay so that signing in again reuses the registration.
 */
internal data class ChatGptCredentials(
    val clientId: String,
    val subject: String,
    val email: String?,
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val scopes: Set<String>,
    val earliestRefreshAt: String?,
) {
    val grantsPlanUsage: Boolean get() = CHATGPT_PLAN_SCOPE in scopes
    val signedIn: Boolean get() = refreshToken.isNotEmpty()

    /** Non-reversible, so cache files never carry the account; another account or client gets another scope. */
    fun cacheScope(model: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$subject:$clientId".toByteArray())
        return "chatgpt/$model/" + digest.take(6).joinToString("") { "%02x".format(it) }
    }
}

/** Seals the whole account in one write, so a crash never leaves half of a rotated token pair. */
internal class ChatGptCredentialStore(
    private val storage: SecretStorage,
    private val cipher: SecretCipher,
) {
    fun load(): ChatGptCredentials? {
        val sealed = storage.get(RECORD) ?: return null
        return try {
            val json = JSONObject(String(cipher.decrypt(Base64.getDecoder().decode(sealed))))
            ChatGptCredentials(
                clientId = json.getString("client_id"),
                subject = json.getString("subject"),
                email = json.optString("email").takeIf { it.isNotEmpty() },
                idToken = json.optString("id_token"),
                accessToken = json.optString("access_token"),
                refreshToken = json.optString("refresh_token"),
                expiresAtMs = json.optLong("expires_at"),
                scopes = json.getJSONArray("scopes").let { array -> (0 until array.length()).map(array::getString).toSet() },
                earliestRefreshAt = json.optString("earliest_refresh_at").takeIf { it.isNotEmpty() },
            )
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: JSONException) {
            null
        } catch (_: RuntimeException) {
            // Includes a Keystore ProviderException: an unreadable record counts as signed out.
            null
        }
    }

    fun save(credentials: ChatGptCredentials) {
        val json =
            JSONObject()
                .put("client_id", credentials.clientId)
                .put("subject", credentials.subject)
                .put("email", credentials.email.orEmpty())
                .put("id_token", credentials.idToken)
                .put("access_token", credentials.accessToken)
                .put("refresh_token", credentials.refreshToken)
                .put("expires_at", credentials.expiresAtMs)
                .put("scopes", JSONArray(credentials.scopes.toList()))
                .put("earliest_refresh_at", credentials.earliestRefreshAt.orEmpty())
        try {
            storage.put(mapOf(RECORD to Base64.getEncoder().encodeToString(cipher.encrypt(json.toString().toByteArray()))))
        } catch (error: GeneralSecurityException) {
            throw ChatGptException(ChatGptErrorKind.UNAVAILABLE, "ChatGPT credentials could not be stored.", error)
        } catch (error: RuntimeException) {
            throw ChatGptException(ChatGptErrorKind.UNAVAILABLE, "ChatGPT credentials could not be stored.", error)
        }
    }

    /** Deletes the account; the opaque host id stays, as the preview asks for one stable id per installation. */
    fun clear() = storage.remove(listOf(RECORD))

    fun hostId(): String = storage.get(HOST_ID) ?: "urn:uuid:${UUID.randomUUID()}".also { storage.put(mapOf(HOST_ID to it)) }
}

/** Hands out a usable access token, refreshing it near expiry with one request however many callers wait. */
internal class ChatGptSession(
    private val store: ChatGptCredentialStore,
    private val api: ChatGptApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    suspend fun accessToken(): String =
        lock.withLock {
            // Read inside the lock: another caller may have refreshed while this one waited.
            val current = withContext(Dispatchers.IO) { store.load() }?.takeIf { it.signedIn } ?: throw signInRequired()
            if (!current.grantsPlanUsage) {
                throw ChatGptException(
                    ChatGptErrorKind.PLAN_USAGE_NOT_GRANTED,
                    "ChatGPT plan usage was not authorized.",
                )
            }
            if (current.expiresAtMs - clock() > REFRESH_MARGIN_MS) return current.accessToken
            refresh(current).accessToken
        }

    /**
     * The request and the save run to the end even if the caller is cancelled: OpenAI may already have rotated the
     * refresh token, and losing the reply would sign the user out.
     */
    private suspend fun refresh(current: ChatGptCredentials): ChatGptCredentials =
        withContext(NonCancellable + Dispatchers.IO) {
            val tokens =
                try {
                    api.refresh(current.clientId, current.refreshToken)
                } catch (error: ChatGptException) {
                    // Only a confirmed dead refresh token ends the session; a passing failure keeps it.
                    if (error.kind == ChatGptErrorKind.SIGN_IN_REQUIRED) {
                        store.save(current.copy(accessToken = "", refreshToken = "", expiresAtMs = 0))
                    }
                    throw error
                }
            val granted =
                tokens.scope
                    .split(' ')
                    .filter { it.isNotEmpty() }
                    .toSet()
            val next =
                current.copy(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken ?: current.refreshToken,
                    idToken = tokens.idToken ?: current.idToken,
                    expiresAtMs = clock() + tokens.expiresInSeconds * 1000,
                    // A refresh reply may leave the scope out (RFC 6749 section 6): the grant is then unchanged.
                    scopes = granted.ifEmpty { current.scopes },
                    earliestRefreshAt = tokens.earliestRefreshAt,
                )
            store.save(next)
            next
        }

    /** After a 401 for [failedToken]: refresh once, unless another caller already replaced it. */
    suspend fun renew(failedToken: String) {
        lock.withLock {
            val current = withContext(Dispatchers.IO) { store.load() }?.takeIf { it.signedIn } ?: throw signInRequired()
            if (current.accessToken == failedToken) refresh(current)
        }
    }

    /** Saves a new sign-in under the same lock as the refresh, so a stale refresh cannot overwrite it. */
    suspend fun replace(credentials: ChatGptCredentials) {
        lock.withLock { withContext(NonCancellable + Dispatchers.IO) { store.save(credentials) } }
    }

    /** Revokes the refresh token when the network allows, then deletes every local credential regardless. */
    suspend fun disconnect() {
        lock.withLock {
            val current = withContext(Dispatchers.IO) { store.load() }
            try {
                if (current != null && current.signedIn) api.revoke(current.clientId, current.refreshToken)
            } catch (_: IOException) {
                // Offline: the local copy is still deleted below, and the server forgets it when it expires.
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { store.clear() }
            }
        }
    }

    private fun signInRequired() = ChatGptException(ChatGptErrorKind.SIGN_IN_REQUIRED, "ChatGPT sign-in is required.")
}
