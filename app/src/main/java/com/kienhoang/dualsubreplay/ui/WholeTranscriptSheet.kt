package com.kienhoang.dualsubreplay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

/** What the player needs to open the Whole transcript sheet; null in previews and tests without a video. */
internal class WholeTranscriptAccess(
    val transcript: StateFlow<WholeTranscript?>,
    val exporter: TranscriptExporter,
    val title: () -> String?,
)

internal val LocalWholeTranscript = staticCompositionLocalOf<WholeTranscriptAccess?> { null }

/** [open] while the playing video has a whole transcript to show; null with live subtitles or between videos. */
@Composable
internal fun rememberWholeTranscriptOpener(open: () -> Unit): (() -> Unit)? {
    val access = LocalWholeTranscript.current ?: return null
    val transcript by access.transcript.collectAsState()
    val enabled by rememberWholeTranscriptEnabled()
    return open.takeIf { enabled && transcript != null }
}

/** Settings → Playback → Whole transcript: the gear popup's Whole transcript link. Off by default (owner, 2026-11-17). */
internal const val WHOLE_TRANSCRIPT_PREFERENCE = "whole_transcript"

@Composable
internal fun rememberWholeTranscriptEnabled(): State<Boolean> =
    rememberStoredSetting(WHOLE_TRANSCRIPT_PREFERENCE) { it.getBoolean(WHOLE_TRANSCRIPT_PREFERENCE, false) }

/** The search box above the lines; ▲ / ▼ leave it on a TV remote. */
@Composable
private fun TranscriptSearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .tvArrowsLeave()
                .testTag("whole_transcript_search"),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.whole_transcript_search)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

/** The sheet for the playing video; closes itself when the video changes and its transcript goes. */
@Composable
internal fun PlayerWholeTranscript(
    state: DualSubUiState,
    onJump: (SubtitleSegment) -> Unit,
    onDismiss: () -> Unit,
) {
    val access = LocalWholeTranscript.current
    val transcript = access?.transcript?.collectAsState()?.value
    if (access == null || transcript == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    WholeTranscriptSheet(
        transcript = transcript,
        currentStartMs = state.segments.getOrNull(state.currentIndex)?.startMs,
        exporter = access.exporter,
        title = remember(transcript) { access.title() },
        onJump = onJump,
        onDismiss = onDismiss,
    )
}

/** How long the list must rest before the rows on screen are translated, so a fast scroll asks nothing. */
private const val TRANSLATE_AFTER_SCROLL_MS = 600L

/** How long typing must pause before the transcript is searched. */
private const val SEARCH_AFTER_TYPING_MS = 300L

/**
 * The Whole transcript sheet, opened from the subtitle gear popup (25kiuo, Discussion #81,
 * 2026-11-17): every line of the video from start to end, a search box, a tap on a line to jump
 * there, and Export. Rows come from [transcript] a page at a time; translations show under a
 * sentence's first row once the list rests on it.
 */
@Composable
internal fun WholeTranscriptSheet(
    transcript: WholeTranscript,
    currentStartMs: Long?,
    exporter: TranscriptExporter,
    title: String?,
    onJump: (SubtitleSegment) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    val matches by produceState<List<Int>?>(null, transcript, query) {
        value = null
        if (query.isBlank()) return@produceState
        delay(SEARCH_AFTER_TYPING_MS)
        value = transcript.search(query)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(transcript) {
        // Opens at the playing line rather than at the start of a long video.
        val start = currentStartMs ?: return@LaunchedEffect
        val index = (0 until transcript.size).firstStartAfter(transcript, start)
        if (index > 0) listState.scrollToItem(index)
    }
    val translations = remember(transcript) { mutableStateMapOf<Int, String>() }
    // Registered here, not in the export dialog, which closes before Android's save screen answers.
    var pending by remember { mutableStateOf<TranscriptExportOptions?>(null) }
    val saveScreen =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val picked = pending
            pending = null
            if (uri != null && picked != null) exporter.start(transcript, uri, picked, title)
        }
    TranslateRowsOnScreen(transcript, listState, translations, enabled = query.isBlank())

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("whole_transcript"),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.whole_transcript_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { exporting = true }, modifier = Modifier.testTag("whole_transcript_export")) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.whole_transcript_export))
                    }
                    CloseIconButton(onDismiss)
                }
                TranscriptSearchField(query) { query = it }
                ExportProgress(exporter)
                val found = matches
                when {
                    query.isNotBlank() && found == null -> Unit
                    query.isNotBlank() && found.isNullOrEmpty() ->
                        Text(
                            stringResource(R.string.whole_transcript_no_match),
                            modifier = Modifier.padding(16.dp).testTag("whole_transcript_no_match"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else ->
                        LazyColumn(
                            state = if (query.isBlank()) listState else rememberLazyListState(),
                            modifier = Modifier.weight(1f).testTag("whole_transcript_list"),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            val jump: (SubtitleSegment) -> Unit = { row ->
                                onJump(row)
                                onDismiss()
                            }
                            if (found == null) {
                                items(transcript.size, key = { it }) { index ->
                                    TranscriptRow(transcript, index, translations[index], currentStartMs, jump)
                                }
                            } else {
                                items(found, key = { it }) { index ->
                                    TranscriptRow(transcript, index, translations[index], currentStartMs, jump)
                                }
                            }
                        }
                }
            }
        }
    }
    if (exporting) {
        ExportDialog(
            subtitle1Language = transcript.subtitle1Language,
            subtitle2Language = transcript.subtitle2Language,
            subtitle1UsesAi = transcript.subtitle1UsesAi,
            subtitle2UsesAi = transcript.subtitle2UsesAi,
            onDismiss = { exporting = false },
        ) { options ->
            exporting = false
            pending = options
            saveScreen.launch(exportFileName(title, options.format))
        }
    }
}

/** The first row starting at or after [startMs] minus one, by binary search over the rows on disk. */
private suspend fun IntRange.firstStartAfter(
    transcript: WholeTranscript,
    startMs: Long,
): Int {
    var low = first
    var high = last
    var found = first
    while (low <= high) {
        val middle = (low + high).ushr(1)
        val row = transcript.row(middle) ?: return first
        if (row.startMs <= startMs) {
            found = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return found
}

@Composable
private fun TranslateRowsOnScreen(
    transcript: WholeTranscript,
    listState: LazyListState,
    translations: MutableMap<Int, String>,
    enabled: Boolean,
) {
    LaunchedEffect(transcript, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collectLatest { visible ->
                if (visible.isEmpty()) return@collectLatest
                delay(TRANSLATE_AFTER_SCROLL_MS)
                translations.putAll(transcript.translateStarts(visible.first()..visible.last()))
            }
    }
}

@Composable
private fun TranscriptRow(
    transcript: WholeTranscript,
    index: Int,
    translation: String?,
    currentStartMs: Long?,
    onClick: (SubtitleSegment) -> Unit,
) {
    val row by produceState<SubtitleSegment?>(null, transcript, index) { value = transcript.row(index) }
    val segment = row
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = segment != null, role = Role.Button) { segment?.let(onClick) }
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("whole_transcript_row_$index"),
    ) {
        Text(
            segment?.let { clockTime(it.startMs) }.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(56.dp).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            val playing = segment != null && currentStartMs == segment.startMs
            Text(
                segment?.originalText ?: "…",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (playing) FontWeight.Bold else null,
            )
            val shown = translation ?: segment?.takeIf(::startsSentence)?.let(transcript::knownTranslation)
            shown?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("whole_transcript_translation_$index"),
                )
            }
        }
    }
}

@Composable
private fun ExportProgress(exporter: TranscriptExporter) {
    val state by exporter.state.collectAsState()
    val export = state ?: return
    val locale = currentInterfaceLocale()
    // A file that stopped early may stay behind incomplete: the save screen already made it, and it is never deleted.
    val mayBeIncomplete =
        export.result in
            setOf(TranscriptExportState.Result.FAILED, TranscriptExportState.Result.CHANGED, TranscriptExportState.Result.CANCELLED)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("whole_transcript_export_progress")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (export.result == TranscriptExportState.Result.PARTIAL && export.missingLanguages.isNotEmpty()) {
                        stringResource(
                            R.string.whole_transcript_saved_missing,
                            export.missingLanguages.joinToString { languageDisplayName(it, locale) },
                        )
                    } else {
                        stringResource(
                            when (export.result) {
                                TranscriptExportState.Result.RUNNING -> R.string.whole_transcript_exporting
                                TranscriptExportState.Result.SAVED, TranscriptExportState.Result.PARTIAL -> R.string.whole_transcript_saved
                                TranscriptExportState.Result.FAILED -> R.string.whole_transcript_export_failed
                                TranscriptExportState.Result.CHANGED -> R.string.whole_transcript_export_changed
                                TranscriptExportState.Result.CANCELLED -> R.string.whole_transcript_export_cancelled
                            },
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (mayBeIncomplete) {
                    Text(
                        stringResource(R.string.whole_transcript_export_incomplete_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("whole_transcript_export_incomplete_note"),
                    )
                }
            }
            if (export.result == TranscriptExportState.Result.RUNNING) {
                TextButton(onClick = exporter::cancel) { Text(stringResource(R.string.whole_transcript_export_cancel)) }
            } else {
                TextButton(onClick = exporter::dismiss) { Text(stringResource(R.string.whole_transcript_export_ok)) }
            }
        }
        if (export.result == TranscriptExportState.Result.RUNNING) {
            LinearProgressIndicator(
                progress = { if (export.total > 0) export.done.toFloat() / export.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Export: text or SRT, then which translation lines follow the Original (Subtitle 1 on, Subtitle 2 off by default),
 * then Android's own save screen picks the file. [subtitle2Language] is null when Subtitle 2 is off or repeats another
 * line: its box is then disabled and says why, so no file silently lacks it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportDialog(
    subtitle1Language: String?,
    subtitle2Language: String?,
    subtitle1UsesAi: Boolean = false,
    subtitle2UsesAi: Boolean = false,
    onDismiss: () -> Unit,
    onExport: (TranscriptExportOptions) -> Unit,
) {
    var format by remember { mutableStateOf(TranscriptExportFormat.TEXT) }
    var withSubtitle1 by remember { mutableStateOf(true) }
    var withSubtitle2 by remember { mutableStateOf(false) }
    val locale = currentInterfaceLocale()
    val subtitle2Available = subtitle2Language != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.whole_transcript_export), onClose = onDismiss) },
        text = {
            // Scrolls, so the notes never push Save off a small or landscape screen.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = format == TranscriptExportFormat.TEXT,
                        onClick = { format = TranscriptExportFormat.TEXT },
                        label = { Text(stringResource(R.string.whole_transcript_format_text)) },
                        modifier = Modifier.testTag("whole_transcript_format_text"),
                    )
                    FilterChip(
                        selected = format == TranscriptExportFormat.SRT,
                        onClick = { format = TranscriptExportFormat.SRT },
                        label = { Text(stringResource(R.string.whole_transcript_format_srt)) },
                        modifier = Modifier.testTag("whole_transcript_format_srt"),
                    )
                }
                SubtitleChoice(
                    label = subtitleChoiceLabel(R.string.subtitle_role_first, subtitle1Language, locale),
                    checked = withSubtitle1,
                    enabled = true,
                    testTag = "whole_transcript_with_subtitle1",
                ) { withSubtitle1 = !withSubtitle1 }
                SubtitleChoice(
                    label = subtitleChoiceLabel(R.string.subtitle_role_second, subtitle2Language, locale),
                    checked = withSubtitle2 && subtitle2Available,
                    enabled = subtitle2Available,
                    testTag = "whole_transcript_with_subtitle2",
                ) { withSubtitle2 = !withSubtitle2 }
                if (!subtitle2Available) {
                    Text(
                        stringResource(R.string.whole_transcript_subtitle2_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("whole_transcript_subtitle2_unavailable"),
                    )
                }
                if (withSubtitle1 || (withSubtitle2 && subtitle2Available)) {
                    Text(
                        stringResource(R.string.whole_transcript_with_translation_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The selected AI service is asked for lines it has not translated yet, and that uses its quota or credits.
                if ((withSubtitle1 && subtitle1UsesAi) || (withSubtitle2 && subtitle2Available && subtitle2UsesAi)) {
                    Text(
                        stringResource(R.string.whole_transcript_ai_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("whole_transcript_ai_note"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onExport(TranscriptExportOptions(format, withSubtitle1, withSubtitle2 && subtitle2Available)) },
                modifier = Modifier.testTag("whole_transcript_export_save"),
            ) { Text(stringResource(R.string.whole_transcript_export_save)) }
        },
    )
}

/** "Subtitle 1 (German)": the slot's role name and, when known, the language it shows now. */
@Composable
private fun subtitleChoiceLabel(
    @StringRes role: Int,
    language: String?,
    locale: Locale,
): String =
    stringResource(role).let { name ->
        if (language ==
            null
        ) {
            name
        } else {
            stringResource(R.string.whole_transcript_include_subtitle, name, languageDisplayName(language, locale))
        }
    }

@Composable
private fun SubtitleChoice(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                .testTag(testTag),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The file name the save screen suggests: the video's title, made safe for a file name. */
internal fun exportFileName(
    title: String?,
    format: TranscriptExportFormat,
): String {
    val base =
        title
            ?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(80)
            ?.ifBlank { null }
            ?: "transcript"
    return "$base.${format.extension}"
}
