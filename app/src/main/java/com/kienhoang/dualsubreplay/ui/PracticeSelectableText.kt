package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle

/**
 * Practice text whose words can be selected like a subtitle line: Copy, Pronounce and Google
 * Translate in the same popup bar. [language] is the text's own language, so words split and
 * speak correctly. The in-app Translate action is left out, because the word sheet opens beneath Practice.
 */
@Composable
internal fun PracticeSelectableText(
    text: String,
    language: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    modifier: Modifier = Modifier,
) {
    SelectableSubtitleText(
        text = text,
        annotated = AnnotatedString(text),
        style = style.copy(color = LocalContentColor.current),
        languageCode = language,
        segment = null,
        translated = false,
        highlightColor = MaterialTheme.colorScheme.primary,
        onWordClick = {},
        onBlankTap = {},
        // Merged, so a test or screen reader still reads the whole line as one text.
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        lineLanguage = language,
        showTranslate = false,
    )
}
