package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TripleSubtitlesTest {
    @Test
    fun disabledModeAsksForNothing() {
        assertNull(thirdLanguageToTranslate(enabled = false, source = "de", target = "en", third = "fa"))
    }

    @Test
    fun thirdLanguageEqualToAnotherLineIsNotRequested() {
        assertNull(thirdLanguageToTranslate(true, source = "de", target = "en", third = "de"))
        assertNull(thirdLanguageToTranslate(true, source = "de", target = "en", third = "en"))
        assertNull(thirdLanguageToTranslate(true, source = "de-DE", target = "en", third = "DE"))
    }

    @Test
    fun defaultsAreGermanEnglishPersianAndUnknownSavedValuesFallBack() {
        assertEquals("fa", thirdLanguageToTranslate(true, source = "de", target = "en", third = "fa"))
        assertEquals("fa", storedThirdLanguage(null))
        assertEquals("fa", storedThirdLanguage("xx"))
        assertEquals("ar", storedThirdLanguage("ar"))
    }

    @Test
    fun unknownSourceStillTranslatesTheThirdLanguage() {
        assertEquals("fa", thirdLanguageToTranslate(true, source = null, target = "en", third = "fa"))
    }

    @Test
    fun thirdLinesApplyOnlyToTheirOwnVideo() {
        val lines = ThirdLines("video-a", mapOf(3L to "سلام"))
        assertEquals("سلام", lines.textFor("video-a", 3L))
        assertNull(lines.textFor("video-b", 3L))
        assertNull(lines.textFor("video-a", 4L))
        assertNull(lines.textFor(null, 3L))
    }

    @Test
    fun everyRowGetsItsOwnThirdLineFromTheSourceText() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val asked = mutableListOf<String>()
                val done = CompletableDeferred<Map<Long, String>>()
                val job =
                    launch {
                        runThirdLanguage(store, requests, { text ->
                            asked += text
                            "fa:$text"
                        }) { lines -> if (lines.size == 2) done.complete(lines) }
                    }
                try {
                    val lines = withTimeout(5000) { done.await() }
                    assertEquals(mapOf(0L to "fa:Hallo", 1L to "fa:Welt"), lines)
                    assertEquals(listOf("Hallo", "Welt"), asked)
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun aFailingThirdTranslatorEndsQuietlyAndKeepsEarlierLines() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val published = mutableListOf<Map<Long, String>>()
                val failed = CompletableDeferred<Unit>()
                val job =
                    launch {
                        runThirdLanguage(store, requests, { text ->
                            if (text == "Welt") {
                                failed.complete(Unit)
                                error("Persian is down")
                            }
                            "fa:$text"
                        }) { published += it }
                    }
                try {
                    withTimeout(5000) { failed.await() }
                    yield()
                    assertTrue(job.isActive) // A translator error stops translating; it never fails the caller.
                    assertEquals(mapOf(0L to "fa:Hallo"), published.last())
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun cancellationPropagatesSoAVideoSwitchStopsTheJob() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val started = CompletableDeferred<Unit>()
                val job =
                    launch {
                        runThirdLanguage(store, requests, {
                            started.complete(Unit)
                            CompletableDeferred<String>().await()
                        }) { }
                    }
                withTimeout(5000) { started.await() }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
        }

    private val strings =
        object : UiStrings {
            override fun get(
                id: Int,
                vararg formatArgs: Any,
            ): String = "status"
        }

    private fun playing(
        thirdLines: ThirdLines,
        translatedVisibility: CaptionVisibility = CaptionVisibility.ALWAYS,
        thirdEnabled: Boolean = true,
        originalVisibility: CaptionVisibility = CaptionVisibility.ALWAYS,
    ) = DualSubUiState(
        activeVideoId = "v1",
        segments = listOf(SubtitleSegment(7, 0, 1000, "Hallo", "Hello")),
        currentIndex = 0,
        thirdLines = thirdLines,
        tripleSubtitlesEnabled = thirdEnabled,
        translatedVisibility = translatedVisibility,
        originalVisibility = originalVisibility,
    )

    @Test
    fun overlayShowsAllThreeLinesOfTheActiveRow() {
        val content = learningOverlayContent(playing(ThirdLines("v1", mapOf(7L to "سلام"))), strings)!!
        assertEquals(Triple("Hallo", "Hello", "سلام"), Triple(content.originalText, content.translatedText, content.thirdText))
    }

    @Test
    fun dualModeAndAnotherVideosLinesLeaveTheOverlayAtTwoLines() {
        assertNull(learningOverlayContent(playing(ThirdLines()), strings)!!.thirdText)
        assertNull(learningOverlayContent(playing(ThirdLines("other", mapOf(7L to "سلام"))), strings)!!.thirdText)
    }

    private val persian = ThirdLines("v1", mapOf(7L to "سلام"))

    private fun threeLines(state: DualSubUiState): List<String?> =
        learningOverlayContent(state, strings)!!.let { listOf(it.originalText, it.translatedText, it.thirdText) }

    @Test
    fun eachSlotIsIndependentOfTheOthers() {
        val never = CaptionVisibility.NEVER
        assertEquals(listOf("Hallo", null, "سلام"), threeLines(playing(persian, never)))
        assertEquals(listOf(null, "Hello", "سلام"), threeLines(playing(persian, originalVisibility = never)))
        assertEquals(listOf(null, null, "سلام"), threeLines(playing(persian, never, originalVisibility = never)))
    }

    @Test
    fun anUncheckedThirdSlotShowsNoThirdLineEvenWithTranslatedLines() {
        assertEquals(listOf("Hallo", "Hello", null), threeLines(playing(persian, thirdEnabled = false)))
    }

    @Test
    fun thirdLineFollowsTheSecondLinesPlayModeOnlyWhileTheSecondSlotIsOn() {
        val listenFirst = playing(persian).copy(studyPlayMode = StudyPlayMode.LISTEN_FIRST, playbackPaused = false)
        assertNull(threeLines(listenFirst)[2])
        val secondOff = listenFirst.copy(translatedVisibility = CaptionVisibility.NEVER)
        assertEquals("سلام", threeLines(secondOff)[2])
    }

    @Test
    fun rtlDefaultsToOnlyTheThirdLineAndEachSlotIsSetAlone() {
        val defaults = SlotRtl()
        assertEquals(listOf(false, false, true), SubtitleSlot.entries.map { defaults[it] })
        val changed = defaults.with(SubtitleSlot.SECOND, true).with(SubtitleSlot.THIRD, false)
        assertEquals(listOf(false, true, false), SubtitleSlot.entries.map { changed[it] })
        assertEquals(SlotRtl(), DualSubUiState().slotRtl)
    }

    @Test
    fun rtlPreferencesHaveDistinctKeysThatReset() {
        val keys = SubtitleSlot.entries.map { it.rtlPreference }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(RESETTABLE_SETTING_KEYS.containsAll(keys))
    }

    @Test
    fun aThirdLanguageThatMatchesAHiddenSecondLineIsStillRequested() {
        assertNull(thirdLanguageToTranslate(true, source = "de", target = "en", third = "en", secondShown = true))
        assertEquals("en", thirdLanguageToTranslate(true, source = "de", target = "en", third = "en", secondShown = false))
        assertNull(thirdLanguageToTranslate(true, source = "de", target = "en", third = "de", secondShown = false))
    }

    @Test
    fun forkDefaultsAreEnglishSecondPersianThirdOffAndSlotsMapToVisibility() {
        val defaults = DualSubUiState()
        assertEquals("en", defaults.targetLanguage)
        assertEquals("fa", defaults.thirdLanguage)
        assertEquals(false, defaults.tripleSubtitlesEnabled)
        assertEquals(CaptionVisibility.ALWAYS, slotVisibility(true))
        assertEquals(CaptionVisibility.NEVER, slotVisibility(false))
        assertNull(defaults.thirdLanguageFor("de"))
    }

    private suspend fun withStore(block: suspend (SubtitleStore) -> Unit) {
        val directory = Files.createTempDirectory("triple-subtitles-test").toFile()
        try {
            val rows =
                listOf(
                    SubtitleSegment(0, 0, 1000, "Hallo"),
                    SubtitleSegment(1, 1000, 2000, "Welt"),
                )
            SubtitleStore.create(directory, rows).use { block(it) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
