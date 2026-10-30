package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.translation.MAX_AI_BATCH_SENTENCES
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.flow.StateFlow

/** What the re-translation dialog and buttons can do; null where the build or the current run offers nothing. */
internal class RefreshActions(
    /** The lines the current run translates with the AI (Subtitle 2 only where it does). Empty: nothing to refresh. */
    val available: StateFlow<Set<SubtitleSlot>>,
    val state: StateFlow<RefreshState>,
    /** Sentences a scope would send for each line, for the entire-video warning. */
    val estimate: suspend (RefreshScope) -> Int,
    val start: (Set<SubtitleSlot>, RefreshScope) -> Unit,
    val cancel: () -> Unit,
)

private enum class SlotChoice(
    val slots: Set<SubtitleSlot>,
) {
    FIRST(setOf(SubtitleSlot.SECOND)),
    SECOND(setOf(SubtitleSlot.THIRD)),
    BOTH(setOf(SubtitleSlot.SECOND, SubtitleSlot.THIRD)),
}

/**
 * The compact button beside the overlay's settings and close buttons: opens the re-translation dialog, shows progress while a
 * refresh runs, and is always shown; when no line uses the AI the dialog says what is needed. It needs no debug setting to be found.
 */
@Composable
internal fun RetranslateButton(
    actions: RefreshActions?,
    initial: SubtitleSlot = SubtitleSlot.SECOND,
    modifier: Modifier = Modifier,
) {
    if (actions == null) return
    val state by actions.state.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    // The button keeps its name while a refresh runs, so a screen reader still finds it.
    val name = stringResource(R.string.retranslate_title)
    IconButton(onClick = { open = true }, modifier = modifier.testTag("retranslate_button").semantics { contentDescription = name }) {
        if (state.running.isEmpty()) {
            Icon(Icons.Default.AutoFixHigh, contentDescription = null)
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    if (open) DualSubTheme { RetranslateDialog(initial, actions) { open = false } }
}

/** The mark under a line while that line is refreshing, or the short failure text after a refresh failed. */
@Composable
internal fun RefreshStatusText(
    slot: SubtitleSlot,
    color: androidx.compose.ui.graphics.Color,
) {
    val info = LocalTranslationSourceInfo.current
    val state by info.refreshState.collectAsState()
    when {
        slot in state.running ->
            Text(
                stringResource(R.string.retranslate_refreshing),
                color = color.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("refreshing_${slot.stableId}"),
            )
        slot in state.failed ->
            Text(
                stringResource(R.string.retranslate_failed_short),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("refresh_failed_${slot.stableId}"),
            )
    }
}

/**
 * Which lines (default: the one it was opened from), how much (default: the current sentence), a note about allowance, and for
 * the entire video an explicit warning with an estimate and a confirmation. While it runs it shows progress and can be stopped;
 * the subtitles on screen never go away.
 */
@Composable
internal fun RetranslateDialog(
    initial: SubtitleSlot,
    actions: RefreshActions,
    onDismiss: () -> Unit,
) {
    val available by actions.available.collectAsState()
    val state by actions.state.collectAsState()
    var choice by rememberSaveable { mutableStateOf(if (initial == SubtitleSlot.THIRD) SlotChoice.SECOND else SlotChoice.FIRST) }
    var scope by rememberSaveable { mutableStateOf(RefreshScope.SENTENCE) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var started by rememberSaveable { mutableStateOf(false) }
    val sentences = remember { mutableIntStateOf(0) }
    LaunchedEffect(scope) {
        confirmed = false
        sentences.intValue = if (scope == RefreshScope.VIDEO) actions.estimate(scope) else 0
    }
    // A choice the run cannot do (Subtitle 2 without the AI) falls back to what it can.
    val usable = choice.slots.filter { it in available }.toSet()
    val running = state.running.isNotEmpty()
    // The dialog closes itself once what it started has finished without a failure.
    LaunchedEffect(running, state.failed) {
        if (started && !running && state.failed.isEmpty()) onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.retranslate_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.retranslate_note), style = MaterialTheme.typography.bodySmall)
                if (available.isEmpty()) {
                    Text(stringResource(R.string.retranslate_unavailable), color = MaterialTheme.colorScheme.error)
                } else {
                    SlotChoices(choice, available, running) { choice = it }
                    ScopeChoices(scope, running) { scope = it }
                    if (scope == RefreshScope.VIDEO) {
                        VideoWarning(sentences.intValue, usable.size.coerceAtLeast(1), confirmed, running) { confirmed = it }
                    }
                    RefreshProgress(state)
                }
            }
        },
        confirmButton = {
            if (running) {
                TextButton(
                    onClick = actions.cancel,
                    modifier = Modifier.testTag("retranslate_stop"),
                ) { Text(stringResource(R.string.retranslate_stop)) }
            } else {
                TextButton(
                    onClick = {
                        started = true
                        actions.start(usable, scope)
                    },
                    enabled = usable.isNotEmpty() && (scope != RefreshScope.VIDEO || confirmed),
                    modifier = Modifier.testTag("retranslate_start"),
                ) { Text(stringResource(R.string.retranslate_start)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.translation_source_close)) } },
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SlotChoices(
    choice: SlotChoice,
    available: Set<SubtitleSlot>,
    running: Boolean,
    onChoose: (SlotChoice) -> Unit,
) {
    Text(stringResource(R.string.retranslate_subtitles), style = MaterialTheme.typography.labelLarge)
    Column(Modifier.selectableGroup()) {
        SlotChoice.entries.forEach { option ->
            val label =
                when (option) {
                    SlotChoice.FIRST -> stringResource(SubtitleSlot.SECOND.roleLabelRes)
                    SlotChoice.SECOND -> stringResource(SubtitleSlot.THIRD.roleLabelRes)
                    SlotChoice.BOTH -> stringResource(R.string.retranslate_both)
                }
            ChoiceRow(
                label,
                choice == option,
                option.slots.all { it in available } && !running,
                "retranslate_slot_${option.name.lowercase()}",
            ) {
                onChoose(option)
            }
        }
    }
}

@Composable
private fun ScopeChoices(
    scope: RefreshScope,
    running: Boolean,
    onChoose: (RefreshScope) -> Unit,
) {
    Text(stringResource(R.string.retranslate_scope), style = MaterialTheme.typography.labelLarge)
    Column(Modifier.selectableGroup()) {
        RefreshScope.entries.forEach { option ->
            val label =
                stringResource(
                    when (option) {
                        RefreshScope.SENTENCE -> R.string.retranslate_scope_sentence
                        RefreshScope.WINDOW -> R.string.retranslate_scope_window
                        RefreshScope.VIDEO -> R.string.retranslate_scope_video
                    },
                )
            ChoiceRow(label, scope == option, !running, "retranslate_scope_${option.name.lowercase()}") { onChoose(option) }
        }
    }
}

/** The entire video can use a lot of allowance or credit, so it says how much and waits for an explicit yes. */
@Composable
private fun VideoWarning(
    sentences: Int,
    lines: Int,
    confirmed: Boolean,
    running: Boolean,
    onConfirm: (Boolean) -> Unit,
) {
    val requests = (sentences + MAX_AI_BATCH_SENTENCES - 1) / MAX_AI_BATCH_SENTENCES * lines
    Text(
        stringResource(R.string.retranslate_video_warning, sentences * lines, requests),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("retranslate_video_warning"),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(
                min = 48.dp,
            ).toggleable(confirmed, enabled = !running, role = Role.Checkbox, onValueChange = onConfirm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = confirmed, onCheckedChange = null, modifier = Modifier.testTag("retranslate_confirm"))
        Text(stringResource(R.string.retranslate_video_confirm), modifier = Modifier.padding(start = 8.dp))
    }
}

/** Progress while a refresh runs, and the reason when one failed (the earlier subtitles stay either way). */
@Composable
private fun RefreshProgress(state: RefreshState) {
    if (state.running.isNotEmpty()) {
        Text(stringResource(R.string.retranslate_progress, state.done, state.total), modifier = Modifier.testTag("retranslate_progress"))
        LinearProgressIndicator(
            progress = { if (state.total == 0) 0f else (state.done.toFloat() / state.total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    state.failed.values.firstOrNull()?.let { reason ->
        Text(
            stringResource(R.string.retranslate_failed, reason),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("retranslate_failure"),
        )
    }
}

/** The panel header's actions in order: Re-translate with AI (always shown), the subtitle settings, and hide. [compact] uses 36 dp buttons. */
@Composable
internal fun PanelHeaderActions(
    onSettings: () -> Unit,
    onHide: () -> Unit,
    compact: Boolean,
) {
    val button = if (compact) Modifier.size(36.dp) else Modifier
    val icon = if (compact) Modifier.size(20.dp) else Modifier
    RetranslateButton(LocalTranslationSourceInfo.current.refresh, modifier = button)
    IconButton(onClick = onSettings, modifier = button) {
        Icon(
            Icons.Default.Settings,
            contentDescription = stringResource(R.string.player_subtitle_settings),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = icon,
        )
    }
    IconButton(onClick = onHide, modifier = button) {
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.player_hide_dual_subtitles),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = icon,
        )
    }
}
