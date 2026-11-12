package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AI_TRANSLATION_PROVIDER_PREFERENCE
import com.kienhoang.dualsubreplay.assistant.CHATGPT_ACCESS_ID
import com.kienhoang.dualsubreplay.assistant.aiAccessOptions
import com.kienhoang.dualsubreplay.chatgpt.AI_ACCESS_CHATGPT
import com.kienhoang.dualsubreplay.chatgpt.AI_ACCESS_METHOD_PREFERENCE
import com.kienhoang.dualsubreplay.translation.AI_TRANSLATION_ONLY_PREFERENCE
import com.kienhoang.dualsubreplay.translation.AI_TRANSLATION_PREFERENCE
import com.kienhoang.dualsubreplay.translation.GOOGLE_WAIT_PREFERENCE
import com.kienhoang.dualsubreplay.translation.SubtitleTranslator
import com.kienhoang.dualsubreplay.translation.TRANSLATION_ENGINE_PREFERENCE
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import kotlinx.coroutines.flow.StateFlow

/** What the Settings page hands to the per-tab scope selector; null (previews, tests) hides the selector. */
internal class TabSettingsActions(
    /** The tab "Current tab" edits right now; a Settings screen opened for another tab closes (see [settingsOpenedForOtherTab]). */
    val openTabId: Long?,
    val speed: TabSpeedAccess,
    val defaultProfile: StateFlow<TabSettingsProfile>,
    val updateDefault: ((TabSettingsProfile) -> TabSettingsProfile) -> Unit,
    val resetCurrentTab: () -> Unit,
    val useCurrentTabAsDefault: () -> Unit,
)

internal val LocalTabSettings = staticCompositionLocalOf<TabSettingsActions?> { null }

internal enum class TabSettingsScope { CURRENT, DEFAULT }

/**
 * A Settings screen edits the tab that was open when it appeared. When the open tab changes under it (the menu, the assistant,
 * a closed tab) it closes instead of rebinding, so a control the person was about to touch can never write to another tab.
 */
internal fun settingsOpenedForOtherTab(
    openedFor: Long?,
    openNow: Long?,
): Boolean = openedFor != openNow

/** Call first in a screen that edits the open tab's settings: it closes when the open tab changes. */
@Composable
internal fun DismissWhenOpenTabChanges(onDismiss: () -> Unit) {
    val openNow = LocalTabSettings.current?.openTabId
    val openedFor = remember { openNow }
    LaunchedEffect(openNow) { if (settingsOpenedForOtherTab(openedFor, openNow)) onDismiss() }
}

/** `Current tab | Default for new tabs`: which profile the Settings page edits. */
@Composable
internal fun TabSettingsScopeBar(
    scope: TabSettingsScope,
    onChange: (TabSettingsScope) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = scope == TabSettingsScope.CURRENT,
            onClick = { onChange(TabSettingsScope.CURRENT) },
            label = { Text(stringResource(R.string.tab_settings_scope_current)) },
            modifier = Modifier.testTag("tab_settings_scope_current"),
        )
        FilterChip(
            selected = scope == TabSettingsScope.DEFAULT,
            onClick = { onChange(TabSettingsScope.DEFAULT) },
            label = { Text(stringResource(R.string.tab_settings_scope_default)) },
            modifier = Modifier.testTag("tab_settings_scope_default"),
        )
    }
}

/** Under the selector while editing the open tab: says what is per tab and offers the reset (asks first). */
@Composable
internal fun CurrentTabSettingsNote(actions: TabSettingsActions) {
    var confirming by remember { mutableStateOf(false) }
    var confirmingDefault by remember { mutableStateOf(false) }
    Text(
        stringResource(R.string.tab_settings_current_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { confirming = true }, modifier = Modifier.testTag("tab_settings_reset_tab")) {
        Text(stringResource(R.string.tab_settings_reset_tab))
    }
    TextButton(onClick = { confirmingDefault = true }, modifier = Modifier.testTag("tab_settings_use_as_default")) {
        Text(stringResource(R.string.tab_settings_use_as_default))
    }
    if (confirmingDefault) {
        AlertDialog(
            onDismissRequest = { confirmingDefault = false },
            title = { Text(stringResource(R.string.tab_settings_use_as_default_title)) },
            text = { Text(stringResource(R.string.tab_settings_use_as_default_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDefault = false
                        actions.useCurrentTabAsDefault()
                    },
                ) { Text(stringResource(R.string.tab_settings_use_as_default_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmingDefault = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.tab_settings_reset_title)) },
            text = { Text(stringResource(R.string.tab_settings_reset_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        actions.resetCurrentTab()
                    },
                ) { Text(stringResource(R.string.tab_settings_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

private typealias DefaultEdit = ((TabSettingsProfile) -> TabSettingsProfile) -> Unit

/**
 * Edits the Default profile that new tabs copy. It reads and writes only the stored Default, never the open tab's
 * preferences, so nothing here reaches the playing video. The sections below are emitted in order into the caller's column.
 */
@Composable
internal fun DefaultTabProfileEditor(actions: TabSettingsActions) {
    val profile by actions.defaultProfile.collectAsState()
    val view = remember(profile) { DualSubUiState().withTabSettings(ProfilePreferences(profile.values)) }
    val edit = actions.updateDefault
    Text(
        stringResource(R.string.tab_settings_default_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    DefaultLanguageRows(view, edit)
    val translator = view.onlineTranslationSettings().translator
    DefaultTranslatorRows(profile, translator, edit)
    DefaultTextRows(view, edit)
    DefaultOrderRows(view, translator, edit)
    DefaultColorRows(view, edit)
    DefaultLearningAndSpeedRows(view, profile, edit)
}

@Composable
private fun DefaultLanguageRows(
    view: DualSubUiState,
    edit: DefaultEdit,
) {
    val autoLabel = stringResource(R.string.settings_source_language_auto)
    val languages = remember { TranslationLanguages.all.map { it.name to it.code } }
    ChoiceRow(
        slotRoleLabel(SubtitleSlot.ORIGINAL, null),
        if (view.sourcePreference == "auto") autoLabel else TranslationLanguages.displayName(view.sourcePreference),
        listOf(autoLabel to "auto") + languages,
        "default_original_language",
    ) { code -> edit { it.with("preferred_caption_language", code) } }
    ChoiceRow(
        slotRoleLabel(SubtitleSlot.SECOND, null),
        TranslationLanguages.displayName(view.targetLanguage),
        languages,
        "default_second_language",
    ) { code -> edit { it.with("target_language", code) } }
    SettingsSwitchRow(
        title = slotRoleLabel(SubtitleSlot.THIRD, null),
        description = TranslationLanguages.displayName(view.thirdLanguage),
        checked = view.tripleSubtitlesEnabled,
        onCheckedChange = { on -> edit { it.with(TRIPLE_SUBTITLES_PREFERENCE, on) } },
        testTag = "default_third_switch",
        enabled = true,
    )
    ChoiceRow(
        slotRoleLabel(SubtitleSlot.THIRD, null),
        TranslationLanguages.displayName(view.thirdLanguage),
        languages,
        "default_third_language",
    ) { code -> edit { it.with(THIRD_LANGUAGE_PREFERENCE, code) } }
}

@Composable
private fun DefaultTranslatorRows(
    profile: TabSettingsProfile,
    translator: SubtitleTranslator,
    edit: DefaultEdit,
) {
    Text(
        stringResource(R.string.settings_translator_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 6.dp),
    )
    SubtitleTranslator.entries.forEach { option ->
        TranslatorOptionRow(option, translator) { choice ->
            edit {
                it
                    .with(TRANSLATION_ENGINE_PREFERENCE, choice.engine.storageValue)
                    .with(AI_TRANSLATION_PREFERENCE, choice.usesAi)
                    .with(AI_TRANSLATION_ONLY_PREFERENCE, choice == SubtitleTranslator.AI_ONLY)
            }
        }
    }
    if (translator.usesAi) {
        val chatGpt = profile.values[AI_ACCESS_METHOD_PREFERENCE] == AI_ACCESS_CHATGPT
        val savedId = if (chatGpt) CHATGPT_ACCESS_ID else profile.values[AI_TRANSLATION_PROVIDER_PREFERENCE] as? String
        val options = aiAccessOptions().map { stringResource(it.labelRes) to it.id }
        ChoiceRow(
            stringResource(R.string.tab_settings_ai_service),
            options.firstOrNull { it.second == savedId }?.first ?: stringResource(R.string.settings_source_language_auto),
            options,
            "default_ai_service",
        ) { id ->
            edit {
                if (id == CHATGPT_ACCESS_ID) {
                    it.with(AI_ACCESS_METHOD_PREFERENCE, AI_ACCESS_CHATGPT)
                } else {
                    it.with(AI_ACCESS_METHOD_PREFERENCE, "keys").with(AI_TRANSLATION_PROVIDER_PREFERENCE, id)
                }
            }
        }
    }
}

@Composable
private fun DefaultTextRows(
    view: DualSubUiState,
    edit: DefaultEdit,
) {
    Text(stringResource(R.string.settings_text_size, (view.fontScale * 100).toInt()))
    Slider(
        value = view.fontScale,
        onValueChange = { scale -> edit { it.with("font_scale", normalizeFontScale(scale)) } },
        valueRange = MIN_FONT_SCALE..MAX_FONT_SCALE,
        modifier = Modifier.testTag("default_font_scale_slider"),
    )
    SubtitleSlot.entries.forEach { slot ->
        ChoiceRow(
            stringResource(R.string.settings_subtitle_alignment_title) + " · " + slotRoleLabel(slot, null),
            stringResource(view.slotAlignment[slot].labelRes),
            SubtitleAlignment.entries.map { stringResource(it.labelRes) to it.storageValue },
            "default_alignment_${slot.stableId}",
        ) { value -> edit { it.with(slot.alignmentPreference, value) } }
        SettingsSwitchRow(
            title = stringResource(R.string.settings_slot_rtl) + " · " + slotRoleLabel(slot, null),
            description = "",
            checked = view.slotRtl[slot],
            onCheckedChange = { on -> edit { it.with(slot.rtlPreference, on) } },
            testTag = "default_rtl_${slot.stableId}",
            enabled = true,
        )
    }
    ChoiceRow(
        stringResource(R.string.settings_caption_format_title),
        stringResource(view.captionFormat.labelRes),
        CaptionFormat.entries.map { stringResource(it.labelRes) to it.storageValue },
        "default_caption_format",
    ) { value -> edit { it.with(CAPTION_FORMAT_PREFERENCE, value) } }
    listOf(true, false).forEach { original ->
        val visibility = if (original) view.originalVisibility else view.translatedVisibility
        ChoiceRow(
            slotRoleLabel(if (original) SubtitleSlot.ORIGINAL else SubtitleSlot.SECOND, null),
            stringResource(visibility.labelRes),
            CaptionVisibility.entries.map { stringResource(it.labelRes) to it.name },
            "default_visibility_$original",
        ) { value -> edit { it.with(if (original) ORIGINAL_VISIBILITY else TRANSLATED_VISIBILITY, value) } }
    }
}

/** Order and placement: the same two controls as the open tab's page, writing the Default only. */
@Composable
private fun DefaultOrderRows(
    view: DualSubUiState,
    translator: SubtitleTranslator,
    edit: DefaultEdit,
) {
    view.subtitleOrder.forEach { slot ->
        val label = slotRoleLabel(slot, null)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { edit { it.with(SUBTITLE_ORDER_PREFERENCE, view.subtitleOrder.moved(slot, -1).serializedOrder()) } },
                modifier = Modifier.testTag("default_move_up_${slot.stableId}"),
            ) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.settings_slot_move_up, label)) }
            IconButton(
                onClick = { edit { it.with(SUBTITLE_ORDER_PREFERENCE, view.subtitleOrder.moved(slot, 1).serializedOrder()) } },
                modifier = Modifier.testTag("default_move_down_${slot.stableId}"),
            ) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.settings_slot_move_down, label)) }
        }
    }
    TranslationPositionSettings(view.subtitleOrder.translationPosition()) { position ->
        // Choosing a position clears the saved order, as on the open tab's page.
        edit { it.with(TRANSLATION_POSITION_PREFERENCE, position.storageValue).with(SUBTITLE_ORDER_PREFERENCE, null) }
    }
    if (translator == SubtitleTranslator.GOOGLE || translator == SubtitleTranslator.AI_WITH_GOOGLE) {
        GoogleWaitSettings(view.googleWait) { wait -> edit { it.with(GOOGLE_WAIT_PREFERENCE, wait.storageValue) } }
    }
}

/** Colors (Stage 2). */
@Composable
private fun DefaultColorRows(
    view: DualSubUiState,
    edit: DefaultEdit,
) {
    SettingsSwitchRow(
        title = stringResource(R.string.settings_custom_colors_title),
        description = stringResource(R.string.settings_custom_colors_description),
        checked = view.customColorsEnabled,
        onCheckedChange = { on -> edit { it.with(CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE, on) } },
        testTag = "default_custom_colors_switch",
        enabled = true,
    )
    if (view.customColorsEnabled) {
        SubtitleColorSwatchRow(
            title = stringResource(R.string.settings_original_color_title),
            selectedKey = view.originalColorKey,
            enabled = true,
            onColorChange = { key -> edit { it.with(SUBTITLE_ORIGINAL_COLOR_PREFERENCE, key) } },
            testTagPrefix = "default_original_color",
        )
        SubtitleColorSwatchRow(
            title = stringResource(R.string.settings_translated_color_title),
            selectedKey = view.translatedColorKey,
            enabled = true,
            onColorChange = { key -> edit { it.with(SUBTITLE_TRANSLATED_COLOR_PREFERENCE, key) } },
            testTagPrefix = "default_translated_color",
        )
        SubtitleColorSwatchRow(
            title = stringResource(R.string.settings_highlight_color_title),
            selectedKey = view.highlightColorKey,
            enabled = view.wordHighlightEnabled,
            onColorChange = { key -> edit { it.with(SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE, key) } },
            testTagPrefix = "default_highlight_color",
        )
    }
}

/** Word highlight, word learning and the tab playback speed (Stage 2). */
@Composable
private fun DefaultLearningAndSpeedRows(
    view: DualSubUiState,
    profile: TabSettingsProfile,
    edit: DefaultEdit,
) {
    SettingsSwitchRow(
        title = stringResource(R.string.settings_highlight_spoken_words_title),
        description = stringResource(R.string.settings_highlight_spoken_words_description),
        checked = view.wordHighlightEnabled,
        onCheckedChange = { on -> edit { it.with(WORD_HIGHLIGHT_ENABLED_PREFERENCE, on) } },
        testTag = "default_word_highlight_switch",
        enabled = true,
    )
    WordLearningSettings(
        autoPronounce = view.autoPronounce,
        onAutoPronounceChange = { on -> edit { it.with("auto_pronounce", on) } },
        wordLearningEnabled = view.wordLearningEnabled,
        onWordLearningChange = { on -> edit { it.with(WORD_LEARNING_ENABLED_PREFERENCE, on) } },
        wordLearningTarget = view.wordLearningTarget,
        onWordLearningTargetChange = { target -> edit { it.with(WORD_LEARNING_TARGET_PREFERENCE, target) } },
        tapToLearnEnabled = view.tapToLearnEnabled,
        onTapToLearnChange = { on -> edit { it.with(TAP_TO_LEARN_PREFERENCE, on) } },
        wordLearningActiveOnly = view.wordLearningActiveOnly,
        onWordLearningActiveOnlyChange = { on -> edit { it.with(WORD_LEARNING_ACTIVE_ONLY_PREFERENCE, on) } },
    )
    val follow = stringResource(R.string.tab_settings_speed_follow)
    val speed = profile.values[TAB_PLAYBACK_SPEED_PREFERENCE] as? Float
    ChoiceRow(
        stringResource(R.string.tab_settings_playback_speed),
        speed?.let { "$it×" } ?: follow,
        listOf(follow to "") + listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { "$it×" to it.toString() },
        "default_playback_speed",
    ) { value -> edit { it.with(TAB_PLAYBACK_SPEED_PREFERENCE, value.toFloatOrNull()) } }
}

/** A title with the current choice as a button that opens the list of [options] (label to stored value). */
@Composable
private fun ChoiceRow(
    title: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    tag: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, modifier = Modifier.weight(1f))
        Column {
            TextButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) { Text(selectedLabel) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (label, value) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            open = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}
