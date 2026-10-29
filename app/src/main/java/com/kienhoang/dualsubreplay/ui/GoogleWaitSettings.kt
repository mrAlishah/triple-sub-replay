package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.translation.GoogleWait
import com.kienhoang.dualsubreplay.translation.SubtitleTranslator

/** The chip text of each Wait for Google choice. */
@get:StringRes
internal val GoogleWait.labelRes: Int
    get() =
        when (this) {
            GoogleWait.AUTO -> R.string.settings_google_wait_auto
            GoogleWait.TWO_SECONDS -> R.string.settings_google_wait_2
            GoogleWait.FOUR_SECONDS -> R.string.settings_google_wait_4
            GoogleWait.EIGHT_SECONDS -> R.string.settings_google_wait_8
        }

/** Settings → Translation → Wait for Google, shown while Google's lines are shown first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GoogleWaitSettings(
    wait: GoogleWait,
    onChange: (GoogleWait) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.settings_google_wait_title))
        Text(
            stringResource(R.string.settings_google_wait_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoogleWait.entries.forEach { option ->
                FilterChip(
                    selected = wait == option,
                    onClick = { onChange(option) },
                    label = { Text(stringResource(option.labelRes)) },
                    modifier = Modifier.testTag("google_wait_${option.storageValue}"),
                )
            }
        }
    }
}

/** The title of each Translate subtitles with choice. */
@get:StringRes
internal val SubtitleTranslator.titleRes: Int
    get() =
        when (this) {
            SubtitleTranslator.GOOGLE -> R.string.settings_translator_google
            SubtitleTranslator.AI_WITH_GOOGLE -> R.string.settings_translator_ai_with_google
            SubtitleTranslator.AI_ONLY -> R.string.settings_translator_ai_only
            SubtitleTranslator.ON_DEVICE -> R.string.settings_translator_on_device
        }

@get:StringRes
internal val SubtitleTranslator.descriptionRes: Int
    get() =
        when (this) {
            SubtitleTranslator.GOOGLE -> R.string.settings_translator_google_description
            SubtitleTranslator.AI_WITH_GOOGLE -> R.string.settings_translator_ai_with_google_description
            SubtitleTranslator.AI_ONLY -> R.string.settings_translator_ai_only_description
            SubtitleTranslator.ON_DEVICE -> R.string.settings_translator_on_device_description
        }
