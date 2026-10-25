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
    ) = DualSubUiState(
        activeVideoId = "v1",
        segments = listOf(SubtitleSegment(7, 0, 1000, "Hallo", "Hello")),
        currentIndex = 0,
        thirdLines = thirdLines,
        translatedVisibility = translatedVisibility,
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

    @Test
    fun hidingTranslationsAlsoHidesTheThirdLine() {
        val hidden = playing(ThirdLines("v1", mapOf(7L to "سلام")), CaptionVisibility.NEVER)
        val content = learningOverlayContent(hidden, strings)!!
        assertNull(content.translatedText)
        assertNull(content.thirdText)
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
