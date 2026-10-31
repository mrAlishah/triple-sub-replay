package com.kienhoang.dualsubreplay.chatgpt

import kotlinx.coroutines.delay

internal val CHATGPT_RETRY_DELAYS_MS = listOf(1_000L, 3_000L)

/**
 * One request to the signed-in ChatGPT plan, for translation and chat alike: it stops at once while the shared [gate] blocks
 * (ineligible, usage limit, sign-in ended), renews a rejected token once and repeats the request, and retries a passing
 * failure only while nothing has been shown. [call] gets the access token and a function it runs when text starts to arrive.
 */
internal suspend fun <T> chatGptCall(
    session: ChatGptSession,
    gate: ChatGptGate,
    retryDelaysMs: List<Long>,
    wait: suspend (Long) -> Unit,
    /** Whether a failure is remembered in the shared [gate]; a chat request the service refused is not, so it cannot silence translation. */
    remember: (ChatGptException) -> Boolean = { true },
    call: suspend (access: String, started: () -> Unit) -> T,
): T {
    gate.blocked()?.let { throw it }
    var started = false
    var renewed = false
    val delays = retryDelaysMs.iterator()
    while (true) {
        var used: String? = null
        try {
            val access = session.accessToken().also { used = it }
            return call(access) { started = true }
        } catch (error: ChatGptException) {
            val rejected = used
            // A 401 can mean the token was revoked early: renew it once, then try the same request again.
            if (error.kind == ChatGptErrorKind.SIGN_IN_REQUIRED && rejected != null && !renewed && !started) {
                renewed = true
                try {
                    session.renew(rejected)
                    continue
                } catch (renewal: ChatGptException) {
                    gate.record(renewal)
                    throw renewal
                }
            }
            // Text already handed to the screen must not arrive twice; only a passing failure is worth another try.
            if (error.kind == ChatGptErrorKind.UNAVAILABLE && !started && delays.hasNext()) {
                wait(delays.next())
                continue
            }
            if (remember(error)) gate.record(error)
            throw error
        }
    }
}

/**
 * The assistant chat's way to the ChatGPT plan. It owns nothing: the session (one refresh lock), the credential store and the
 * gate are the ones the subtitle translation uses, so a limit or a sign-out stops both and tokens are never refreshed twice.
 */
internal class ChatGptChatBackend(
    private val session: ChatGptSession,
    private val api: ChatGptApi,
    private val gate: ChatGptGate,
    private val retryDelaysMs: List<Long> = CHATGPT_RETRY_DELAYS_MS,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    /** Sends [body] (a Responses request) and streams its text to [onText]; the result exists only for a completed reply. */
    suspend fun respond(
        body: String,
        onText: (String) -> Unit,
    ): ChatGptResult = respondTracked(body, onText) { }

    /** Like [respond], and runs [onSent] once the request is about to leave the phone: the token is in hand and the permission was checked. */
    suspend fun respondTracked(
        body: String,
        onText: (String) -> Unit,
        onSent: () -> Unit,
    ): ChatGptResult =
        // A refused request (for example one that carried tools) is told to the caller, not remembered for everyone: the chat
        // asks again without tools, and subtitle translation must not stop for a minute because of it.
        chatGptCall(session, gate, retryDelaysMs, wait, remember = { it.kind != ChatGptErrorKind.BAD_REQUEST }) { access, started ->
            // The session gave a token, so the plan-usage permission was checked; only now does the request leave the phone.
            onSent()
            api.respond(access, body) {
                started()
                onText(it)
            }
        }
}
