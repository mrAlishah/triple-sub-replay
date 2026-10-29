package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.AnkiSendResult
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.TRIPLE_FIELDS
import com.kienhoang.dualsubreplay.data.tripleNoteFields
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeSendTest {
    private val saved =
        SavedWord(
            id = "w1",
            word = "Verletzten",
            reading = "fɛɐ̯ˈlɛtsən",
            wordLanguage = "de",
            meaningLanguage = "en",
            meaning = "injured",
            sentence = "Er kümmert sich um die Verletzten.",
            translatedSentence = "He takes care of the injured.",
            videoId = "abcdefghijk",
            startMs = 971_000,
            endMs = 974_000,
            translated = false,
            online = true,
            sentenceTranslations = mapOf("fa" to "او از مجروحان مراقبت می‌کند."),
            meanings = mapOf("fa" to "مجروح شد", "ar" to "جريح"),
            personalNotes = "Plural.\nدستور",
            dueAt = 5_000,
            intervalMs = 7_000,
        )

    @Test
    fun theSavedWordFillsAllTenFieldsIncludingSavedMeaningsOfLanguagesNoLongerOn() {
        val byName = TRIPLE_FIELDS.zip(tripleNoteFields(saved)).toMap()
        assertEquals(10, byName.size)
        assertEquals("Verletzten", byName["Word"])
        assertEquals("injured", byName["Subtitle 1"])
        assertEquals("مجروح شد<br>جريح", byName["Subtitle 2"])
        assertEquals("fɛɐ̯ˈlɛtsən", byName["Reading"])
        assertTrue(byName.getValue("Sentence").contains("<b>Verletzten</b>"))
        assertTrue(byName.getValue("Translation").contains("He takes care of the injured.<"))
        assertEquals("Plural.<br>دستور", byName["Text"])
        assertTrue(byName.getValue("Source").contains("t=971s"))
        assertTrue(byName.getValue("Online Example").contains("971s"))
    }

    @Test
    fun pendingEditsInTheVocabularyViewAreAppliedBeforeSending() {
        val sent = saved.withPendingEdits(false, "  hurt ", mapOf("fa" to " زخمی ", "ar" to ""), "new notes")
        assertEquals("hurt", sent.meaning)
        assertEquals(mapOf("fa" to "زخمی"), sent.meanings)
        assertEquals("new notes", sent.personalNotes)
        assertEquals(saved.dueAt, sent.dueAt)
        assertEquals(saved.intervalMs, sent.intervalMs)
        assertTrue(sent.online)
        assertEquals(saved.sentenceTranslations, sent.sentenceTranslations)
    }

    @Test
    fun unchangedEditsLeaveTheSavedWordAsItIs() {
        assertEquals(saved, saved.withPendingEdits(false, saved.meaning, saved.meanings, saved.personalNotes))
    }

    @Test
    fun inAReviewMeaningsAreNotEditableSoOnlyTheNotesAreApplied() {
        val sent = saved.withPendingEdits(true, "ignored", mapOf("fa" to "ignored"), "review notes")
        assertEquals(saved.meaning, sent.meaning)
        assertEquals(saved.meanings, sent.meanings)
        assertEquals("review notes", sent.personalNotes)
    }

    @Test
    fun theWordSentIsTheStoredOneAfterTheEditsAreSaved() =
        runBlocking {
            val stored = saved.withPendingEdits(false, "hurt", saved.meanings, saved.personalNotes)
            val sent = mutableListOf<SavedWord>()
            val result =
                sendEditedWord({ stored }, {
                    sent += it
                    AnkiSendResult(sent = 1)
                })
            assertEquals(AnkiSendResult(sent = 1), result)
            assertEquals(listOf(stored), sent)
            assertEquals("hurt", sent.single().meaning)
        }

    @Test
    fun aWordThatNoLongerExistsIsNotSent() =
        runBlocking {
            var calls = 0
            assertNull(
                sendEditedWord({ null }, {
                    calls++
                    AnkiSendResult(sent = 1)
                }),
            )
            assertEquals(0, calls)
        }
}
