package com.kienhoang.dualsubreplay.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private const val SIGN_IN_TIMEOUT_MS = 3 * 60_000L

/**
 * One browser sign-in: listener first, then the system browser, then the exchange and the verified identity.
 * [openBrowser] is given the authorization URL (it contains the PKCE challenge, never a secret).
 */
internal class ChatGptSignIn(
    private val store: ChatGptCredentialStore,
    private val api: ChatGptApi,
    private val agentName: String,
    /** Saves the new account; the app passes the session's, which holds the refresh lock. */
    private val save: suspend (ChatGptCredentials) -> Unit = { credentials ->
        withContext(Dispatchers.IO) { store.save(credentials) }
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun signIn(openBrowser: (String) -> Unit): ChatGptCredentials {
        val existing = withContext(Dispatchers.IO) { store.load() }
        val hostId = withContext(Dispatchers.IO) { store.hostId() }
        val registering = existing == null
        val listenerOrNull =
            try {
                ChatGptLoopback()
            } catch (error: IOException) {
                throw ChatGptException(ChatGptErrorKind.SIGN_IN_FAILED, "The sign-in could not listen on this phone.", error)
            }
        listenerOrNull.use { listener ->
            val attempt = newChatGptAttempt(listener.port)
            val url =
                chatGptAuthorizeUrl(
                    existing?.clientId ?: CHATGPT_REGISTRATION_CLIENT,
                    hostId,
                    agentName.takeIf { registering },
                    attempt,
                    forceConsent =
                        existing?.grantsPlanUsage == false,
                )
            openBrowser(url)
            val code =
                when (val callback = listener.await(attempt.state, SIGN_IN_TIMEOUT_MS)) {
                    is ChatGptCallback.Code -> callback
                    ChatGptCallback.Denied -> throw ChatGptException(ChatGptErrorKind.SIGN_IN_FAILED, "ChatGPT sign-in was cancelled.")
                    is ChatGptCallback.Invalid -> throw ChatGptException(ChatGptErrorKind.SIGN_IN_FAILED, callback.reason)
                }
            // A returning sign-in may omit the client id; a different one is never accepted.
            val clientId =
                when {
                    existing == null ->
                        code.issuedClientId
                            ?: throw ChatGptException(ChatGptErrorKind.SIGN_IN_FAILED, "ChatGPT registration was incomplete.")
                    code.issuedClientId != null && code.issuedClientId != existing.clientId -> throw ChatGptException(
                        ChatGptErrorKind.SIGN_IN_FAILED,
                        "ChatGPT answered for another app.",
                    )
                    else -> existing.clientId
                }
            if (clientId ==
                CHATGPT_REGISTRATION_CLIENT
            ) {
                throw ChatGptException(ChatGptErrorKind.SIGN_IN_FAILED, "ChatGPT registration was incomplete.")
            }
            val tokens = api.exchange(clientId, code.code, attempt.verifier, attempt.redirectUri)
            val identity = validateChatGptIdToken(tokens.idToken.orEmpty(), api.jwks(), clientId, attempt.nonce, clock() / 1000)
            // Another account must never overwrite this one's credentials; the user disconnects first.
            if (existing != null && existing.subject != identity.subject) {
                throw ChatGptException(ChatGptErrorKind.SIGN_IN_FAILED, "That is a different ChatGPT account. Disconnect first.")
            }
            val refresh =
                tokens.refreshToken?.takeIf { it.isNotEmpty() }
                    ?: throw ChatGptException(ChatGptErrorKind.SIGN_IN_FAILED, "ChatGPT gave no refresh token.")
            return ChatGptCredentials(
                clientId = clientId,
                subject = identity.subject,
                email = identity.email,
                idToken = tokens.idToken.orEmpty(),
                accessToken = tokens.accessToken,
                refreshToken = refresh,
                expiresAtMs = clock() + tokens.expiresInSeconds * 1000,
                scopes =
                    tokens.scope
                        .split(' ')
                        .filter { it.isNotEmpty() }
                        .toSet(),
                earliestRefreshAt = tokens.earliestRefreshAt,
            ).also { save(it) }
        }
    }
}
