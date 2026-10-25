package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PersianTextTest {
    private val zwnj = "‌"

    @Test
    fun tokenizerKeepsZwnjWordsWholeAndLeavesPersianPunctuationOut() {
        val text = "من می${zwnj}خواهم خانه را ببینم، درسته؟"
        val words = LanguageAwareTokenizer.tokenize(text, "fa").map { it.text }
        assertEquals(listOf("من", "می${zwnj}خواهم", "خانه", "را", "ببینم", "درسته"), words)
    }

    @Test
    fun normalizationUnifiesArabicLettersAndDropsMarksButKeepsZwnj() {
        assertEquals("کتاب", normalizePersian("كتاب"))
        assertEquals("می${zwnj}روم", normalizePersian("مي${zwnj}رَوم"))
        assertEquals("علی", normalizePersian("عَلـي"))
    }

    @Test
    fun persianSpellingsOfOneWordShareASavedWordIdOtherLanguagesDoNot() {
        val segment = SubtitleSegment(1, 0, 1000, "Das Buch", "The book")

        fun id(
            word: String,
            language: String,
        ) = savedWordId(word, language, "de", "dQw4w9WgXcQ", segment)
        assertEquals(id("کتاب", "fa"), id("كتاب", "fa"))
        assertNotEquals(id("كتاب", "ar"), id("کتاب", "ar"))
        assertEquals(id("Haus", "de"), id("haus", "de"))
    }
}
