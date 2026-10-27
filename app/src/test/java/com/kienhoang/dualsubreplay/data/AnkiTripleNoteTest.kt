package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiTripleNoteTest {
    private val german =
        SavedWord(
            id = "1",
            word = "Verletzten",
            reading = null,
            wordLanguage = "de",
            meaningLanguage = "en",
            meaning = "injured",
            sentence = "Er kümmert sich um die Verletzten.",
            translatedSentence = "He takes care of the injured.",
            videoId = "abcdefghijk",
            startMs = 971_000,
            endMs = 974_500,
            translated = false,
            sentenceTranslations = mapOf("fa" to "او از مجروحان مراقبت می‌کند."),
            meanings = mapOf("fa" to "مجروح شد"),
            personalNotes = "Plural of der Verletzte.\n\nدستور <نکته>",
        )

    private fun fields(word: SavedWord) = TRIPLE_FIELDS.zip(tripleNoteFields(word)).toMap()

    @Test
    fun theTypeIsVersionedAndHasTheNineFieldsInOrder() {
        assertEquals("TripleSub Replay v3", TRIPLE_NOTE_TYPE_NAME)
        assertTrue(TRIPLE_NOTE_TYPE_NAME != ANKI_NOTE_TYPE_NAME)
        assertEquals(
            listOf("Word", "Subtitle 1", "Subtitle 2", "Reading", "Sentence", "Translation", "Text", "Source", "Online Example"),
            TRIPLE_FIELDS,
        )
        assertEquals(TRIPLE_FIELDS.size, tripleNoteFields(german).size)
    }

    @Test
    fun eachMeaningHasItsOwnFieldWithoutAnyLanguageLabel() {
        val f = fields(german)
        assertEquals("injured", f["Subtitle 1"])
        assertEquals("مجروح شد", f["Subtitle 2"])
        assertFalse(f.getValue("Subtitle 2").contains("fa"))
    }

    @Test
    fun translationIsTheSecondThenThirdSentenceOnSeparateLinesWithoutLabels() {
        assertEquals(
            "<span dir=\"auto\">He takes care of the injured.</span><br><span dir=\"auto\">او از مجروحان مراقبت می‌کند.</span>",
            fields(german)["Translation"],
        )
        assertEquals("<b>Verletzten</b>.", fields(german.copy(sentence = "Verletzten.", word = "Verletzten"))["Sentence"])
    }

    @Test
    fun disabledMissingBlankOrRepeatedTranslationsAreNotExported() {
        assertEquals(
            "<span dir=\"auto\">He takes care of the injured.</span>",
            ankiLinesHtml(german.copy(sentenceTranslations = emptyMap())),
        )
        assertEquals("", ankiLinesHtml(german.copy(translatedSentence = null, sentenceTranslations = mapOf("fa" to "  "))))
        val same = german.copy(sentenceTranslations = mapOf("en" to "He takes care of the injured."))
        assertEquals("<span dir=\"auto\">He takes care of the injured.</span>", ankiLinesHtml(same))
        assertEquals("", ankiMeaning2Html(german.copy(meanings = emptyMap())))
        assertEquals("", ankiMeaning2Html(german.copy(meanings = mapOf("fa" to " "))))
    }

    @Test
    fun descKeepsLineBreaksAndIsEscapedAndEmptyStaysEmpty() {
        assertEquals("Plural of der Verletzte.<br><br>دستور &lt;نکته&gt;", fields(german)["Text"])
        assertEquals("", fields(german.copy(personalNotes = ""))["Text"])
    }

    @Test
    fun theOnlineExampleIsTheClipLinkOnlyWhenOnAndValid() {
        val link = ankiOnlineExampleHtml(german.copy(online = true))
        assertEquals("<a href=\"https://www.youtube.com/watch?v=abcdefghijk&amp;t=971s\">YouTube 16:11-16:14</a>", link)
        assertEquals("", ankiOnlineExampleHtml(german.copy(online = false)))
        assertEquals("", ankiOnlineExampleHtml(german.copy(online = true, videoId = null)))
        assertEquals("", ankiOnlineExampleHtml(german.copy(online = true, endMs = german.startMs)))
    }

    @Test
    fun theOlderSixFieldTypeAndItsNotesAreUnchanged() {
        val six = ankiNoteFields(german)
        assertEquals(listOf("Word", "Meaning", "Reading", "Sentence", "Translation", "Source"), ANKI_FIELDS)
        assertEquals(ANKI_FIELDS.size, six.size)
        assertTrue(six[1].startsWith("injured") && six[1].contains("مجروح شد"))
    }

    @Test
    fun ankiConnectFillsTheNewTypeByNameAndOtherTypesGetEveryValueOnTheBack() {
        val withClip = german.copy(online = true)
        assertEquals(TRIPLE_FIELDS, ankiConnectFields(TRIPLE_FIELDS, withClip).keys.toList())
        assertEquals(TRIPLE_FIELDS.zip(tripleNoteFields(withClip)).toMap(), ankiConnectFields(TRIPLE_FIELDS, withClip))
        val back = ankiConnectFields(listOf("Front", "Back"), withClip).getValue("Back")
        assertTrue(back.contains("injured") && back.contains("مجروح شد") && back.contains("Plural of") && back.contains("t=971s"))
        assertEquals(ANKI_FIELDS, ankiConnectFields(ANKI_FIELDS, withClip).keys.toList())
    }

    @Test
    fun theCardShowsMeaning2DescAndTheExampleOnlyWhenFilled() {
        listOf("Subtitle 2", "Text", "Online Example").forEach {
            assertTrue(TRIPLE_ANSWER_FORMAT.contains("{{#$it}}") && TRIPLE_ANSWER_FORMAT.contains("{{/$it}}"))
        }
        assertFalse(TRIPLE_QUESTION_FORMAT.contains("Meaning") || TRIPLE_QUESTION_FORMAT.contains("Text"))
    }
}
