package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.translation.TranslationOrigin
import com.kienhoang.dualsubreplay.translation.TranslationProvider
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** "Re-translate with AI": the dialog, the overlay button, the marks under the lines and the source label. */
class RetranslateUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val starts = mutableListOf<Pair<Set<SubtitleSlot>, RefreshScope>>()
    private var stops = 0
    private val available = MutableStateFlow(setOf(SubtitleSlot.SECOND, SubtitleSlot.THIRD))
    private val state = MutableStateFlow(RefreshState())

    private val actions =
        RefreshActions(
            available = available,
            state = state,
            estimate = { 130 },
            start = { slots, scope -> starts += slots to scope },
            cancel = { stops++ },
        )

    private val chatGpt = TranslationOrigin(TranslationProvider.CHATGPT, cached = false, model = "gpt-x")

    private fun show(
        showSource: Boolean = true,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        composeRule.setContent {
            DualSubTheme {
                Surface {
                    CompositionLocalProvider(LocalTranslationSourceInfo provides TranslationSourceInfo(showSource, refresh = actions)) {
                        Column { content() }
                    }
                }
            }
        }
    }

    @Test
    fun theDialogDefaultsToTheOpenedLineAndTheCurrentSentence() {
        show { RetranslateDialog(SubtitleSlot.THIRD, actions) {} }
        composeRule.onNodeWithTag("retranslate_slot_second").assertIsDisplayed()
        composeRule.onNodeWithTag("retranslate_start").assertIsEnabled().performClick()
        // Opened from Subtitle 2: that line only, the current sentence.
        assertEquals(listOf(setOf(SubtitleSlot.THIRD) to RefreshScope.SENTENCE), starts)
        saveUiEvidence("retranslate-dialog", composeRule.onRoot().captureToImage())
    }

    @Test
    fun bothLinesAndTheWindowCanBeChosen() {
        show { RetranslateDialog(SubtitleSlot.SECOND, actions) {} }
        composeRule.onNodeWithTag("retranslate_slot_both").performClick()
        composeRule.onNodeWithTag("retranslate_scope_window").performClick()
        composeRule.onNodeWithTag("retranslate_start").performClick()
        assertEquals(listOf(setOf(SubtitleSlot.SECOND, SubtitleSlot.THIRD) to RefreshScope.WINDOW), starts)
    }

    @Test
    fun aLineThatDoesNotUseTheAiCannotBeChosen() {
        available.value = setOf(SubtitleSlot.SECOND)
        show { RetranslateDialog(SubtitleSlot.SECOND, actions) {} }
        composeRule.onNodeWithTag("retranslate_slot_second").assertIsNotEnabled()
        composeRule.onNodeWithTag("retranslate_slot_both").assertIsNotEnabled()
        composeRule.onNodeWithTag("retranslate_slot_first").assertIsEnabled()
    }

    @Test
    fun theEntireVideoNeedsAnExplicitConfirmationAndShowsTheEstimate() {
        show { RetranslateDialog(SubtitleSlot.SECOND, actions) {} }
        composeRule.onNodeWithTag("retranslate_scope_video").performClick()
        composeRule.onNodeWithTag("retranslate_video_warning").assertIsDisplayed()
        composeRule.onNodeWithText("130", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("retranslate_start").assertIsNotEnabled()
        composeRule.onNodeWithTag("retranslate_confirm").performClick()
        composeRule.onNodeWithTag("retranslate_start").assertIsEnabled().performClick()
        assertEquals(listOf(setOf(SubtitleSlot.SECOND) to RefreshScope.VIDEO), starts)
    }

    @Test
    fun whileItRunsTheDialogShowsProgressAndCanBeStopped() {
        state.value = RefreshState(running = setOf(SubtitleSlot.SECOND), done = 12, total = 40)
        show { RetranslateDialog(SubtitleSlot.SECOND, actions) {} }
        composeRule.onNodeWithTag("retranslate_progress").assertIsDisplayed()
        composeRule.onNodeWithTag("retranslate_start").assertDoesNotExist()
        composeRule.onNodeWithTag("retranslate_stop").performClick()
        assertEquals(1, stops)
    }

    @Test
    fun aFailureIsShownAndTheEarlierSubtitlesAreSaidToStay() {
        state.value = RefreshState(failed = mapOf(SubtitleSlot.SECOND to "USAGE_LIMIT"))
        show { RetranslateDialog(SubtitleSlot.SECOND, actions) {} }
        composeRule.onNodeWithTag("retranslate_failure").assertIsDisplayed()
        composeRule.onNodeWithText("USAGE_LIMIT", substring = true).assertIsDisplayed()
    }

    @Test
    fun theOverlayButtonIsFoundWithoutTheDebugLabels() {
        show(showSource = false) { RetranslateButton(actions) }
        composeRule.onNodeWithTag("retranslate_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("retranslate_start").assertIsDisplayed()
    }

    @Test
    fun theButtonIsAlwaysThereAndTheDialogSaysWhatIsNeededWhenNoLineUsesTheAi() {
        available.value = emptySet()
        show(showSource = false) { RetranslateButton(actions) }
        composeRule.onNodeWithTag("retranslate_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Connect an AI provider first", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("retranslate_start").assertIsNotEnabled()
    }

    @Test
    fun aRefreshShowsAMarkUnderTheLineWithoutChangingTheSourceLabel() {
        show(showSource = true) {
            TranslationSourceTag(chatGpt, SubtitleSlot.SECOND, "en", Color.White)
            TranslationSourceTag(TranslationOrigin.GOOGLE, SubtitleSlot.THIRD, "fa", Color.White)
        }
        composeRule.onNodeWithTag("refreshing_second").assertDoesNotExist()
        state.value = RefreshState(running = setOf(SubtitleSlot.SECOND))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("refreshing_second").assertIsDisplayed()
        composeRule.onNodeWithTag("refreshing_third").assertDoesNotExist()
        // The label still names what is on screen: a refresh in progress changes nothing about it.
        composeRule.onNodeWithTag("translation_source_second").assertIsDisplayed()
        composeRule.onNodeWithText("ChatGPT Subscription").assertIsDisplayed()
        state.value = RefreshState(failed = mapOf(SubtitleSlot.SECOND to "UNAVAILABLE"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("refresh_failed_second").assertIsDisplayed()
        saveUiEvidence("retranslate-marks", composeRule.onRoot().captureToImage())
    }

    @Test
    fun theMarkShowsEvenWhenTheDebugLabelsAreOff() {
        show(showSource = false) { TranslationSourceTag(chatGpt, SubtitleSlot.SECOND, "en", Color.White) }
        composeRule.onNodeWithTag("translation_source_second").assertDoesNotExist()
        state.value = RefreshState(running = setOf(SubtitleSlot.SECOND))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("refreshing_second").assertIsDisplayed()
    }

    @Test
    fun theLabelDetailsOfferReTranslateOnlyForALineThatUsesTheAi() {
        show { TranslationSourceTag(chatGpt, SubtitleSlot.SECOND, "en", Color.White) }
        composeRule.onNodeWithTag("translation_source_second").performClick()
        composeRule.onNodeWithTag("details_retranslate").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("retranslate_start").assertIsDisplayed()
    }
}
