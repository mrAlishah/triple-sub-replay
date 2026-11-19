package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Subtitle 1 and Subtitle 2 meanings in the saved-word view: each on its own row, none under another or the speaker. */
class PracticeMeaningsUiTest {
    @get:Rule val compose = createComposeRule()

    private val word =
        SavedWord(
            id = "1",
            word = "Verletzten",
            reading = null,
            wordLanguage = "de",
            meaningLanguage = "en",
            meaning = "injured, a long meaning that has to wrap on a narrow phone screen instead of running under the button",
            sentence = "Er kümmert sich um die Verletzten.",
            translatedSentence = null,
            videoId = null,
            startMs = 0,
            endMs = 0,
            translated = false,
            meanings = mapOf("fa" to "مجروح شد، یک معنی بلند که باید در چند خط بشکند", "ar" to "جريح"),
        )

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertStacked(vararg tags: String) {
        tags.toList().zipWithNext().forEach { (above, below) ->
            assertTrue("$below sits below $above", bounds(below).top >= bounds(above).bottom - 1f)
        }
    }

    @Test
    fun editedMeaningsAreStackedAndTheSpeakerStartsBesideTheFirstOneOnANarrowScreen() {
        val edited = editableMeanings(word)
        compose.setContent {
            DualSubTheme {
                Box(Modifier.width(300.dp)) {
                    WithSpeaker("injured", "en", { _, _ -> }) {
                        EditMeanings(word, word.meaning, {}, edited, Locale.ENGLISH, enabled = true) {}
                    }
                }
            }
        }
        assertStacked("saved_meaning_primary", "saved_meaning_fa", "saved_meaning_ar")
        val first = bounds("saved_meaning_primary")
        val speaker = bounds("practice_read_meaning")
        assertTrue("the field stops before the speaker button", first.right <= speaker.left + 1f)
        assertEquals("the speaker starts at the first field", first.top, speaker.top, 16f)
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        listOf("saved_meaning_primary", "saved_meaning_fa", "saved_meaning_ar").forEach {
            assertTrue("$it fits the screen width", bounds(it).right <= screen.right + 1f && bounds(it).left >= screen.left - 1f)
        }
        compose.onNodeWithTag("saved_meaning_fa").assertIsDisplayed()
    }

    @Test
    fun reviewedMeaningsAreStackedToo() {
        compose.setContent {
            DualSubTheme {
                Box(Modifier.width(300.dp)) {
                    WithSpeaker("injured", "en", { _, _ -> }) { ReviewMeanings(word, Locale.ENGLISH) }
                }
            }
        }
        assertStacked("review_meaning", "review_meaning_fa", "review_meaning_ar")
        assertTrue(bounds("review_meaning").right <= bounds("practice_read_meaning").left + 1f)
    }
}
