package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.normalizeSourceTitle

/** Saves the title the user typed for a video (video id, title); null where there is nothing to save to. */
internal val LocalVideoTitleEditor = staticCompositionLocalOf<((String, String) -> Unit)?> { null }

/** The open video's title in the subtitle header, with a pencil that opens the title for editing. */
@Composable
internal fun SourceTitleLine(
    state: DualSubUiState,
    style: TextStyle,
) {
    val videoId = state.activeVideoId
    val title = state.titleOf(videoId)
    val editor = LocalVideoTitleEditor.current
    // The video the dialog was opened for: playback may move on to another video while it is open.
    var editingId by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TitleText(title, style)
        if (videoId != null && editor != null) {
            IconButton(onClick = { editingId = videoId }, modifier = Modifier.size(28.dp).testTag("edit_source_title")) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.player_edit_video_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
    val openFor = editingId
    if (openFor != null && editor != null) {
        EditVideoTitleDialog(state.titleOf(openFor).orEmpty(), onCancel = { editingId = null }) {
            editor(openFor, it)
            editingId = null
        }
    }
}

@Composable
private fun RowScope.TitleText(
    title: String?,
    style: TextStyle,
) {
    Text(
        text = title ?: stringResource(R.string.player_set_video_title),
        style = style,
        color = if (title == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false).testTag("source_title_header"),
    )
}

@Composable
private fun EditVideoTitleDialog(
    initial: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.player_video_title_dialog)) },
        text = {
            OutlinedTextField(
                text,
                { text = it },
                singleLine = true,
                textStyle = TextStyle(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth().testTag("source_title_input"),
            )
        },
        confirmButton = {
            TextButton(
                enabled = normalizeSourceTitle(text) != null,
                onClick = { onSave(text) },
                modifier = Modifier.testTag("source_title_save"),
            ) { Text(stringResource(R.string.player_video_title_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.player_video_title_cancel)) } },
    )
}
