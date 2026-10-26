package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.data.AnkiProblem
import com.kienhoang.dualsubreplay.data.AnkiSendResult
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PracticeSendToAnkiUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val word =
        SavedWord(
            id = "w1",
            word = "Verletzten",
            reading = null,
            wordLanguage = "de",
            meaningLanguage = "en",
            meaning = "injured",
            sentence = "Er kümmert sich um die Verletzten.",
            translatedSentence = "He takes care of the injured.",
            videoId = "abcdefghijk",
            startMs = 971_000,
            endMs = 974_000,
            translated = false,
            meanings = mapOf("fa" to "مجروح شد"),
            personalNotes = "my notes",
        )

    private fun show(
        canSend: Boolean = true,
        alreadySent: Boolean = false,
        prepare: suspend () -> SavedWord? = { word },
        send: suspend (SavedWord) -> AnkiSendResult,
    ) {
        composeRule.setContent {
            DualSubTheme {
                PracticeSendToAnki(word.id, canSend, alreadySent, { false }, prepare, send)
            }
        }
    }

    @Test
    fun anUnsentWordShowsTheButtonAndSendsTheSavedWordOnceWhileTheSendIsRunning() {
        val finish = CompletableDeferred<AnkiSendResult>()
        val sent = mutableListOf<SavedWord>()
        show {
            sent += it
            finish.await()
        }
        composeRule
            .onNodeWithTag("practice_send_to_anki")
            .assertIsEnabled()
            .assertTextContains("Send to AnkiDroid")
            .performClick()
        composeRule.onNodeWithTag("practice_send_progress").assertIsDisplayed()
        composeRule.onNodeWithTag("practice_send_to_anki").assertIsNotEnabled().performClick()
        finish.complete(AnkiSendResult(sent = 1))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("practice_send_to_anki").assertIsNotEnabled().assertTextContains("Sent to AnkiDroid")
        assertEquals(listOf(word), sent)
    }

    @Test
    fun theButtonStaysBusyWhenTheShownWordChangesWhileASendRuns() {
        val finish = CompletableDeferred<AnkiSendResult>()
        var calls = 0
        val shown = mutableStateOf("w1")
        composeRule.setContent {
            DualSubTheme {
                PracticeSendToAnki(
                    shown.value,
                    true,
                    false,
                    { false },
                    { word },
                    {
                        calls++
                        finish.await()
                    },
                )
            }
        }
        composeRule.onNodeWithTag("practice_send_to_anki").performClick()
        composeRule.runOnIdle { shown.value = "w2" }
        composeRule.onNodeWithTag("practice_send_to_anki").assertIsNotEnabled().performClick()
        finish.complete(AnkiSendResult(sent = 1))
        composeRule.waitForIdle()
        assertEquals(1, calls)
    }

    @Test
    fun aFailedSendShowsTheStepAndAllowsATryAgain() {
        show { AnkiSendResult(problem = AnkiProblem.INSERT_FAILED) }
        composeRule.onNodeWithTag("practice_send_to_anki").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("practice_send_problem").assertIsDisplayed()
        composeRule.onNodeWithTag("practice_send_to_anki").assertIsEnabled().assertTextContains("Send to AnkiDroid")
    }

    @Test
    fun aWordSentBeforeShowsSentAndCannotBeSentAgain() {
        show(alreadySent = true) { AnkiSendResult(sent = 1) }
        composeRule.onNodeWithTag("practice_send_to_anki").assertIsNotEnabled().assertTextContains("Sent to AnkiDroid")
    }

    @Test
    fun aBlankMeaningCannotBeSent() {
        show(canSend = false) { AnkiSendResult(sent = 1) }
        composeRule.onNodeWithTag("practice_send_to_anki").assertIsNotEnabled()
    }

    @Test
    fun aWordDeletedMeanwhileIsNotSent() {
        var calls = 0
        show(prepare = { null }) {
            calls++
            AnkiSendResult(sent = 1)
        }
        composeRule.onNodeWithTag("practice_send_to_anki").performClick()
        composeRule.waitForIdle()
        assertEquals(0, calls)
    }
}
