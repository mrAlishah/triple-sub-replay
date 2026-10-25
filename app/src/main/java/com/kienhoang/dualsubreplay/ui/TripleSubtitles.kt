package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

// Fork-only feature (docs/specs/2026-10-25-triple-subtitles.md): a third subtitle line kept beside, not
// inside, SubtitleSegment so the store format, the dual-mode pipeline and upstream merges stay untouched.

internal const val TRIPLE_SUBTITLES_PREFERENCE = "triple_subtitles_enabled"
internal const val THIRD_LANGUAGE_PREFERENCE = "third_language"
internal const val DEFAULT_THIRD_LANGUAGE = "fa"

/** The fork's default second language; upstream's default is Vietnamese. */
internal const val DEFAULT_SECOND_LANGUAGE = "en"
internal val TRIPLE_SETTING_KEYS = listOf(TRIPLE_SUBTITLES_PREFERENCE, THIRD_LANGUAGE_PREFERENCE)

/** The saved third language, or Persian when nothing valid is saved. */
internal fun storedThirdLanguage(raw: String?): String =
    raw?.let(TranslationLanguages::normalize)?.takeIf(TranslationLanguages::isSupported) ?: DEFAULT_THIRD_LANGUAGE

/**
 * The language to translate the third line into, or null when no request is needed: the slot is off, or
 * [third] would repeat a line that shows: the caption language [source], or the second line's [target]
 * while [secondShown].
 */
internal fun thirdLanguageToTranslate(
    enabled: Boolean,
    source: String?,
    target: String,
    third: String,
    secondShown: Boolean = true,
): String? {
    if (!enabled) return null
    val language = TranslationLanguages.normalize(third)
    val repeated = listOfNotNull(source, target.takeIf { secondShown }).map(TranslationLanguages::normalize)
    return language.takeIf { it !in repeated }
}

/** [thirdLanguageToTranslate] for the current settings and a caption track in [source]. */
internal fun DualSubUiState.thirdLanguageFor(source: String?): String? =
    thirdLanguageToTranslate(tripleSubtitlesEnabled, source, targetLanguage, thirdLanguage, translatedVisibility != CaptionVisibility.NEVER)

/**
 * Whether the third line shows: its checkbox is on, and, when the second line is not switched off, the
 * second line's play-mode and pause rules (listen first, hide until paused) apply to it too.
 */
internal fun DualSubUiState.showThird(): Boolean =
    tripleSubtitlesEnabled && (translatedVisibility == CaptionVisibility.NEVER || showTranslation())

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

/**
 * The three subtitle slots of the Subtitle languages dialog: line 1 original, line 2 second, line 3 third.
 * Original and second wrap the existing caption-visibility settings; third is the fork's own. Handed to the
 * dialog through [LocalTripleSubtitleControls] so the dialog's parameters and upstream call sites stay as they are.
 */
internal class TripleSubtitleControls(
    val originalEnabled: Boolean,
    val secondEnabled: Boolean,
    val enabled: Boolean,
    val language: String,
    val onOriginalEnabledChange: (Boolean) -> Unit,
    val onSecondEnabledChange: (Boolean) -> Unit,
    val onEnabledChange: (Boolean) -> Unit,
    val onLanguageChange: (String) -> Unit,
)

internal val LocalTripleSubtitleControls =
    staticCompositionLocalOf {
        TripleSubtitleControls(true, true, false, DEFAULT_THIRD_LANGUAGE, {}, {}, {}, {})
    }

/** One slot: a checkbox that shows or hides the line, and the button that opens its language list. */
@Composable
internal fun SubtitleSlotRow(
    @StringRes label: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    languageLabel: String,
    onPickLanguage: () -> Unit,
    tag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                .testTag("${tag}_checkbox"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label))
    }
    OutlinedButton(
        onClick = onPickLanguage,
        modifier = Modifier.fillMaxWidth().testTag("${tag}_picker"),
    ) {
        Text(languageLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A slot checkbox as a caption-visibility setting; unchecking and checking again returns to "always". */
internal fun slotVisibility(checked: Boolean): CaptionVisibility = if (checked) CaptionVisibility.ALWAYS else CaptionVisibility.NEVER
