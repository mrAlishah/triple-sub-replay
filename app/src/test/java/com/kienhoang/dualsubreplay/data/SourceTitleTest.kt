package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTitleTest {
    private fun word(
        id: String,
        videoId: String? = "aaaaaaaaaaa",
        title: String? = null,
        due: Long = 0,
        text: String = id,
    ) = SavedWord(id, text, null, "de", "en", "m", "s", null, videoId, 0, 0, false, false, dueAt = due, sourceTitle = title)

    @Test
    fun theTitleSurvivesJsonAndAnOlderRecordWithoutItStillLoads() {
        val titled = word("1", title = "Deutsch lernen – عربي 日本")
        assertEquals(titled, decodeWord(encodeWord(titled)))
        val old = encodeWord(titled).apply { remove("sourceTitle") }
        assertNull(decodeWord(old).sourceTitle)
        assertFalse(encodeWord(word("2")).has("sourceTitle"))
        assertNull(decodeWord(encodeWord(titled).put("sourceTitle", "   ")).sourceTitle)
    }

    @Test
    fun theTitleIsNormalizedAndNeverPartOfTheIdentity() {
        assertEquals("A B", normalizeSourceTitle("  A \n\t B  "))
        assertNull(normalizeSourceTitle("  \n "))
        assertEquals(200, normalizeSourceTitle("x".repeat(500))?.length)
        val a = word("1", title = "Old name")
        val b = a.copy(sourceTitle = "Renamed")
        assertEquals(transferIdentity(a), transferIdentity(b))
        val selection = LearningWordSelection(AnalyzedToken("Haus", 0, 4, PartOfSpeech.NOUN), "de", "en", "aaaaaaaaaaa", null, false)
        assertEquals(
            savedWordFrom(selection, "house", false).id,
            savedWordFrom(selection.copy(sourceTitle = "Any title"), "house", false).id,
        )
        assertEquals("Any title", savedWordFrom(selection.copy(sourceTitle = " Any   title "), "house", false).sourceTitle)
    }

    @Test
    fun savingAgainOrImportingAnOlderFileNeverErasesATitle() {
        val old = word("1", title = "Kept")
        assertEquals("Kept", mergeResaved(old, word("1")).sourceTitle)
        assertEquals("New", mergeResaved(old, word("1", title = "New")).sourceTitle)
        assertEquals("Kept", keepSavedNotes(word("1"), old).sourceTitle)
        assertEquals("New", keepSavedNotes(word("1", title = "New"), old).sourceTitle)
    }

    @Test
    fun reviewingKeepsTheTitleAndTheScheduleRules() {
        val reviewed = reviewWord(word("1", title = "T"), ReviewRating.GOOD, now = 1_000)
        assertEquals("T", reviewed.sourceTitle)
        assertEquals(3 * DAY_MS, reviewed.intervalMs)
        assertEquals(1_000 + 3 * DAY_MS, reviewed.dueAt)
    }

    @Test
    fun sourcesAreKeyedByVideoSoSameTitleVideosStayApartAndARenamedVideoStaysOne() {
        val words =
            listOf(
                word("1", "aaaaaaaaaaa", "Same"),
                word("2", "bbbbbbbbbbb", "same"),
                word("3", "aaaaaaaaaaa", "Renamed later"),
                word("4", "aaaaaaaaaaa", "Renamed later"),
                word("5", null, "  Local   title "),
                word("6", null, "local title"),
                word("7", null, null),
                word("8", "ccccccccccc", null),
            )
        val options = sourceOptions(words)
        assertEquals(5, options.size)
        val a = options.first { it.videoId == "aaaaaaaaaaa" }
        assertEquals(3, a.count)
        assertEquals("Renamed later", a.title)
        assertEquals(2, options.first { it.key == "t:local title" }.count)
        assertEquals(1, options.first { it.videoId == "ccccccccccc" }.count)
        assertNull(options.first { it.videoId == "ccccccccccc" }.title)
        assertEquals(UNKNOWN_SOURCE_KEY, options.last().key)
        assertEquals(UNKNOWN_SOURCE_KEY, sourceKey(words[6]))
        val same = sourceOptions(listOf(word("1", "aaaaaaaaaaa", "Same"), word("2", "bbbbbbbbbbb", "same")))
        assertTrue(same.all { it.sharesTitle })
        assertFalse(sourceOptions(listOf(word("1", "aaaaaaaaaaa", "Only"))).single().sharesTitle)
    }

    @Test
    fun theSelectorSearchIgnoresCaseAndWorksInAnyScript() {
        val options =
            sourceOptions(
                listOf(
                    word("1", "aaaaaaaaaaa", "Deutsch Lernen"),
                    word("2", "bbbbbbbbbbb", "اخبار الليل"),
                    word("3", "ccccccccccc", "ÄPFEL"),
                ),
            )
        assertEquals(1, filterSourceOptions(options, "  lernen ").size)
        assertEquals(1, filterSourceOptions(options, "الليل").size)
        assertEquals(1, filterSourceOptions(options, "äpfel").size)
        assertEquals(3, filterSourceOptions(options, "").size)
        assertTrue(filterSourceOptions(options, "zzz").isEmpty())
    }

    @Test
    fun theSourceFilterAndTheTextSearchCombineWithAnd() {
        val words =
            listOf(
                word("1", "aaaaaaaaaaa", "A", text = "Haus"),
                word("2", "aaaaaaaaaaa", "A", text = "Baum"),
                word("3", "bbbbbbbbbbb", "B", text = "Haus"),
            )
        val search: (SavedWord) -> Boolean = { it.word == "Haus" }
        assertEquals(listOf("1", "3"), filterSavedWords(words, null, search).map { it.id })
        assertEquals(listOf("1"), filterSavedWords(words, "v:aaaaaaaaaaa", search).map { it.id })
        assertEquals(listOf("1", "2", "3"), filterSavedWords(words, null) { true }.map { it.id })
        assertTrue(filterSavedWords(words, UNKNOWN_SOURCE_KEY, search).isEmpty())
    }

    @Test
    fun theDueQueueFollowsTheSelectedSourceAndNeverMixesAnother() {
        val words =
            listOf(
                word("1", "aaaaaaaaaaa", "A", due = 0),
                word("2", "aaaaaaaaaaa", "A", due = 9_999),
                word("3", "bbbbbbbbbbb", "B", due = 0),
                word("4", null, null, due = 0),
            )
        assertEquals(listOf("1", "3", "4"), dueWordsForSource(words, null, now = 100).map { it.id })
        assertEquals(listOf("1"), dueWordsForSource(words, "v:aaaaaaaaaaa", now = 100).map { it.id })
        assertEquals(listOf("4"), dueWordsForSource(words, UNKNOWN_SOURCE_KEY, now = 100).map { it.id })
        assertTrue(dueWordsForSource(words, "v:bbbbbbbbbbb", now = -1).isEmpty())
    }

    @Test
    fun jsonBackupKeepsTitlesAndAnOlderBackupStillImports() {
        val titled = word("1", title = "Tab and \"quote\" line – 日本")
        assertEquals(listOf(titled), parseTransfer(exportBackup(listOf(titled))).backup)
        val legacy = exportBackup(listOf(word("2"))).also { assertFalse(it.contains("sourceTitle")) }
        assertEquals(listOf(word("2")), parseTransfer(legacy).backup)
    }

    @Test
    fun tsvCarriesTheTitleByHeaderAndOldFilesAndLegacyHeadersStillImport() {
        val titled = word("1", title = "Ti\ttle \"q\" – عربي").copy(videoId = "dQw4w9WgXcQ")
        val text = exportAnkiTsv(listOf(titled))
        assertTrue(text.lines().first { it.startsWith("#columns:") }.contains("\tendMs\tsourceTitle\t"))
        val doc = parseTransfer(text)
        assertEquals(
            "Ti tle \"q\" – عربي",
            previewImport(doc, defaultTransferMapping(doc), "de", "en", emptyList()).words.single().sourceTitle,
        )
        val old = parseTransfer("#separator:Tab\n#html:false\n#columns:word\tmeaning\tmeaningLanguage\n\"Haus\"\t\"house\"\t\"en\"")
        assertNull(previewImport(old, defaultTransferMapping(old), "de", "en", emptyList()).words.single().sourceTitle)
        val legacyWithTitle =
            parseTransfer("#separator:Tab\n#html:false\n#columns:word\tmeaning\tsourceTitle\n\"Haus\"\t\"house\"\t\"  My  video \"")
        assertEquals(
            "My video",
            previewImport(legacyWithTitle, defaultTransferMapping(legacyWithTitle), "de", "en", emptyList()).words.single().sourceTitle,
        )
    }
}
