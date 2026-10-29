package com.kienhoang.dualsubreplay.chatgpt

import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import kotlinx.coroutines.delay

private const val USAGE_LIMIT_REST_MS = 5 * 60_000L
private const val BAD_REQUEST_REST_MS = 60_000L
private val RETRY_DELAYS_MS = listOf(1_000L, 3_000L)

/**
 * Remembers a failure that no retry can fix, so the translation stops asking OpenAI: ineligible and failing
 * requests until the user reconnects or taps Refresh, a usage limit for a few minutes. A passing failure never blocks.
 */
internal class ChatGptGate(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failure: ChatGptException? = null
    private var until = Long.MAX_VALUE

    /** Told when a failure starts blocking, so Settings can show it instead of "ready". */
    @Volatile var onBlocked: ((ChatGptException) -> Unit)? = null

    @Synchronized
    fun blocked(): ChatGptException? {
        if (failure != null && clock() >= until) failure = null
        return failure
    }

    @Synchronized
    fun record(error: ChatGptException) {
        when (error.kind) {
            ChatGptErrorKind.USAGE_LIMIT -> {
                failure = error
                until = clock() + USAGE_LIMIT_REST_MS
            }
            // One refused window should not silence every later one, so a refused request only rests a minute.
            ChatGptErrorKind.BAD_REQUEST -> {
                failure = error
                until = clock() + BAD_REQUEST_REST_MS
            }
            ChatGptErrorKind.NOT_ELIGIBLE, ChatGptErrorKind.PLAN_USAGE_NOT_GRANTED, ChatGptErrorKind.SIGN_IN_REQUIRED,
            ChatGptErrorKind.UNSUPPORTED_MODEL,
            -> {
                failure = error
                until = Long.MAX_VALUE
            }
            else -> return
        }
        onBlocked?.invoke(error)
    }

    @Synchronized
    fun reset() {
        failure = null
    }
}

/**
 * Translates with the signed-in ChatGPT plan. The request is the existing AI translation request (system text as
 * `instructions`, the batch as the one user input); the reply streams to [onText] and is returned only once the
 * response completed. [AiSubtitleTranslator][com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator] already sends
 * one request at a time, so Subtitle 1 and Subtitle 2 take turns and need no pacing of their own here.
 */
internal class ChatGptTranslationModel(
    private val model: String,
    override val scope: String,
    private val session: ChatGptSession,
    private val api: ChatGptApi,
    private val gate: ChatGptGate,
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : AiTranslationModel {
    override fun isBlocked(): Boolean = gate.blocked() != null

    override suspend fun ask(
        system: String,
        user: String,
        onText: (String) -> Unit,
    ): String {
        gate.blocked()?.let { throw it }
        var started = false
        var renewed = false
        val delays = retryDelaysMs.iterator()
        while (true) {
            var used: String? = null
            try {
                val access = session.accessToken().also { used = it }
                return api.stream(access, chatGptResponsesBody(model, system, user)) {
                    started = true
                    onText(it)
                }
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
                gate.record(error)
                throw error
            }
        }
    }
}
