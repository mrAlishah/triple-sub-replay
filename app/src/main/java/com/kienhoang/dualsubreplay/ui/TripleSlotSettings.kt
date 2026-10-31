package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Fork-owned (see docs/development/fork-customizations.md, FC-01): saves and publishes the per-slot subtitle
 * settings of Triple subtitles. [AppViewModel] keeps its public setters and only delegates here, so upstream
 * changes to the ViewModel do not touch this logic.
 */
internal class TripleSlotSettings(
    private val preferences: SharedPreferences,
    private val state: MutableStateFlow<DualSubUiState>,
) {
    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(TRIPLE_SUBTITLES_PREFERENCE, enabled).apply()
        state.update { it.copy(tripleSubtitlesEnabled = enabled) }
    }

    fun setThirdLanguage(language: String) {
        val normalized = storedThirdLanguage(language)
        preferences.edit().putString(THIRD_LANGUAGE_PREFERENCE, normalized).apply()
        state.update { it.copy(thirdLanguage = normalized) }
    }

    fun setRtl(
        slot: SubtitleSlot,
        rtl: Boolean,
    ) {
        preferences.edit().putBoolean(slot.rtlPreference, rtl).apply()
        state.update { it.copy(slotRtl = it.slotRtl.with(slot, rtl)) }
    }

    /** Moves a slot one place up or down in the display order; its identity and settings stay put. */
    fun move(
        slot: SubtitleSlot,
        delta: Int,
    ) {
        val order = state.value.subtitleOrder.moved(slot, delta)
        preferences.edit().putString(SUBTITLE_ORDER_PREFERENCE, order.serializedOrder()).apply()
        state.update { it.copy(subtitleOrder = order) }
    }

    fun setAlignment(
        slot: SubtitleSlot,
        alignment: SubtitleAlignment,
    ) {
        preferences.edit().putString(slot.alignmentPreference, alignment.storageValue).apply()
        state.update { it.copy(slotAlignment = it.slotAlignment.with(slot, alignment)) }
    }

    fun setFontScale(
        slot: SubtitleSlot,
        scale: Float,
    ) {
        val safe = normalizeSlotScale(scale)
        preferences.edit().putFloat(slot.scalePreference, safe).apply()
        state.update { it.copy(slotFontScale = it.slotFontScale.with(slot, safe)) }
    }
}
