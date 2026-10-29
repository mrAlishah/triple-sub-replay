package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.data.learningSourceLanguage
import com.kienhoang.dualsubreplay.translation.Translated
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import com.kienhoang.dualsubreplay.translation.TranslationOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

// Fork-only feature (docs/specs/2026-10-25-triple-subtitles.md): a third subtitle line kept beside, not
// inside, SubtitleSegment so the store format, the dual-mode pipeline and upstream merges stay untouched.

internal const val TRIPLE_SUBTITLES_PREFERENCE = "triple_subtitles_enabled"
internal const val THIRD_LANGUAGE_PREFERENCE = "third_language"
internal const val DEFAULT_THIRD_LANGUAGE = "fa"

/** The fork's default second language; upstream's default is Vietnamese. */
internal const val DEFAULT_SECOND_LANGUAGE = "en"

/** The three subtitle lines, top to bottom; [rtlPreference] saves each line's RTL checkbox. */
enum class SubtitleSlot(
    val rtlPreference: String,
) {
    ORIGINAL("rtl_original"),
    SECOND("rtl_second"),
    THIRD("rtl_third"),
}

/** Which lines are forced right-to-left. Off means automatic: the text's own script decides, as in upstream. */
data class SlotRtl(
    val original: Boolean = false,
    val second: Boolean = false,
    val third: Boolean = true,
) {
    operator fun get(slot: SubtitleSlot): Boolean =
        when (slot) {
            SubtitleSlot.ORIGINAL -> original
            SubtitleSlot.SECOND -> second
            SubtitleSlot.THIRD -> third
        }

    fun with(
        slot: SubtitleSlot,
        rtl: Boolean,
    ): SlotRtl =
        when (slot) {
            SubtitleSlot.ORIGINAL -> copy(original = rtl)
            SubtitleSlot.SECOND -> copy(second = rtl)
            SubtitleSlot.THIRD -> copy(third = rtl)
        }
}

internal fun storedSlotRtl(preferences: SharedPreferences): SlotRtl {
    val defaults = SlotRtl()
    return SubtitleSlot.entries.fold(defaults) { rtl, slot ->
        rtl.with(slot, preferences.getBoolean(slot.rtlPreference, defaults[slot]))
    }
}

internal val TRIPLE_SETTING_KEYS =
    listOf(TRIPLE_SUBTITLES_PREFERENCE, THIRD_LANGUAGE_PREFERENCE) + SubtitleSlot.entries.map { it.rtlPreference } +
        SLOT_LAYOUT_SETTING_KEYS

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
 * The subtitle languages a tapped word should also get a meaning in, beside the [primary] one, in slot
 * order (caption language, second line, third line): without the word's own language, the primary
 * language and repeats. [third] is [thirdLanguageFor]'s answer, so an off or repeated slot never appears.
 */
internal fun extraMeaningLanguages(
    wordLanguage: String,
    primary: String,
    source: String,
    target: String,
    secondShown: Boolean,
    third: String?,
): List<String> {
    val skip = setOf(wordLanguage, primary).map(TranslationLanguages::normalize)
    return listOfNotNull(source, target.takeIf { secondShown }, third)
        .map(TranslationLanguages::normalize)
        .distinct()
        .filterNot { it in skip }
}

internal fun DualSubUiState.extraMeaningLanguagesFor(selection: LearningWordSelection): List<String> =
    extraMeaningLanguagesFor(selection.wordLanguage, selection.meaningLanguage)

/** [word] with only the meanings and sentence translations of the subtitle languages that are on now, as the word card sends it. */
internal fun SavedWord.withActiveLanguages(state: DualSubUiState): SavedWord {
    val active = state.extraMeaningLanguagesFor(wordLanguage, meaningLanguage)
    return copy(
        meanings = meanings.filterKeys { TranslationLanguages.normalize(it) in active },
        sentenceTranslations = sentenceTranslations.filterKeys { TranslationLanguages.normalize(it) in active },
    )
}

private fun DualSubUiState.extraMeaningLanguagesFor(
    wordLanguage: String,
    primary: String,
): List<String> {
    val source = learningSourceLanguage(resolvedSourceLanguage, sourcePreference)
    return extraMeaningLanguages(
        wordLanguage = wordLanguage,
        primary = primary,
        source = source,
        target = targetLanguage,
        secondShown = translatedVisibility != CaptionVisibility.NEVER,
        third = thirdLanguageFor(source),
    )
}

/**
 * Whether the third line shows: its checkbox is on, and, when the second line is not switched off, the
 * second line's play-mode and pause rules (listen first, hide until paused) apply to it too.
 */
internal fun DualSubUiState.showThird(): Boolean =
    tripleSubtitlesEnabled && (translatedVisibility == CaptionVisibility.NEVER || showTranslation())

data class ThirdLines(
    val videoId: String? = null,
    val lines: Map<Long, String> = emptyMap(),
    /** The language [lines] were translated into; null until the run starts. */
    val language: String? = null,
    /** Who wrote each of [lines] (diagnostics); a line without an entry reads as unknown. */
    val origins: Map<Long, TranslationOrigin> = emptyMap(),
) {
    fun textFor(
        activeVideoId: String?,
        segmentId: Long,
    ): String? = if (activeVideoId != null && activeVideoId == videoId) lines[segmentId] else null

    fun originFor(
        activeVideoId: String?,
        segmentId: Long,
    ): TranslationOrigin? = if (activeVideoId != null && activeVideoId == videoId) origins[segmentId] else null
}

internal fun thirdSentenceTranslations(
    third: ThirdLines,
    activeVideoId: String?,
    segment: SubtitleSegment?,
    source: String,
    target: String,
): Map<String, String> {
    val language = third.language ?: return emptyMap()
    val text = segment?.let { third.textFor(activeVideoId, it.id) }?.takeIf(String::isNotBlank) ?: return emptyMap()
    return if (language == source || language == target) emptyMap() else mapOf(language to text)
}

/** A page title together with the video of the address it was reported for. */
data class VideoTitle(
    val videoId: String,
    val title: String,
)

/** The title of [videoId] when the page reported it for that same video; null for any other video (never a stale one). */
internal fun DualSubUiState.titleOf(videoId: String?): String? = videoTitle?.takeIf { videoId != null && it.videoId == videoId }?.title

internal fun DualSubUiState.thirdSentenceFor(selection: LearningWordSelection): Map<String, String> {
    val source = learningSourceLanguage(resolvedSourceLanguage, sourcePreference)
    return thirdSentenceTranslations(thirdLines, activeVideoId, selection.segment, source, targetLanguage)
}

/** The third line of row [segmentId], only while it is in the language now chosen (a changed choice hides the old lines at once). */
internal fun DualSubUiState.thirdLineFor(segmentId: Long): String? =
    thirdLines.takeIf { it.language == thirdLanguage }?.textFor(activeVideoId, segmentId)

/** Who wrote the third line of row [segmentId] (same language rule as [thirdLineFor]); null when not recorded. */
internal fun DualSubUiState.thirdOriginFor(segmentId: Long): TranslationOrigin? =
    thirdLines.takeIf { it.language == thirdLanguage }?.originFor(activeVideoId, segmentId)

/**
 * Translates the rows near playback into the third language with the same window loop as the second
 * line, and hands the finished lines to [publish]. [prefetch] and [upgrades] are the second line's AI hooks.
 * A translator error only stops this line: the loop reports it to nobody, so the original and the second
 * line never learn of it. Cancellation propagates.
 */
internal suspend fun runThirdLanguage(
    store: SubtitleStore,
    requests: StateFlow<CaptionPlaybackRequest>,
    translate: suspend (String) -> String,
    prefetch: (suspend (List<String>) -> Unit)? = null,
    upgrades: TranslationUpgrades? = null,
    publish: (Map<Long, String>) -> Unit,
) = runThirdLanguageTracked(store, requests, { text -> Translated(translate(text), null) }, prefetch, upgrades) { lines, _ ->
    publish(lines)
}

/** [runThirdLanguage] where each line says who wrote it, handed to [publish] beside the lines (by row id). */
internal suspend fun runThirdLanguageTracked(
    store: SubtitleStore,
    requests: StateFlow<CaptionPlaybackRequest>,
    translate: suspend (String) -> Translated,
    prefetch: (suspend (List<String>) -> Unit)? = null,
    upgrades: TranslationUpgrades? = null,
    publish: (Map<Long, String>, Map<Long, TranslationOrigin>) -> Unit,
) {
    try {
        translatePlaybackWindowTracked(store, requests, translate, prefetch = prefetch, upgrades = upgrades) { rows, _ ->
            val shown = rows.filter { !it.translatedText.isNullOrBlank() }
            publish(
                shown.associate { it.id to it.translatedText.orEmpty() },
                shown.mapNotNull { row -> row.translationOrigin?.let { row.id to it } }.toMap(),
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // The third line is optional; the loop already stopped translating on its own errors.
    }
}

/**
 * Lays a line out right-to-left when its RTL checkbox is on: flush right, words flowing from the right.
 * Off leaves the layout alone, so the text's own script still decides, as in upstream.
 */
@Composable
internal fun LineDirection(
    rtl: Boolean,
    content: @Composable () -> Unit,
) {
    if (!rtl) return content()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxWidth()) { content() }
    }
}

/** [line] wrapped in [LineDirection]; a hidden line stays null so `subtitleLineOrder` still leaves it out. */
internal fun withDirection(
    rtl: Boolean,
    line: (@Composable () -> Unit)?,
): (@Composable () -> Unit)? = line?.let { inner -> @Composable { LineDirection(rtl, inner) } }

/** Draws each subtitle line (true marks the second) in the direction its slot's RTL checkbox asks for. */
@Composable
internal fun List<Boolean>.forEachDirected(
    rtl: SlotRtl,
    line: @Composable (Boolean) -> Unit,
) = forEach { second -> LineDirection(if (second) rtl.second else rtl.original) { line(second) } }

/** The third line; its RTL checkbox decides the direction (on by default, for Persian). Tappable when [tap] is set. */
@Composable
internal fun ThirdLineText(
    text: String,
    fontScale: Float,
    color: Color,
    modifier: Modifier = Modifier,
    tap: ThirdTap? = null,
) {
    val thirdAlignment = LocalTripleSubtitleControls.current.alignment.third
    val style =
        TextStyle(
            fontSize = (14f * fontScale).sp,
            lineHeight = (18f * fontScale).sp,
            color = color,
            textAlign = thirdAlignment.textAlign(),
        )
    LineDirection(LocalTripleSubtitleControls.current.rtl.third) {
        if (tap != null) {
            SelectableSubtitleText(
                text = text,
                annotated = AnnotatedString(text),
                style = style,
                languageCode = tap.language,
                segment = tap.segment,
                translated = true,
                highlightColor = tap.highlightColor,
                onWordClick = tap.onWordClick,
                onBlankTap = tap.onBlankTap,
                modifier = modifier.fillMaxWidth().testTag("third_subtitle_line"),
                overflow = TextOverflow.Ellipsis,
                lineLanguage = tap.language,
            )
        } else {
            Text(
                text = text,
                modifier = modifier.fillMaxWidth().testTag("third_subtitle_line"),
                style = style,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The third line as the card and the overlay place it: below the others, tappable while [tappable] (word learning and tap to learn). */
@Composable
internal fun ThirdLine(
    text: String,
    fontScale: Float,
    color: Color,
    tappable: Boolean,
    segment: SubtitleSegment?,
    highlightColor: Color,
    onWordClick: (WordTap) -> Unit,
    onBlankTap: () -> Unit,
) {
    val language = LocalTripleSubtitleControls.current.language
    val tap = thirdTapOrNull(tappable, language, segment, highlightColor, onWordClick, onBlankTap)
    ThirdLineText(text, fontScale, color, Modifier.padding(top = 2.dp), tap)
}

/** Makes the third line tappable: the same tap-to-learn bar as the other lines, in the third language. */
internal class ThirdTap(
    val language: String,
    val segment: SubtitleSegment?,
    val highlightColor: Color,
    val onWordClick: (WordTap) -> Unit,
    val onBlankTap: () -> Unit,
)

internal fun thirdTapOrNull(
    enabled: Boolean,
    language: String,
    segment: SubtitleSegment?,
    highlightColor: Color,
    onWordClick: (WordTap) -> Unit,
    onBlankTap: () -> Unit,
): ThirdTap? = if (enabled) ThirdTap(language, segment, highlightColor, onWordClick, onBlankTap) else null

/**
 * The three subtitle slots of the Subtitle languages dialog: line 1 original, line 2 second, line 3 third.
 * Original and second wrap the existing caption-visibility settings; third is the fork's own. Handed to the
 * dialog and to the lines through [LocalTripleSubtitleControls] so no upstream signature has to change.
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
    val rtl: SlotRtl = SlotRtl(),
    val onRtlChange: (SubtitleSlot, Boolean) -> Unit = { _, _ -> },
    /** Top to bottom; a permutation of all three slots. */
    val order: List<SubtitleSlot> = DEFAULT_SUBTITLE_ORDER,
    val onMove: (SubtitleSlot, Int) -> Unit = { _, _ -> },
    val fontScales: SlotFontScale = SlotFontScale(),
    val onFontScaleChange: (SubtitleSlot, Float) -> Unit = { _, _ -> },
    val alignment: SlotAlignment = SlotAlignment(),
    val onAlignmentChange: (SubtitleSlot, SubtitleAlignment) -> Unit = { _, _ -> },
)

internal val LocalTripleSubtitleControls =
    staticCompositionLocalOf {
        TripleSubtitleControls(true, true, false, DEFAULT_THIRD_LANGUAGE, {}, {}, {}, {})
    }

/** One slot: a checkbox that shows or hides the line, an RTL checkbox, and the button that opens its language list. */
@Composable
internal fun SubtitleSlotRow(
    slot: SubtitleSlot,
    @StringRes label: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    languageLabel: String,
    onPickLanguage: () -> Unit,
    tag: String,
    languageHint: String? = null,
) {
    val controls = LocalTripleSubtitleControls.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                    .testTag("${tag}_checkbox"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(slotRoleLabel(slot, languageHint))
        }
        Row(
            modifier =
                Modifier
                    .toggleable(
                        value = controls.rtl[slot],
                        role = Role.Checkbox,
                        onValueChange = { controls.onRtlChange(slot, it) },
                    ).testTag("${tag}_rtl"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = controls.rtl[slot], onCheckedChange = null)
            Text(stringResource(R.string.settings_slot_rtl))
        }
    }
    OutlinedButton(
        onClick = onPickLanguage,
        modifier = Modifier.fillMaxWidth().testTag("${tag}_picker"),
    ) {
        Text(languageLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    SlotLayoutControls(slot, tag)
}

/** A slot checkbox as a caption-visibility setting; unchecking and checking again returns to "always". */
internal fun slotVisibility(checked: Boolean): CaptionVisibility = if (checked) CaptionVisibility.ALWAYS else CaptionVisibility.NEVER
