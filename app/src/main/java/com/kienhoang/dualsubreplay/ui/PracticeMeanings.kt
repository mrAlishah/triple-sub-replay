package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.*
import java.util.Locale

// Fork-owned (docs/development/fork-customizations.md): the further-language meanings of a saved word, shown in Practice and edited in Vocabulary.

/** The further-language meanings of [word] as text fields' state, one entry per language. */
internal fun editableMeanings(word: SavedWord?): SnapshotStateMap<String, String> =
    mutableStateMapOf<String, String>().apply { putAll(word?.meanings.orEmpty()) }

/** Content direction lays a Persian or Arabic meaning out right-to-left whatever the interface language. */
@Composable
private fun meaningTextStyle(): TextStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content)

/** The answer side of a Practice card: the primary meaning, then each further language under its own label. */
@Composable
internal fun ReviewMeanings(
    word: SavedWord,
    locale: Locale,
) {
    val style = meaningTextStyle()
    PracticeSelectableText(word.meaning, word.meaningLanguage, style, Modifier.testTag("review_meaning"))
    word.meanings.forEach { (language, text) ->
        val label = slotRoleLabel(SubtitleSlot.THIRD, languageDisplayName(language, locale))
        Text(label, style = MaterialTheme.typography.labelLarge)
        PracticeSelectableText(text, language, style, Modifier.testTag("review_meaning_$language"))
    }
}

/** The Vocabulary edit view: the primary meaning, then one text field per further language, each edited on its own. */
@Composable
internal fun EditMeanings(
    word: SavedWord,
    meaning: String,
    onMeaning: (String) -> Unit,
    edited: SnapshotStateMap<String, String>,
    locale: Locale,
    enabled: Boolean,
    onSave: () -> Unit,
) {
    val style = meaningTextStyle()
    val primaryLabel = slotRoleLabel(SubtitleSlot.SECOND, languageDisplayName(word.meaningLanguage, locale))
    OutlinedTextField(meaning, onMeaning, textStyle = style, label = { Text(primaryLabel) })
    val languages = edited.keys.toList()
    languages.forEach { language ->
        val label = slotRoleLabel(SubtitleSlot.THIRD, languageDisplayName(language, locale))
        OutlinedTextField(
            value = edited[language].orEmpty(),
            onValueChange = { edited[language] = it },
            textStyle = style,
            label = { Text(label) },
            modifier = Modifier.testTag("saved_meaning_$language"),
        )
    }
    val changed = meaning != word.meaning || edited.toMap() != word.meanings
    TextButton(onClick = onSave, enabled = enabled && changed) { Text(stringResource(R.string.practice_save_meaning)) }
}

/**
 * [this] with the edits still pending in the details view: the meaning fields (Vocabulary view only, they are
 * read-only in a review) and the notes. Used to save them before a send, so the sent note is never stale.
 */
internal fun SavedWord.withPendingEdits(
    practice: Boolean,
    meaning: String,
    edited: Map<String, String>,
    notes: String,
): SavedWord = (if (practice) this else withMeanings(meaning, edited)).copy(personalNotes = notes)

/** The edited texts as saved: trimmed, and a language whose field was emptied is dropped. */
internal fun SavedWord.withMeanings(
    meaning: String,
    edited: Map<String, String>,
): SavedWord {
    val kept = LinkedHashMap<String, String>()
    for ((language, text) in edited) if (text.isNotBlank()) kept[language] = text.trim()
    return copy(meaning = meaning.trim(), meanings = kept)
}
