package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Who produced a translation that is on screen. [UNKNOWN] is for text whose producer was not recorded. */
enum class TranslationProvider { CHATGPT, AI_PROVIDER, GOOGLE, ON_DEVICE, YOUTUBE, UNKNOWN }

/**
 * The producer of one displayed translation, set by the code that produced or read it (never from the settings).
 * [cached] is true for an AI result read from the disk cache; [model] names the AI model when there is one.
 */
data class TranslationOrigin(
    val provider: TranslationProvider,
    val cached: Boolean = false,
    val model: String? = null,
) {
    companion object {
        val GOOGLE = TranslationOrigin(TranslationProvider.GOOGLE)
        val ON_DEVICE = TranslationOrigin(TranslationProvider.ON_DEVICE)
        val YOUTUBE = TranslationOrigin(TranslationProvider.YOUTUBE)
        val UNKNOWN = TranslationOrigin(TranslationProvider.UNKNOWN)
    }
}

/** A translation step's result inside the app: the text and where it came from (null when not recorded). */
internal class Translated(
    val text: String,
    val origin: TranslationOrigin?,
)

private const val CHATGPT_SCOPE_PREFIX = "chatgpt/"

/**
 * The origin of an AI translation, read from the cache scope of the model that wrote it
 * (`chatgpt/<model>/<account hash>` for ChatGPT Subscription, `<service>/<model>` for a keyed service), so the
 * account hash is never shown.
 */
internal fun aiTranslationOrigin(
    scope: String,
    cached: Boolean,
): TranslationOrigin =
    if (scope.startsWith(CHATGPT_SCOPE_PREFIX)) {
        TranslationOrigin(TranslationProvider.CHATGPT, cached, scope.removePrefix(CHATGPT_SCOPE_PREFIX).substringBeforeLast('/'))
    } else {
        TranslationOrigin(TranslationProvider.AI_PROVIDER, cached, scope)
    }

/** An error that can name its cause in one short code, for the diagnostics (never a server message). */
internal interface DiagnosticReason {
    val reason: String
}

internal enum class TranslationEventKind {
    /** The AI was asked for sentences the window lacked (before the request is sent). */
    AI_SCHEDULED,

    /** A request was sent. */
    AI_REQUEST,

    /** The reply was complete and its sentences were stored. */
    AI_SUCCEEDED,
    AI_FAILED,

    /** Rows on screen took the AI's text. */
    AI_UPGRADED,

    /** The AI's text agrees with what is shown, which keeps its label. */
    AI_IDENTICAL,
    FALLBACK,

    /** The user asked for a fresh translation. */
    REFRESH_REQUESTED,

    /** The fresh request ignores the stored translations and earlier answers. */
    CACHE_BYPASSED,
    REFRESH_SUCCEEDED,
    REFRESH_FAILED,
}

/** One diagnostics line. It never holds subtitle text: [count] is a number of sentences, [reason] a short code. */
internal data class TranslationEvent(
    val atMs: Long,
    val kind: TranslationEventKind,
    /** The language the line was translated into. */
    val target: String,
    val origin: TranslationOrigin?,
    val count: Int = 0,
    val reason: String? = null,
)

private const val MAX_EVENTS = 60
private const val REPEAT_WINDOW = 12

/** Events a long video repeats many times; the same one within the last few is kept once. */
private val COALESCED =
    setOf(
        TranslationEventKind.FALLBACK,
        TranslationEventKind.AI_IDENTICAL,
        TranslationEventKind.AI_UPGRADED,
        TranslationEventKind.AI_SCHEDULED,
    )

/**
 * The last few translation events of this app run, for Settings → Captions & translation → Show translation
 * source. A repeated fallback or identical-result event is kept once, so a long video cannot flood it.
 */
internal class TranslationDiagnostics(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val all = MutableStateFlow<List<TranslationEvent>>(emptyList())
    val events: StateFlow<List<TranslationEvent>> = all.asStateFlow()

    fun record(
        kind: TranslationEventKind,
        target: String,
        origin: TranslationOrigin?,
        count: Int = 0,
        reason: String? = null,
    ) {
        val event = TranslationEvent(now(), kind, target, origin, count, reason)
        all.update { current ->
            val repeats =
                kind in COALESCED &&
                    current.takeLast(REPEAT_WINDOW).any { it.kind == kind && it.target == target && it.origin == origin }
            if (repeats) current else (current + event).takeLast(MAX_EVENTS)
        }
    }

    fun clear() = all.update { emptyList() }
}
