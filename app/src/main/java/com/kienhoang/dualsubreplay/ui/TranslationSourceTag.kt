package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.translation.TranslationEvent
import com.kienhoang.dualsubreplay.translation.TranslationEventKind
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import com.kienhoang.dualsubreplay.translation.TranslationOrigin
import com.kienhoang.dualsubreplay.translation.TranslationProvider
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date

private const val DETAIL_EVENTS = 8

/** Whether the source labels show (Settings → Captions & translation) and the diagnostics events behind them. */
internal class TranslationSourceInfo(
    val show: Boolean = false,
    val events: StateFlow<List<TranslationEvent>> = MutableStateFlow(emptyList()),
)

internal val LocalTranslationSourceInfo = staticCompositionLocalOf { TranslationSourceInfo() }

/** The label text for [origin]; an origin that was not recorded reads as unknown, never as a guess. */
@StringRes
internal fun translationSourceRes(origin: TranslationOrigin?): Int {
    if (origin == null) return R.string.translation_source_unknown
    return when (origin.provider) {
        TranslationProvider.CHATGPT ->
            if (origin.cached) R.string.translation_source_chatgpt_cached else R.string.translation_source_chatgpt
        TranslationProvider.AI_PROVIDER ->
            if (origin.cached) R.string.translation_source_ai_provider_cached else R.string.translation_source_ai_provider
        TranslationProvider.GOOGLE -> R.string.translation_source_google
        TranslationProvider.ON_DEVICE -> R.string.translation_source_on_device
        TranslationProvider.YOUTUBE -> R.string.translation_source_youtube
        TranslationProvider.UNKNOWN -> R.string.translation_source_unknown
    }
}

/** One diagnostics line, from counts and codes only (an event never holds subtitle text). */
internal fun translationEventText(
    event: TranslationEvent,
    strings: UiStrings,
): String =
    when (event.kind) {
        TranslationEventKind.AI_REQUEST -> strings.get(R.string.translation_source_event_request, event.count)
        TranslationEventKind.AI_SUCCEEDED -> strings.get(R.string.translation_source_event_succeeded, event.count)
        TranslationEventKind.AI_FAILED ->
            strings.get(R.string.translation_source_event_failed, event.reason ?: "?", event.count)
        TranslationEventKind.AI_IDENTICAL ->
            strings.get(R.string.translation_source_event_identical, strings.get(translationSourceRes(event.origin)))
        TranslationEventKind.FALLBACK ->
            strings.get(R.string.translation_source_event_fallback, strings.get(translationSourceRes(event.origin)))
    }

/** The events of one target language, newest first, as the details view lists them. */
internal fun eventsFor(
    events: List<TranslationEvent>,
    targetLanguage: String,
): List<TranslationEvent> {
    val target = TranslationLanguages.normalize(targetLanguage)
    return events.filter { it.target == target }.takeLast(DETAIL_EVENTS).reversed()
}

/**
 * The small source label under a translated subtitle line, only while Show translation source is on. Tapping it
 * opens the details. [origin] is what was recorded when the shown text was produced.
 */
@Composable
internal fun TranslationSourceTag(
    origin: TranslationOrigin?,
    slot: SubtitleSlot,
    targetLanguage: String,
    color: Color,
) {
    val info = LocalTranslationSourceInfo.current
    if (!info.show) return
    var details by rememberSaveable { mutableStateOf(false) }
    Text(
        text = stringResource(translationSourceRes(origin)),
        fontSize = 10.sp,
        lineHeight = 12.sp,
        color = color.copy(alpha = 0.7f),
        modifier =
            Modifier
                .padding(top = 1.dp)
                .clickable { details = true }
                .testTag("translation_source_${slot.stableId}"),
    )
    if (details) {
        val events by info.events.collectAsState()
        DualSubTheme {
            TranslationSourceDialog(origin, slot, targetLanguage, eventsFor(events, targetLanguage)) { details = false }
        }
    }
}

@Composable
private fun TranslationSourceDialog(
    origin: TranslationOrigin?,
    slot: SubtitleSlot,
    targetLanguage: String,
    events: List<TranslationEvent>,
    onDismiss: () -> Unit,
) {
    val strings = LocalResources.current.uiStrings()
    val time = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.translation_source_close)) } },
        title = { Text(stringResource(R.string.translation_source_dialog_title)) },
        text = {
            Column {
                Text(stringResource(translationSourceRes(origin)), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.translation_source_dialog_slot, stringResource(slot.roleLabelRes), targetLanguage),
                    style = MaterialTheme.typography.bodySmall,
                )
                origin?.model?.let {
                    Text(stringResource(R.string.translation_source_dialog_model, it), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.translation_source_dialog_events),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (events.isEmpty()) {
                    Text(stringResource(R.string.translation_source_dialog_no_events), style = MaterialTheme.typography.bodySmall)
                }
                events.forEach { event ->
                    Text(
                        "${time.format(Date(event.atMs))}  ${translationEventText(event, strings)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}
