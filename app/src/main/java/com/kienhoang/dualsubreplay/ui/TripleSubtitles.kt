package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

// Fork-only feature (docs/specs/2026-10-25-triple-subtitles.md): a third subtitle line kept beside, not
// inside, SubtitleSegment so the store format, the dual-mode pipeline and upstream merges stay untouched.

internal const val TRIPLE_SUBTITLES_PREFERENCE = "triple_subtitles_enabled"
internal const val THIRD_LANGUAGE_PREFERENCE = "third_language"
internal const val DEFAULT_THIRD_LANGUAGE = "fa"
internal val TRIPLE_SETTING_KEYS = listOf(TRIPLE_SUBTITLES_PREFERENCE, THIRD_LANGUAGE_PREFERENCE)

/** The saved third language, or Persian when nothing valid is saved. */
internal fun storedThirdLanguage(raw: String?): String =
    raw?.let(TranslationLanguages::normalize)?.takeIf(TranslationLanguages::isSupported) ?: DEFAULT_THIRD_LANGUAGE

/**
 * The language to translate the third line into, or null when no request is needed: the mode is off, or
 * [third] would repeat the caption language [source] or the second line's [target].
 */
internal fun thirdLanguageToTranslate(
    enabled: Boolean,
    source: String?,
    target: String,
    third: String,
): String? {
    if (!enabled) return null
    val language = TranslationLanguages.normalize(third)
    val repeated = listOfNotNull(source, target).map(TranslationLanguages::normalize)
    return language.takeIf { it !in repeated }
}

/** [thirdLanguageToTranslate] for the current settings and a caption track in [source]. */
internal fun DualSubUiState.thirdLanguageFor(source: String?): String? =
    thirdLanguageToTranslate(tripleSubtitlesEnabled, source, targetLanguage, thirdLanguage)

/** Third-line texts by row id for [videoId]; ids are store indices, so they mean nothing for another video. */
data class ThirdLines(
    val videoId: String? = null,
    val lines: Map<Long, String> = emptyMap(),
) {
    fun textFor(
        activeVideoId: String?,
        segmentId: Long,
    ): String? = if (activeVideoId != null && activeVideoId == videoId) lines[segmentId] else null
}

/**
 * Translates the rows near playback into the third language with the same window loop as the second
 * line, and hands the finished lines to [publish]. A translator error only stops this line: the loop
 * reports it to nobody, so the original and the second line never learn of it. Cancellation propagates.
 */
internal suspend fun runThirdLanguage(
    store: SubtitleStore,
    requests: StateFlow<CaptionPlaybackRequest>,
    translate: suspend (String) -> String,
    publish: (Map<Long, String>) -> Unit,
) {
    try {
        translatePlaybackWindow(store, requests, translate) { rows, _ ->
            publish(rows.mapNotNull { row -> row.translatedText?.takeIf(String::isNotBlank)?.let { row.id to it } }.toMap())
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // The third line is optional; the loop already stopped translating on its own errors.
    }
}

/**
 * The third line. `TextDirection.Content` lets the text's own first strong letter decide, so Persian is laid
 * out right-to-left and flush right while German and English stay left-to-right, whatever the app language.
 */
@Composable
internal fun ThirdLineText(
    text: String,
    fontScale: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().testTag("third_subtitle_line"),
        style =
            TextStyle(
                fontSize = (14f * fontScale).sp,
                lineHeight = (18f * fontScale).sp,
                color = color,
                textDirection = TextDirection.Content,
            ),
        overflow = TextOverflow.Ellipsis,
    )
}

/** The mode's switch, language and their setters, handed to Settings → Translation without widening the dialog's parameters. */
internal class TripleSubtitleControls(
    val enabled: Boolean,
    val language: String,
    val onEnabledChange: (Boolean) -> Unit,
    val onLanguageChange: (String) -> Unit,
)

internal val LocalTripleSubtitleControls = staticCompositionLocalOf<TripleSubtitleControls?> { null }

/** Settings → Translation → Triple subtitles, and its Third language list while on. */
@Composable
internal fun TripleSubtitleSettings(controls: TripleSubtitleControls) {
    SettingsSwitchRow(
        title = stringResource(R.string.settings_triple_title),
        description = stringResource(R.string.settings_triple_description),
        checked = controls.enabled,
        onCheckedChange = controls.onEnabledChange,
        testTag = "triple_subtitles_switch",
    )
    if (!controls.enabled) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.settings_triple_language_title))
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("third_language_button")) {
            Text(TranslationLanguages.displayName(controls.language))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TranslationLanguages.all.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        open = false
                        controls.onLanguageChange(option.code)
                    },
                    modifier = Modifier.testTag("third_language_${option.code}"),
                )
            }
        }
    }
}
