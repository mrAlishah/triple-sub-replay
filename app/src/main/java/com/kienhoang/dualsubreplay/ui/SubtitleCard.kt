package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.translation.TranslationOrigin

@Composable
internal fun CompactSubtitleCard(
    segment: SubtitleSegment,
    active: Boolean,
    fontScale: Float,
    onReplay: () -> Unit,
    playing: Boolean = false,
    activeWordIndex: Int = -1,
    originalColor: Color = subtitleColor(DEFAULT_ORIGINAL_COLOR_KEY, appThemeIsLight()),
    translatedColor: Color = subtitleColor(DEFAULT_TRANSLATED_COLOR_KEY, appThemeIsLight()),
    highlightColor: Color = subtitleColor(DEFAULT_HIGHLIGHT_COLOR_KEY, appThemeIsLight()),
    wordLearningEnabled: Boolean = false,
    wordLearningTarget: String = DEFAULT_WORD_LEARNING_TARGET,
    wordLearningActiveOnly: Boolean = true,
    tapToLearnEnabled: Boolean = true,
    resolvedSourceLanguage: String? = null,
    targetLanguage: String = "vi",
    isDownloadingTranslationModel: Boolean = false,
    translationUnavailable: Boolean = false,
    onWordClick: (WordTap) -> Unit = {},
    replayEnabled: Boolean = true,
    showOriginal: Boolean = true,
    showTranslation: Boolean = true,
    translationPosition: TranslationPosition = TranslationPosition.BELOW,
    thirdText: String? = null,
    thirdOrigin: TranslationOrigin? = null,
    /** Goes before the row's click, for a focus requester (TV layout). */
    focusModifier: Modifier = Modifier,
    /** The loop button at the row's end (Study tools → Loop button on the playing line), or null for none. */
    loop: LineLoopButton? = null,
    /** Dictation: the line is hidden until revealed, and this (one dot per word) shows instead. */
    hiddenHint: String? = null,
) {
    if (hiddenHint == null && !showOriginal && !showTranslation && thirdText == null) return
    val slotControls = LocalTripleSubtitleControls.current
    val slotRtl = slotControls.rtl
    val rowState = stringResource(if (active) R.string.player_active_subtitle else R.string.player_subtitle)
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { stateDescription = rowState }
                .tvFocusRing(),
        border = activeCardBorder(active),
        colors = activeCardColors(active),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 8.dp,
                        vertical = 7.dp,
                    ).heightIn(min = if (replayEnabled) 60.dp else 0.dp)
                    .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (replayEnabled) {
                Box(Modifier.width(34.dp).fillMaxHeight()) {
                    Box(Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-5).dp)) {
                        SegmentRetranslateMenu(segment, showTranslation, thirdText != null)
                    }
                    Box(Modifier.align(Alignment.Center)) { ReplayButton(active, playing, onReplay, focusModifier) }
                }
            }
            if (replayEnabled) Spacer(Modifier.size(9.dp))
            if (hiddenHint != null) {
                HiddenLineText(hiddenHint, fontScale, Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f)) {
                    val isSentenceEligibleForPos = !wordLearningActiveOnly || active
                    slotControls.order.forEach { slot ->
                        when (slot) {
                            SubtitleSlot.ORIGINAL ->
                                if (showOriginal) {
                                    LineDirection(slotRtl.original) {
                                        OriginalCardText(
                                            segment,
                                            active,
                                            activeWordIndex,
                                            fontScale * slotControls.fontScales.original,
                                            originalColor,
                                            translatedColor,
                                            highlightColor,
                                            wordLearningEnabled,
                                            wordLearningTarget,
                                            isSentenceEligibleForPos,
                                            tapToLearnEnabled,
                                            resolvedSourceLanguage,
                                            targetLanguage,
                                            isDownloadingTranslationModel,
                                            onWordClick,
                                        )
                                    }
                                }
                            SubtitleSlot.SECOND ->
                                if (showTranslation) {
                                    LineDirection(slotRtl.second) {
                                        TranslatedCardText(
                                            segment,
                                            active,
                                            activeWordIndex,
                                            fontScale * slotControls.fontScales.second,
                                            originalColor,
                                            translatedColor,
                                            highlightColor,
                                            wordLearningEnabled,
                                            wordLearningTarget,
                                            isSentenceEligibleForPos,
                                            tapToLearnEnabled,
                                            resolvedSourceLanguage,
                                            targetLanguage,
                                            stringResource(pendingTranslationTextRes(isDownloadingTranslationModel, translationUnavailable)),
                                            onWordClick,
                                        )
                                    }
                                    if (segment.translatedText != null) {
                                        TranslationSourceTag(segment.translationOrigin, SubtitleSlot.SECOND, targetLanguage, translatedColor)
                                    }
                                }
                            SubtitleSlot.THIRD ->
                                thirdText?.let {
                                    val learn = wordLearningEnabled && tapToLearnEnabled
                                    ThirdLine(
                                        it,
                                        fontScale * slotControls.fontScales.third,
                                        translatedColor,
                                        learn,
                                        segment,
                                        highlightColor,
                                        onWordClick,
                                        {},
                                    )
                                    TranslationSourceTag(thirdOrigin, SubtitleSlot.THIRD, slotControls.language, translatedColor)
                                }
                        }
                    }
                }
            }
            loop?.let { LineLoopIconButton(it) }
        }
    }
}

/** A line Dictation hides: dots in place of its words, read out as "Hidden line". */
@Composable
private fun HiddenLineText(
    dots: String,
    fontScale: Float,
    modifier: Modifier,
) {
    val description = stringResource(R.string.dictation_hidden_line)
    Text(
        text = dots,
        modifier = modifier.semantics { contentDescription = description }.testTag("dictation_hidden_line"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = (16 * fontScale).sp,
        letterSpacing = 1.sp,
    )
}

/** The loop button of one transcript row: whether its line loops, and what a tap does. */
internal data class LineLoopButton(
    val looping: Boolean,
    val onToggle: () -> Unit,
)

/** A small repeat-one icon; on a filled accent circle while the line loops. */
@Composable
private fun LineLoopIconButton(button: LineLoopButton) {
    val description = stringResource(if (button.looping) R.string.player_stop_loop_line else R.string.player_loop_line)
    IconButton(
        onClick = button.onToggle,
        modifier = Modifier.padding(start = 4.dp).size(40.dp).testTag("line_loop_button"),
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .background(
                        if (button.looping) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.RepeatOne,
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = if (button.looping) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun activeCardBorder(active: Boolean) =
    BorderStroke(
        width = if (active) 2.dp else 1.dp,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
    )

@Composable
private fun activeCardColors(active: Boolean) =
    CardDefaults.outlinedCardColors(
        containerColor = if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )

@Composable
private fun ReplayButton(
    active: Boolean,
    playing: Boolean,
    onReplay: () -> Unit,
    focusModifier: Modifier,
) {
    Card(
        onClick = onReplay,
        modifier = focusModifier.size(34.dp),
        shape = CircleShape,
        colors =
            CardDefaults.cardColors(
                containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                if (active && playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(if (active && playing) R.string.playback_pause else R.string.player_replay_paragraph),
                modifier = Modifier.size(22.dp),
                tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun OriginalCardText(
    segment: SubtitleSegment,
    active: Boolean,
    activeWordIndex: Int,
    fontScale: Float,
    originalColor: Color,
    translatedColor: Color,
    highlightColor: Color,
    wordLearningEnabled: Boolean,
    wordLearningTarget: String,
    isSentenceEligibleForPos: Boolean,
    tapToLearnEnabled: Boolean,
    resolvedSourceLanguage: String?,
    targetLanguage: String,
    isDownloadingTranslationModel: Boolean,
    onWordClick: (WordTap) -> Unit,
) {
    val shouldHighlightPos =
        wordLearningEnabled && isSentenceEligibleForPos && (wordLearningTarget == "original" || wordLearningTarget == "both")
    val annotatedOriginal =
        rememberAnnotatedSubtitleText(
            text = segment.originalText,
            words = segment.words,
            activeWordIndex = if (active) activeWordIndex else -1,
            baseColor = originalColor,
            highlightColor = highlightColor,
            wordLearningEnabled = shouldHighlightPos,
            languageCode = resolvedSourceLanguage,
            markNewWords = true,
        )
    if (wordLearningEnabled && tapToLearnEnabled) {
        SelectableSubtitleText(
            text = segment.originalText,
            annotated = annotatedOriginal,
            style =
                TextStyle(
                    fontSize = (17 * fontScale).sp,
                    lineHeight = (22 * fontScale).sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = originalColor,
                ),
            languageCode = resolvedSourceLanguage,
            segment = segment,
            translated = false,
            highlightColor = highlightColor,
            onWordClick = onWordClick,
            onBlankTap = {},
        )
    } else {
        Text(
            text = annotatedOriginal,
            modifier = Modifier.lineSide(annotatedOriginal, LocalLayoutDirection.current),
            style = LocalTextStyle.current.inLineDirection(),
            fontSize = (17 * fontScale).sp,
            lineHeight = (22 * fontScale).sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = originalColor,
        )
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun TranslatedCardText(
    segment: SubtitleSegment,
    active: Boolean,
    activeWordIndex: Int,
    fontScale: Float,
    originalColor: Color,
    translatedColor: Color,
    highlightColor: Color,
    wordLearningEnabled: Boolean,
    wordLearningTarget: String,
    isSentenceEligibleForPos: Boolean,
    tapToLearnEnabled: Boolean,
    resolvedSourceLanguage: String?,
    targetLanguage: String,
    fallbackText: String,
    onWordClick: (WordTap) -> Unit,
) {
    val shouldHighlightTrans =
        wordLearningEnabled && isSentenceEligibleForPos && (wordLearningTarget == "translation" || wordLearningTarget == "both")
    val translatedText = segment.translatedText
    // Tokens only feed word colors and tap-to-learn, so skip tokenizing when neither is on.
    val needsOriginalTokens = shouldHighlightTrans || (wordLearningEnabled && tapToLearnEnabled)
    val originalTokens: List<AnalyzedToken> =
        remember(segment.originalText, resolvedSourceLanguage, needsOriginalTokens, tokenizerRevision()) {
            if (needsOriginalTokens) {
                LanguageAwareTokenizer.tokenize(segment.originalText, resolvedSourceLanguage)
            } else {
                emptyList()
            }
        }
    val annotatedTrans =
        if (shouldHighlightTrans && translatedText != null) {
            rememberAnnotatedSubtitleText(
                text = translatedText,
                words = emptyList(),
                activeWordIndex = -1,
                baseColor = translatedColor,
                highlightColor = highlightColor,
                wordLearningEnabled = true,
                languageCode = targetLanguage,
                alignedOriginalTokens = originalTokens,
            )
        } else {
            AnnotatedString(translatedText ?: fallbackText)
        }
    if (wordLearningEnabled && tapToLearnEnabled && translatedText != null) {
        SelectableSubtitleText(
            text = translatedText,
            annotated = annotatedTrans,
            style =
                TextStyle(
                    fontSize = (14 * fontScale).sp,
                    lineHeight = (18 * fontScale).sp,
                    color = translatedColor,
                ),
            languageCode = targetLanguage,
            segment = segment,
            translated = true,
            highlightColor = highlightColor,
            onWordClick = onWordClick,
            onBlankTap = {},
            alignedOriginalTokens = originalTokens,
        )
    } else {
        Text(
            text = annotatedTrans,
            modifier = Modifier.lineSide(annotatedTrans, LocalLayoutDirection.current),
            style = LocalTextStyle.current.inLineDirection(),
            fontSize = (14 * fontScale).sp,
            lineHeight = (18 * fontScale).sp,
            color = translatedColor,
        )
    }
}

/** What a row shows under its original line until its translation arrives. */
@StringRes
internal fun pendingTranslationTextRes(
    downloadingModel: Boolean,
    translationUnavailable: Boolean,
): Int =
    when {
        translationUnavailable -> R.string.player_translation_unavailable
        downloadingModel -> R.string.player_downloading_translation_model
        else -> R.string.player_translating
    }

/** The Play button of a card: pauses when this card's line is the one playing now, otherwise plays the line from its start. */
internal fun YouTubeWebController.replayOrPause(
    state: DualSubUiState,
    segment: SubtitleSegment,
) {
    val playingThis = !state.playbackPaused && state.segments.getOrNull(state.currentIndex)?.id == segment.id
    if (playingThis) pause() else replayFrom(segment.startMs / 1_000f)
}
