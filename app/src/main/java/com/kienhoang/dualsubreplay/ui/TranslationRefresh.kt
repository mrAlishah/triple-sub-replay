package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.DiagnosticReason
import com.kienhoang.dualsubreplay.translation.TranslationDiagnostics
import com.kienhoang.dualsubreplay.translation.TranslationEventKind
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import com.kienhoang.dualsubreplay.translation.aiSentences
import com.kienhoang.dualsubreplay.translation.aiTranslationOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How much a manual re-translation covers. */
internal enum class RefreshScope { SENTENCE, WINDOW, VIDEO }

/** One subtitle line that can be asked of the AI again: its model and the pair it is translated for (always from the Original). */
internal class RefreshTarget(
    val model: AiTranslationModel,
    val source: String,
    val target: String,
)

/**
 * What the current video's translation run offers a manual re-translation: the lines that use the AI, the texts each scope covers,
 * and whether the run is still the current one (a changed video, language, model or provider ends it).
 */
internal class RefreshContext(
    val targets: Map<SubtitleSlot, RefreshTarget>,
    val texts: suspend (RefreshScope) -> List<String>,
    val isCurrent: () -> Boolean,
)

internal data class RefreshState(
    /** Lines being refreshed now; their text on screen stays until the new one arrives. */
    val running: Set<SubtitleSlot> = emptySet(),
    /** Lines whose last refresh failed, with a short reason code. */
    val failed: Map<SubtitleSlot, String> = emptyMap(),
    val done: Int = 0,
    val total: Int = 0,
)

/**
 * Runs the user's "Re-translate with AI": for each chosen line, one after another, a forced request of the chosen scope straight
 * from the Original ([AiSubtitleTranslator.forceTranslate]). Nothing on screen is removed while it runs; a failure leaves the old text
 * and is reported; a result for a run that is no longer current is dropped; repeated taps for a line already refreshing start nothing.
 */
internal class TranslationRefresher(
    private val translator: AiSubtitleTranslator,
    private val diagnostics: TranslationDiagnostics,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(RefreshState())
    val state: StateFlow<RefreshState> = mutable.asStateFlow()
    private val jobs = ArrayList<Job>()

    /** Raised by [cancel] and [reset]: a cancelled run's cleanup must not touch the state of a run started after it. */
    private var epoch = 0

    /** Starts the refresh; false when there is nothing to start (no line uses the AI, or all chosen lines are already refreshing). */
    fun start(
        context: RefreshContext,
        slots: Set<SubtitleSlot>,
        refreshScope: RefreshScope,
    ): Boolean {
        val fresh = SubtitleSlot.entries.filter { it in slots && it in context.targets && it !in mutable.value.running }
        if (fresh.isEmpty()) return false
        mutable.update { it.copy(running = it.running + fresh, failed = it.failed - fresh.toSet(), done = 0, total = 0) }
        val startedAt = epoch
        val previous = jobs.lastOrNull()
        jobs.removeAll { !it.isActive }
        jobs +=
            scope.launch {
                previous?.join()
                run(startedAt, context, fresh, refreshScope)
            }
        return true
    }

    /** The video, language, model or provider changed: stops everything and forgets earlier failures. */
    fun reset() {
        epoch++
        // Cancelled jobs stay listed until they have finished, so a new start waits for their cleanup.
        jobs.forEach { it.cancel() }
        mutable.value = RefreshState()
    }

    /** Stops what is running; the subtitles on screen stay as they are. */
    fun cancel() {
        epoch++
        jobs.forEach { it.cancel() }
        mutable.update { RefreshState(failed = it.failed) }
    }

    private suspend fun run(
        startedAt: Int,
        context: RefreshContext,
        slots: List<SubtitleSlot>,
        refreshScope: RefreshScope,
    ) {
        // A run cancelled meanwhile leaves the state to whatever was started after it.
        fun change(update: (RefreshState) -> RefreshState) {
            if (epoch == startedAt) mutable.update(update)
        }
        try {
            val texts =
                try {
                    context.texts(refreshScope)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // The run ended or its track could not be read: nothing was asked, nothing changes.
                    slots.forEach { slot -> if (context.isCurrent()) change { it.copy(failed = it.failed + (slot to "texts")) } }
                    return
                }
            val perSlot = aiSentences(texts).size
            change { it.copy(total = perSlot * slots.size) }
            slots.forEachIndexed { index, slot ->
                val target = context.targets.getValue(slot)
                val language = TranslationLanguages.normalize(target.target)
                val origin = aiTranslationOrigin(target.model.scope, cached = false)
                diagnostics.record(TranslationEventKind.REFRESH_REQUESTED, language, origin, perSlot)
                try {
                    val replaced =
                        translator.forceTranslate(target.model, target.source, target.target, texts) { done, _ ->
                            if (context.isCurrent()) change { it.copy(done = index * perSlot + done) }
                        }
                    diagnostics.record(TranslationEventKind.REFRESH_SUCCEEDED, language, origin, replaced)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val reason = (error as? DiagnosticReason)?.reason ?: error.javaClass.simpleName.ifEmpty { "error" }
                    diagnostics.record(TranslationEventKind.REFRESH_FAILED, language, origin, 0, reason)
                    // A failure for a run that is gone is not worth showing.
                    if (context.isCurrent()) change { it.copy(failed = it.failed + (slot to reason)) }
                } finally {
                    change { it.copy(running = it.running - slot) }
                }
            }
        } finally {
            change { it.copy(running = it.running - slots.toSet()) }
        }
    }
}
