package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.*
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SavedWordsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun practiceRevealsMeaningAndPersistsRatingWithoutAnyClips() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "vocabulary-ui-${UUID.randomUUID()}.db"
        val repository = VocabularyRepository(context, name)
        val visible = androidx.compose.runtime.mutableStateOf(true)
        val word = savedWordFrom(LearningWordSelection(AnalyzedToken("learn", 0, 5, PartOfSpeech.VERB), "en", "vi",
            "dQw4w9WgXcQ", SubtitleSegment(1, 1000, 3000, "Learn a word", "Học một từ"), false), "học", false)
        runBlocking { repository.save(word) }
        try {
            compose.setContent { DualSubTheme { if (visible.value) SavedWordsScreen(repository, {}, {}, {}) } }
            compose.waitForIdle()
            saveUiEvidence("saved-words")
            compose.onNodeWithTag("practice_words").performClick()
            compose.onNodeWithTag("review_meaning").assertDoesNotExist()
            compose.onNodeWithTag("reveal_meaning").performClick()
            compose.onNodeWithTag("review_meaning").assertTextEquals("học")
            compose.onNodeWithTag("play_online_clip").assertDoesNotExist()
            compose.onNodeWithTag("play_offline_clip").assertDoesNotExist()
            saveUiEvidence("word-practice")
            compose.onNodeWithTag("review_good").performScrollTo().performClick()
            compose.waitUntil(5000) { repository.words.value.single().intervalMs == 3 * DAY_MS }
            compose.onNodeWithTag("practice_complete").assertIsDisplayed()
            assertTrue(repository.words.value.single().dueAt > System.currentTimeMillis())
        } finally {
            // Dispose the screen before closing the isolated test database.
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            repository.close(); context.deleteDatabase(name)
        }
    }

    private fun saved(id: String, text: String, videoId: String?, title: String?) =
        SavedWord(id, text, null, "de", "en", "meaning $text", "sentence", null, videoId, 1000, 3000, false, false, sourceTitle = title)

    @Test fun sourceFilterNarrowsTheListAndTheDueQueueAndTitlesCanBeCorrected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "vocabulary-ui-${UUID.randomUUID()}.db"
        val repository = VocabularyRepository(context, name)
        val visible = androidx.compose.runtime.mutableStateOf(true)
        runBlocking {
            repository.save(saved("a1", "Eins", "aaaaaaaaaaa", "Lesson"))
            repository.save(saved("a2", "Zwei", "aaaaaaaaaaa", "Lesson"))
            repository.save(saved("b1", "Drei", "bbbbbbbbbbb", "Lesson"))
            repository.save(saved("u1", "Vier", null, null))
        }
        try {
            compose.setContent { DualSubTheme { if (visible.value) SavedWordsScreen(repository, {}, {}, {}) } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("saved_word_u1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("source_filter").performClick()
            compose.onNodeWithTag("source_search").performTextInput("lesson")
            compose.onNodeWithTag("source_v:aaaaaaaaaaa").assertExists()
            compose.onNodeWithTag("source_v:bbbbbbbbbbb").assertExists()
            compose.onNodeWithTag("source_unknown").assertDoesNotExist()
            compose.onNodeWithTag("source_v:aaaaaaaaaaa").performClick()
            compose.onNodeWithTag("saved_word_a1").assertExists()
            compose.onNodeWithTag("saved_word_a2").assertExists()
            compose.onNodeWithTag("saved_word_b1").assertDoesNotExist()
            compose.onNodeWithTag("saved_word_u1").assertDoesNotExist()
            saveUiEvidence("saved-words-source-filter")
            compose.onNodeWithTag("practice_words").performClick()
            repeat(2) {
                compose.onNodeWithTag("practice_source").assertExists()
                compose.onNodeWithTag("reveal_meaning").performClick()
                compose.onNodeWithTag("review_good").performScrollTo().performClick()
                compose.waitForIdle()
            }
            compose.waitUntil(5000) { repository.words.value.filter { it.videoId == "aaaaaaaaaaa" }.all { it.intervalMs == 3 * DAY_MS } }
            compose.onNodeWithTag("practice_complete").assertIsDisplayed()
            assertTrue(repository.words.value.filter { it.id in setOf("b1", "u1") }.all { it.intervalMs == 0L && it.dueAt == 0L })
            compose.onNodeWithText("Back to saved words").performClick()
            compose.onNodeWithTag("saved_word_a1").performClick()
            compose.onNodeWithTag("source_title_field").performTextReplacement("Renamed lesson")
            compose.onNodeWithTag("save_source_title").performScrollTo().performClick()
            compose.waitUntil(5000) { repository.words.value.filter { it.videoId == "aaaaaaaaaaa" }.all { it.sourceTitle == "Renamed lesson" } }
            assertEquals("Lesson", repository.words.value.first { it.id == "b1" }.sourceTitle)
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            repository.close(); context.deleteDatabase(name)
        }
    }
}
