package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PracticeSelectableTextUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val googleOpened = mutableListOf<String>()

    private fun show() {
        val controller = PhraseSelectionController()
        controller.actions = PhraseActions(openGoogleTranslate = { googleOpened += it })
        compose.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalPhraseSelection provides controller) {
                    PracticeSelectableText("Badeanzug", "de")
                }
            }
        }
    }

    @Test
    fun selectingPracticeTextShowsThePopupWithGoogleTranslateAndNoInAppTranslate() {
        show()
        compose.onNodeWithText("Badeanzug").performClick()

        compose.onNodeWithTag("phrase_action_bar").assertIsDisplayed()
        compose.onNodeWithTag("phrase_copy").assertIsDisplayed()
        compose.onNodeWithTag("phrase_pronounce").assertIsDisplayed()
        compose.onNodeWithTag("phrase_translate").assertDoesNotExist()
        compose.onNodeWithTag("phrase_google_translate").performClick()
        compose.runOnIdle { assertEquals(listOf("Badeanzug"), googleOpened) }
    }
}
