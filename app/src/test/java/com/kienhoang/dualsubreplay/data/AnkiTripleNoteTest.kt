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
    fun theSourceTitleIsEscapedAndKeepsUnicodeAndRightToLeftText() {
        val fields = tripleNoteFields(german.copy(sourceTitle = "A <b>&\"x\" عربي"))
        assertEquals("A &lt;b&gt;&amp;&quot;x&quot; عربي", fields[TRIPLE_FIELDS.indexOf("Source Title")])
        assertEquals("", tripleNoteFields(german.copy(sourceTitle = null))[TRIPLE_FIELDS.indexOf("Source Title")])
    }

    @Test
    fun theAnswerShowsTheSourceTitleOnlyWhenThereIsOneAndTheLinkStays() {
        val answer = tripleCardTemplate(CardFront.WORD_AND_SENTENCE).answer
        assertTrue(answer.contains("{{#Source Title}}"))
        assertTrue(answer.contains("{{Source}}"))
    }

    @Test
    fun theVideoBecomesAStableTagOnlyForAValidId() {
        assertEquals(listOf(ANKI_TAG, "yt_abcdefghijk"), ankiTags(german.copy(videoId = "abcdefghijk")))
        assertEquals(listOf(ANKI_TAG), ankiTags(german.copy(videoId = null)))
        assertEquals(listOf(ANKI_TAG), ankiTags(german.copy(videoId = "bad id!")))
    }

    @Test
    fun theTypeIsVersionedAndHasTheV4FieldsInOrderThenTheTwoSoundFields() {
        assertEquals("TripleSub Replay v5", TRIPLE_NOTE_TYPE_NAME)
        assertEquals("TripleSub Replay v4", TRIPLE_V4_NOTE_TYPE_NAME)
        assertEquals("TripleSub Replay v3", TRIPLE_V3_NOTE_TYPE_NAME)
        assertEquals(TRIPLE_V3_FIELDS + "Source Title", TRIPLE_V4_FIELDS)
        assertEquals(TRIPLE_V4_FIELDS + listOf("Word audio", "Sentence audio"), TRIPLE_FIELDS)
        assertTrue(TRIPLE_NOTE_TYPE_NAME != ANKI_NOTE_TYPE_NAME)
        assertEquals(
            listOf("Word", "Subtitle 1", "Subtitle 2", "Reading", "Sentence", "Translation", "Text", "Source", "Online Example"),
            TRIPLE_V3_FIELDS,
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
    fun upstreamsAudioTypeAndItsNotesAreUnchanged() {
        val eight = ankiNoteFields(german)
        assertEquals(
            listOf("Word", "Meaning", "Reading", "Sentence", "Translation", "Source", "Word audio", "Sentence audio"),
            ANKI_FIELDS,
        )
        assertEquals(ANKI_FIELDS.size, eight.size)
        assertTrue(eight[1].startsWith("injured") && eight[1].contains("مجروح شد"))
    }

    @Test
    fun ankiConnectFillsTheNewTypeByNameAndOtherTypesGetEveryValueOnTheBack() {
        val withClip = german.copy(online = true)
        assertEquals(TRIPLE_FIELDS, ankiConnectFields(TRIPLE_FIELDS, withClip).keys.toList())
        assertEquals(TRIPLE_FIELDS.zip(tripleNoteFields(withClip)).toMap(), ankiConnectFields(TRIPLE_FIELDS, withClip))
        val back = ankiConnectFields(listOf("Front", "Back"), withClip).getValue("Back")
        assertTrue(back.contains("injured") && back.contains("مجروح شد") && back.contains("Plural of") && back.contains("t=971s"))
        assertEquals(ANKI_FIELDS, ankiConnectFields(ANKI_FIELDS, withClip).keys.toList())
        // A phone that only has the v4 or v3 type still gets the sounds, after the link in Source.
        val sound = CardMedia(word = "[sound:w.mp3]", sentence = "[sound:s.m4a]")
        listOf(TRIPLE_V4_FIELDS, TRIPLE_V3_FIELDS).forEach { names ->
            assertEquals(names, ankiConnectFields(names, withClip, sound).keys.toList())
            assertTrue(ankiConnectFields(names, withClip, sound).getValue("Source").endsWith("<br>[sound:w.mp3]<br>[sound:s.m4a]"))
        }
        // The v4 type has no sound fields: its notes keep their ten fields and the sounds stay out.
        assertEquals(TRIPLE_V4_FIELDS, ankiConnectFields(TRIPLE_V4_FIELDS, withClip).keys.toList())
    }

    @Test
    fun soundsAndThePictureFillTheV5NoteAndTheSourceFieldKeepsTheLink() {
        val media = CardMedia(word = "[sound:w.m4a]", sentence = "[sound:s.m4a]", picture = "<img src=\"p.jpg\">")
        val byName = TRIPLE_FIELDS.zip(tripleNoteFields(german, media)).toMap()
        assertEquals("[sound:w.m4a]", byName["Word audio"])
        assertEquals("[sound:s.m4a]", byName["Sentence audio"])
        assertTrue(byName.getValue("Source").startsWith("<img src=\"p.jpg\"><br><a href="))
    }

    @Test
    fun everyCardFrontKeepsTheTripleBackFieldsAndNoUpstreamMeaningField() {
        CardFront.entries.forEach { front ->
            val answer = tripleCardTemplate(front).answer
            assertFalse(front.name, answer.contains("{{Meaning}}"))
            assertTrue(front.name, answer.contains("{{Subtitle 1}}") && answer.contains("{{#Source Title}}") && answer.contains("{{Source}}"))
        }
    }

    @Test
    fun theCardShowsMeaning2DescAndTheExampleOnlyWhenFilled() {
        val template = tripleCardTemplate(CardFront.WORD_AND_SENTENCE)
        listOf("Subtitle 2", "Text", "Online Example").forEach {
            assertTrue(template.answer.contains("{{#$it}}") && template.answer.contains("{{/$it}}"))
        }
        assertFalse(template.question.contains("Meaning") || template.question.contains("Text"))
    }
}
