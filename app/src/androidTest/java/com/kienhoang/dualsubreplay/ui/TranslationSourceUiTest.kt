package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.translation.TranslationEvent
import com.kienhoang.dualsubreplay.translation.TranslationEventKind
import com.kienhoang.dualsubreplay.translation.TranslationOrigin
import com.kienhoang.dualsubreplay.translation.TranslationProvider
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/** The source label under a translated line follows the origin recorded for the text on screen. */
class TranslationSourceUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chatGpt = TranslationOrigin(TranslationProvider.CHATGPT, cached = false, model = "gpt-x")

    private fun show(
        info: TranslationSourceInfo,
        origin: () -> TranslationOrigin?,
    ) {
        composeRule.setContent {
            DualSubTheme {
                Surface {
                    CompositionLocalProvider(LocalTranslationSourceInfo provides info) {
                        Column {
                            TranslationSourceTag(origin(), SubtitleSlot.SECOND, "en", Color.White)
                            TranslationSourceTag(TranslationOrigin.GOOGLE, SubtitleSlot.THIRD, "fa", Color.White)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun nothingShowsWhileTheSettingIsOff() {
        show(TranslationSourceInfo(show = false)) { chatGpt }
        composeRule.onNodeWithTag("translation_source_second").assertDoesNotExist()
        composeRule.onNodeWithTag("translation_source_third").assertDoesNotExist()
    }

    @Test
    fun eachLineNamesItsOwnSource() {
        show(TranslationSourceInfo(show = true)) { chatGpt }
        composeRule.onNodeWithTag("translation_source_second").assertIsDisplayed().assertTextEquals("ChatGPT Subscription")
        composeRule.onNodeWithTag("translation_source_third").assertIsDisplayed().assertTextEquals("Google Translate")
    }

    @Test
    fun theLabelChangesWhenTheOriginOfTheTextChanges() {
        var origin by mutableStateOf<TranslationOrigin?>(TranslationOrigin.GOOGLE)
        show(TranslationSourceInfo(show = true)) { origin }
        composeRule.onNodeWithTag("translation_source_second").assertTextEquals("Google Translate")
        composeRule.runOnIdle { origin = chatGpt }
        composeRule.onNodeWithTag("translation_source_second").assertTextEquals("ChatGPT Subscription")
        composeRule.runOnIdle { origin = chatGpt.copy(cached = true) }
        composeRule.onNodeWithTag("translation_source_second").assertTextEquals("ChatGPT Subscription (cached)")
        composeRule.runOnIdle { origin = null }
        composeRule.onNodeWithTag("translation_source_second").assertTextEquals("Unknown")
    }

    @Test
    fun tappingTheLabelShowsTheModelAndTheRecentEventsOfThatLanguage() {
        val events =
            MutableStateFlow(
                listOf(
                    TranslationEvent(1, TranslationEventKind.AI_REQUEST, "en", chatGpt, count = 3),
                    TranslationEvent(2, TranslationEventKind.AI_FAILED, "en", chatGpt, count = 0, reason = "UNAVAILABLE"),
                    TranslationEvent(3, TranslationEventKind.FALLBACK, "fa", TranslationOrigin.GOOGLE),
                ),
            )
        show(TranslationSourceInfo(show = true, events = events)) { chatGpt }
        composeRule.onNodeWithTag("translation_source_second").performClick()
        composeRule.onNodeWithText("Model: gpt-x").assertIsDisplayed()
        composeRule.onNodeWithText("AI failed", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Showing Google Translate instead of the AI", substring = true).assertDoesNotExist()
    }
}
