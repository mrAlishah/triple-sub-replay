package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.SourceOption
import com.kienhoang.dualsubreplay.data.UNKNOWN_SOURCE_KEY
import com.kienhoang.dualsubreplay.data.filterSourceOptions

/** The name of [option] in the selector: its title, the video id added when another source has the same title, else "Unknown Source". */
@Composable
internal fun sourceOptionName(option: SourceOption): String =
    when {
        option.title == null && option.videoId != null ->
            stringResource(R.string.practice_source_same_title, stringResource(R.string.practice_source_unknown), option.videoId)
        option.title == null -> stringResource(R.string.practice_source_unknown)
        option.sharesTitle && option.videoId != null -> stringResource(R.string.practice_source_same_title, option.title, option.videoId)
        else -> option.title
    }

/** The "Source / Video" button and, once opened, a searchable list: All Sources, then each saved source. */
@Composable
internal fun SourceFilterPicker(
    options: List<SourceOption>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.key == selectedKey }
    val label = stringResource(R.string.practice_source_label)
    val currentName = current?.let { sourceOptionName(it) } ?: stringResource(R.string.practice_source_all)
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("source_filter")) {
        Text("$label: $currentName", maxLines = 1)
    }
    if (!open) return
    var query by remember { mutableStateOf("") }
    val shown = filterSourceOptions(options, query)
    val allName = stringResource(R.string.practice_source_all)
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label) },
        text = {
            Column {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text(stringResource(R.string.practice_source_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("source_search"),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (query.isBlank()) {
                        item {
                            SourceRow(allName, selectedKey == null, "source_all") {
                                onSelect(null)
                                open = false
                            }
                        }
                    }
                    items(shown, key = { it.key.ifEmpty { "unknown" } }) { option ->
                        val name = sourceOptionName(option)
                        SourceRow(
                            stringResource(R.string.practice_source_option, name, option.count),
                            option.key == selectedKey,
                            if (option.key == UNKNOWN_SOURCE_KEY) "source_unknown" else "source_${option.key}",
                        ) {
                            onSelect(option.key)
                            open = false
                        }
                    }
                    if (shown.isEmpty() && query.isNotBlank()) item { Text(stringResource(R.string.practice_source_none_found)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.practice_close)) } },
    )
}

@Composable
private fun SourceRow(
    text: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(text, style = TextStyle(textDirection = TextDirection.Content)) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
    )
}

/** A saved word's meaning with its source title below it, small and on one line. */
@Composable
internal fun MeaningWithSource(word: SavedWord) {
    Column {
        Text(word.meaning)
        word.sourceTitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("saved_word_source_${word.id}"),
            )
        }
    }
}

/** The source of the word being reviewed, so a filtered session always shows where its cards come from. */
@Composable
internal fun PracticeSourceLine(title: String) {
    Text(
        stringResource(R.string.practice_source_in_session, title),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("practice_source"),
    )
}

/** The title field of a saved word and, once it differs from the saved one, the button that keeps it. */
@Composable
internal fun SourceTitleEditor(
    value: String,
    onValue: (String) -> Unit,
    changed: Boolean,
    enabled: Boolean,
    onSave: () -> Unit,
) {
    OutlinedTextField(
        value,
        onValue,
        textStyle = TextStyle(textDirection = TextDirection.Content),
        label = { Text(stringResource(R.string.practice_source_title_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("source_title_field"),
    )
    if (changed) {
        TextButton(enabled = enabled, onClick = onSave, modifier = Modifier.testTag("save_source_title")) {
            Text(stringResource(R.string.practice_source_title_save))
        }
    }
}
