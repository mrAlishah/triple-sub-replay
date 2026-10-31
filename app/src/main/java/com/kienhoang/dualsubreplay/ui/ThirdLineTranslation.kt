package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.GoogleWebTranslator
import com.kienhoang.dualsubreplay.translation.Translated
import com.kienhoang.dualsubreplay.translation.TranslationDiagnostics
import com.kienhoang.dualsubreplay.translation.TranslationEventKind
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import com.kienhoang.dualsubreplay.translation.TranslationOrigin
import com.kienhoang.dualsubreplay.translation.TranslationProvider
import com.kienhoang.dualsubreplay.translation.awaitWithin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Fork-owned (docs/development/fork-customizations.md, FC-01 and FC-05): the Subtitle 2 (third) line's translation
 * loop. [AppViewModel] keeps the load's lifecycle and calls [start] once per translation run; nothing here
 * reaches into the ViewModel, only the narrow inputs below.
 *
 * @param isCurrentLoad whether [videoId] and [generation] still name the load on screen (stale results are dropped).
 * @param aiFillTexts the sentences of minutes 2 to 5 ahead, which fill the playing window's AI request.
 * @param translateOnDevice one text translated on the device without moving the video off Google.
 * @param googleWaitMs how long a row waits for Google on the current network.
 */
internal class ThirdLineTranslation(
    private val state: MutableStateFlow<DualSubUiState>,
    private val isCurrentLoad: (DualSubUiState, String, Long) -> Boolean,
    private val aiTranslator: AiSubtitleTranslator,
    private val diagnostics: TranslationDiagnostics,
    private val googleTranslator: GoogleWebTranslator,
    private val playbackRequests: StateFlow<CaptionPlaybackRequest>,
    private val aiFillTexts: suspend (SubtitleStore) -> List<String>,
    private val translateOnDevice: suspend (videoId: String, generation: Long, source: String, target: String, text: String) -> String,
    private val googleWaitMs: () -> Long,
) {
    /**
     * One more window loop on the same display [store], so its row ids match [DualSubUiState.segments]. Null when the
     * mode is off or would repeat another line. It runs in [scope] (the load's Google scope) and is cancelled when this
     * translation run ends. With an [ai] model (ChatGPT Subscription) the line is also asked from the AI, straight
     * from the Original.
     */
    fun start(
        store: SubtitleStore,
        videoId: String,
        generation: Long,
        source: String,
        scope: CoroutineScope,
        ai: AiTranslationModel?,
        manualAi: AiTranslationModel?,
    ): Job? {
        publish(videoId, generation, emptyMap(), null)
        val third = state.value.thirdLanguageFor(source) ?: return null
        // The AI translates this line straight from the Original, never from Subtitle 1; Google and the device keep what it leaves out.
        val window =
            ai?.let { model ->
                AiWindowTranslation(scope, aiTranslator, model, source to third) { aiFillTexts(store) }
            }
        val aiOnly = window != null && state.value.aiTranslationOnly

        suspend fun translate(text: String): Translated {
            window?.cached(text)?.let { return Translated(it, window.originOf(text)) }
            if (aiOnly) window.await(text)?.let { return Translated(it, window.originOf(text)) }
            return translateFallback(videoId, generation, source, third, text, scope).also { diagnostics.noteAiFallback(third, aiOnly, it) }
        }

        suspend fun prefetch(texts: List<String>) {
            if (aiOnly) window.first(texts) else window?.ask(texts)
        }
        val upgrades =
            (ai ?: manualAi)?.let { model ->
                aiTranslationUpgrades(aiTranslator, diagnostics, model, source, third, window.takeUnless { aiOnly })
            }
        return scope.launch {
            runThirdLanguageTracked(
                store,
                playbackRequests,
                ::translate,
                if (window != null) ::prefetch else null,
                upgrades,
            ) { lines, origins -> publish(videoId, generation, lines, third, origins) }
        }
    }

    /** Google first (an error or a blank line goes on-device), or on-device only when Google is off for this video. */
    private suspend fun translateFallback(
        videoId: String,
        generation: Long,
        source: String,
        third: String,
        text: String,
        googleRequests: CoroutineScope,
    ): Translated {
        if (state.value.translatesWithGoogle()) {
            val viaGoogle =
                try {
                    awaitWithin(googleRequests, googleWaitMs()) { googleTranslator.translate(source, third, text) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            if (viaGoogle != null) return Translated(viaGoogle, TranslationOrigin.GOOGLE)
        }
        return Translated(translateOnDevice(videoId, generation, source, third, text), TranslationOrigin.ON_DEVICE)
    }

    private fun publish(
        videoId: String,
        generation: Long,
        lines: Map<Long, String>,
        language: String?,
        origins: Map<Long, TranslationOrigin> = emptyMap(),
    ) {
        state.update { current ->
            if (isCurrentLoad(current, videoId, generation)) {
                current.copy(thirdLines = ThirdLines(videoId, lines, language, origins))
            } else {
                current
            }
        }
    }
}

/**
 * AI only took Google, the device or YouTube for a row: the AI failed or left it out, and the diagnostics keep that.
 * With Google first this is the normal order while the AI loads, so it is not recorded; AI failures are.
 */
internal fun TranslationDiagnostics.noteAiFallback(
    target: String,
    aiOnly: Boolean,
    result: Translated,
) {
    val origin = result.origin ?: return
    if (aiOnly && origin.provider != TranslationProvider.CHATGPT && origin.provider != TranslationProvider.AI_PROVIDER) {
        record(TranslationEventKind.FALLBACK, TranslationLanguages.normalize(target), origin)
    }
}
