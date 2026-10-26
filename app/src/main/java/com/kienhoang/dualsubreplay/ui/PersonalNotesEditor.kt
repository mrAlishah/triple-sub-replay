package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R

/**
 * The user's own multiline notes for a saved word (the Anki "Desc" field). Enter adds a line, text lays
 * out by its own direction so Persian reads right-to-left, and nothing is saved until Save (or Back, or a
 * review rating) is pressed; unsaved edits are flagged.
 */
@Composable
internal fun PersonalNotesEditor(
    notes: String,
    onNotes: (String) -> Unit,
    changed: Boolean,
    enabled: Boolean,
    onSave: () -> Unit,
) {
    OutlinedTextField(
        value = notes,
        onValueChange = onNotes,
        label = { Text(stringResource(R.string.practice_notes_label)) },
        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
        minLines = 3,
        maxLines = 12,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().testTag("saved_notes"),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onSave, enabled = enabled && changed, modifier = Modifier.testTag("save_notes")) {
            Text(stringResource(R.string.practice_save_notes))
        }
        if (changed) {
            Text(
                stringResource(R.string.practice_notes_unsaved),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp).testTag("notes_unsaved"),
            )
        }
    }
}
