package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.TranslationOrigin
import com.kienhoang.dualsubreplay.translation.aiSentences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** How long the window waits before it looks again at what the AI still owes it (see [AiWindowTranslation.ensure]). */
internal const val AI_RETRY_MS = 10_000L
private const val LOOK_GAP_MS = 1_000L
private const val BLOCKED_LOOK_MS = 60_000L

/**
 * The AI side of one video's online translation (Settings → Translation → Translate subtitles with
 * an AI choice): one request at a time from the playing window, which [ahead] fills with the
 * minutes after it. Rows wait only while the running request may still bring their sentence.
 */
internal class AiWindowTranslation(
    private val scope: CoroutineScope,
    private val translator: AiSubtitleTranslator,
    private val model: AiTranslationModel,
    languages: Pair<String, String>,
    /** How long [ensure] leaves the AI alone between two looks at what is still owed. */
    private val retryMs: Long = AI_RETRY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ahead: suspend () -> List<String>,
) {
    private val source = languages.first
    private val target = languages.second
    private var job: Job? = null
    private var lookedAt = Long.MIN_VALUE / 2
    private var owed = false
    private var askedAt = Long.MIN_VALUE / 2
    private var askedFor = 0

    /**
     * Level-triggered counterpart of [ask]: called when the window is otherwise settled, it asks again for what the AI still owes
     * the window, so a failed or skipped request, a seek during a running one or a paused video cannot leave Google's lines for good.
     * Returns how long to wait before looking again, or null when nothing is owed. A request in flight is never doubled, the
     * translator's failure rest is waited out, and looks are throttled.
     */
    suspend fun ensure(texts: List<String>): Long? {
        val now = clock()
        if (now - lookedAt < minOf(LOOK_GAP_MS, retryMs)) return retryMs.takeIf { owed }
        lookedAt = now
        owed = translator.owes(model, source, target, texts)
        if (!owed) return null
        if (job?.isActive == true) return retryMs
        // A refusal that lasts (not eligible, signed out, usage limit) is not asked again every few seconds.
        if (model.isBlocked()) return BLOCKED_LOOK_MS
        val rest = translator.restMs()
        if (rest > 0) return minOf(rest, retryMs)
        // New sentences are asked at once; the same ones again only after the retry delay, whatever ended the last request.
        val fresh = texts.hashCode() != askedFor
        if ((fresh && now - askedAt >= minOf(LOOK_GAP_MS, retryMs)) || now - askedAt >= retryMs) {
            askedFor = texts.hashCode()
            ask(texts)
        }
        return retryMs
    }

    suspend fun cached(text: String): String? = translator.cachedTranslation(model, source, target, text)

    /** Who wrote [text]'s translation held now (call right after [cached] or [await] returned it); null if none. */
    fun originOf(text: String): TranslationOrigin? = translator.originOf(model, source, target, text)

    /** Sends [texts] unless a request is already running; the next row step sends what is still missing. */
    fun ask(texts: List<String>) {
        if (job?.isActive != true) {
            askedAt = clock()
            job = scope.launch { translateQuietly(texts) }
        }
    }

    /** [text]'s translation, waiting while the running request may still bring it; null when it ends without it. */
    suspend fun await(text: String): String? {
        job?.let { running -> awaitOrEnd(running) { translator.shownTranslation(model, source, target, text) != null } }
        return cached(text)
    }

    /** AI only: waits for the window's first sentence, asking again once if the running request was for another place. */
    suspend fun first(texts: List<String>) {
        val first = aiSentences(texts).firstOrNull()?.text ?: return
        repeat(2) {
            if (cached(first) != null) return
            ask(texts)
            await(first)
        }
    }

    /** Sends the window's sentences; a slow, refused or unreadable answer leaves the rows to Google. */
    private suspend fun translateQuietly(texts: List<String>) {
        try {
            translator.translateAll(model, source, target, texts, ahead)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Google translates what the AI did not.
        }
    }

    /** Waits until [arrived] (checked whenever the AI translator has news) or until [running] ends. */
    private suspend fun awaitOrEnd(
        running: Job,
        arrived: () -> Boolean,
    ) = coroutineScope {
        val watch = launch { translator.updates.first { arrived() } }
        select {
            running.onJoin {}
            watch.onJoin {}
        }
        watch.cancel()
    }
}
