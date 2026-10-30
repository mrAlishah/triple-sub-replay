package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.aiSentences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val REFRESH_CHUNK_ROWS = 400

/**
 * Fork-owned (docs/development/fork-customizations.md, FC-05): what "Re-translate with AI" may use while one translation run is
 * current. [AppViewModel] installs it when a run starts, ends it when the run ends, and delegates the public refresh calls.
 *
 * @param isCurrentLoad whether a video and generation still name the load on screen.
 * @param refresher the runner that does the refresh.
 */
internal class RefreshSession(
    private val state: MutableStateFlow<DualSubUiState>,
    private val isCurrentLoad: (DualSubUiState, String, Long) -> Boolean,
    private val refresher: TranslationRefresher,
) {
    private var context: RefreshContext? = null

    /** The whole track's texts, read once for the dialog's estimate and reused by the run. */
    private var videoTexts: Pair<SubtitleStore, List<String>>? = null
    private val mutableSlots = MutableStateFlow<Set<SubtitleSlot>>(emptySet())
    val slots: StateFlow<Set<SubtitleSlot>> = mutableSlots.asStateFlow()

    /**
     * What "Re-translate with AI" can use while this run is current: the chosen AI provider's model for both lines, the
     * texts of each scope, and whether the run is still the current one.
     */
    fun install(
        store: SubtitleStore,
        videoId: String,
        generation: Long,
        sourceLanguage: String,
        targetLanguage: String,
        manualAi: AiTranslationModel?,
    ): RefreshContext {
        val third = state.value.thirdLanguageFor(sourceLanguage)
        val installed =
            RefreshContext(
                targets =
                    buildMap {
                        if (manualAi != null) put(SubtitleSlot.SECOND, RefreshTarget(manualAi, sourceLanguage, targetLanguage))
                        if (manualAi != null && third != null) put(SubtitleSlot.THIRD, RefreshTarget(manualAi, sourceLanguage, third))
                    },
                texts = { scope -> texts(store, scope) },
                isCurrent = { isCurrentLoad(state.value, videoId, generation) },
            )
        refresher.reset()
        context = installed
        mutableSlots.value = installed.targets.keys
        return installed
    }

    /** Ends a run's offer, unless a newer run has already replaced it. */
    fun end(installed: RefreshContext?) {
        if (installed == null || context !== installed) return
        refresher.reset()
        context = null
        videoTexts = null
        mutableSlots.value = emptySet()
    }

    /** Starts "Re-translate with AI" for [slots] and [scope]; nothing happens when the current run offers none of them. */
    fun start(
        slots: Set<SubtitleSlot>,
        scope: RefreshScope,
    ) {
        context?.let { refresher.start(it, slots, scope) }
    }

    /** How many sentences [scope] would send for each line, for the warning before an entire-video refresh. */
    suspend fun sentenceCount(scope: RefreshScope): Int = context?.let { aiSentences(it.texts(scope)).size } ?: 0

    /** Re-translates only [segment] for [slots]: the same forced path, with this one row's texts instead of the playing sentence's. */
    fun startRow(
        segment: SubtitleSegment,
        slots: Set<SubtitleSlot>,
    ) {
        val current = context ?: return
        refresher.start(current.forRow(segment), slots, RefreshScope.SENTENCE)
    }

    /** The texts a scope covers, in the form the AI is asked for: the playing sentence, the rows now in the window, or every row. */
    private suspend fun texts(
        store: SubtitleStore,
        scope: RefreshScope,
    ): List<String> =
        when (scope) {
            RefreshScope.SENTENCE ->
                state.value
                    .let { it.segments.getOrNull(it.currentIndex) }
                    ?.let(::rowTranslationTexts)
                    .orEmpty()
            RefreshScope.WINDOW -> translationTexts(state.value.segments)
            RefreshScope.VIDEO ->
                videoTexts?.takeIf { it.first === store }?.second
                    ?: withContext(Dispatchers.IO) {
                        // By row index, so rows that share a start time are never skipped.
                        val all = store.indicesBetween(-1L, Long.MAX_VALUE, Int.MAX_VALUE)
                        val rows = mutableListOf<SubtitleSegment>()
                        for (first in all step REFRESH_CHUNK_ROWS) {
                            currentCoroutineContext().ensureActive()
                            rows += store.read(first..minOf(first + REFRESH_CHUNK_ROWS - 1, all.last))
                        }
                        translationTexts(rows)
                    }.also { videoTexts = store to it }
        }
}

/** The same run and lines, but the texts of this one segment whatever scope is asked: what "Re-translate with AI" on a card sends. */
internal fun RefreshContext.forRow(segment: SubtitleSegment): RefreshContext {
    val texts = rowTranslationTexts(segment)
    return RefreshContext(targets, { texts }, isCurrent)
}
