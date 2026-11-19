package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SentenceSlice
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.StringWriter
import java.io.Writer
import kotlin.coroutines.cancellation.CancellationException

/** Whole transcript export with Subtitle 1 and Subtitle 2 chosen independently (spec 2026-11-19-srt-export-subtitle-2). */
class WholeTranscriptSubtitle2Test {
    @get:Rule
    val folder = TemporaryFolder()

    private val subtitle1Asked = mutableListOf<String>()
    private val subtitle2Asked = mutableListOf<String>()
    private val subtitle2Batches = mutableListOf<List<String>>()

    private val rows =
        listOf(
            SubtitleSegment(1, 1_000, 2_000, "Hello", sentence = SentenceSlice("Hello there.", listOf(6), 0)),
            SubtitleSegment(2, 2_000, 3_500, "there.", sentence = SentenceSlice("Hello there.", listOf(6), 1)),
            SubtitleSegment(3, 3_600_000, 3_602_000, "Good night."),
        )

    private fun track(
        language: String = "fa",
        translate: suspend (String) -> String = {
            subtitle2Asked += it
            "fa: $it"
        },
    ) = TranscriptTrack(language, translate, prefetch = { texts -> subtitle2Batches += texts })

    private fun transcript(
        segments: List<SubtitleSegment> = rows,
        subtitle1: suspend (String) -> String = {
            subtitle1Asked += it
            "vi: $it"
        },
        subtitle2: TranscriptTrack? = track(),
    ): WholeTranscript =
        runBlocking { WholeTranscript(SubtitleStore.create(folder.newFolder(), segments), subtitle1, null, "vi", subtitle2) }

    private fun WholeTranscript.exported(
        format: TranscriptExportFormat,
        subtitle1: Boolean,
        subtitle2: Boolean,
    ): Pair<String, TranscriptExportOutcome> {
        val out = StringWriter()
        val outcome = runBlocking { export(out, TranscriptExportOptions(format, subtitle1, subtitle2), title = null) }
        return out.toString() to outcome
    }

    @Test
    fun srtHasTheOriginalAndOnlyTheSubtitlesThatWereChosenInThatOrder() {
        val cue1 = "1\n00:00:01,000 --> 00:00:03,500\nHello there.\n"
        val cue2 = "2\n01:00:00,000 --> 01:00:02,000\nGood night.\n"
        val expected =
            mapOf(
                (false to false) to "$cue1\n$cue2\n",
                (true to false) to "${cue1}vi: Hello there.\n\n${cue2}vi: Good night.\n\n",
                (false to true) to "${cue1}fa: Hello there.\n\n${cue2}fa: Good night.\n\n",
                (true to true) to "${cue1}vi: Hello there.\nfa: Hello there.\n\n${cue2}vi: Good night.\nfa: Good night.\n\n",
            )
        expected.forEach { (choice, srt) ->
            val (text, outcome) = transcript().exported(TranscriptExportFormat.SRT, choice.first, choice.second)
            assertEquals("Subtitle 1 ${choice.first}, Subtitle 2 ${choice.second}", srt, text)
            assertEquals(TranscriptExportOutcome(completed = true), outcome)
            assertEquals(TranscriptExportState.Result.SAVED, outcome.result)
        }
    }

    @Test
    fun textExportAppliesTheSameChoices() {
        val line1 = "[0:01] Hello there.\n"
        val line2 = "[1:00:00] Good night.\n"
        assertEquals(line1 + line2, transcript().exported(TranscriptExportFormat.TEXT, false, false).first)
        assertEquals(
            "$line1    vi: Hello there.\n$line2    vi: Good night.\n",
            transcript().exported(TranscriptExportFormat.TEXT, true, false).first,
        )
        assertEquals(
            "$line1    fa: Hello there.\n$line2    fa: Good night.\n",
            transcript().exported(TranscriptExportFormat.TEXT, false, true).first,
        )
        assertEquals(
            "$line1    vi: Hello there.\n    fa: Hello there.\n$line2    vi: Good night.\n    fa: Good night.\n",
            transcript().exported(TranscriptExportFormat.TEXT, true, true).first,
        )
    }

    @Test
    fun aTrackThatIsNotChosenIsNeverAskedAndSubtitle2IsAskedFromTheOriginalNotSubtitle1() {
        transcript().exported(TranscriptExportFormat.SRT, subtitle1 = false, subtitle2 = true)
        assertTrue("Subtitle 1 is not asked when it is off", subtitle1Asked.isEmpty())
        assertEquals(listOf("Hello there.", "Good night."), subtitle2Asked)
        subtitle1Asked.clear()
        subtitle2Asked.clear()
        transcript().exported(TranscriptExportFormat.SRT, subtitle1 = true, subtitle2 = true)
        assertEquals(listOf("Hello there.", "Good night."), subtitle1Asked)
        assertEquals("Subtitle 2 never sees Subtitle 1's text", listOf("Hello there.", "Good night."), subtitle2Asked)
        assertFalse(subtitle2Asked.any { it.startsWith("vi:") })
    }

    @Test
    fun anExportThatIsRepeatedAsksNothingAgainAndEachSentenceIsAskedOncePerTrack() {
        val whole = transcript()
        whole.exported(TranscriptExportFormat.SRT, subtitle1 = true, subtitle2 = true)
        val again = whole.exported(TranscriptExportFormat.TEXT, subtitle1 = true, subtitle2 = true).second
        assertEquals(TranscriptExportOutcome(completed = true), again)
        assertEquals(2, subtitle1Asked.size)
        assertEquals(2, subtitle2Asked.size)
    }

    @Test
    fun theSameLanguageInBothTracksStillKeepsTheTracksApart() {
        val whole = transcript(subtitle1 = { "same: $it" }, subtitle2 = track(language = "vi") { "same: $it" })
        val (text, outcome) = whole.exported(TranscriptExportFormat.SRT, subtitle1 = true, subtitle2 = true)
        assertEquals(TranscriptExportOutcome(completed = true), outcome)
        assertTrue(text.contains("Hello there.\nsame: Hello there.\nsame: Hello there.\n\n"))
    }

    @Test
    fun aFailedTrackIsCountedAndNeverFilledFromTheOtherOne() {
        val failing = transcript(subtitle1 = { throw IllegalStateException("no key") })
        val (text, outcome) = failing.exported(TranscriptExportFormat.SRT, subtitle1 = true, subtitle2 = true)
        assertEquals(TranscriptExportOutcome(completed = true, missingSubtitle1 = 2, missingSubtitle2 = 0), outcome)
        assertEquals(TranscriptExportState.Result.PARTIAL, outcome.result)
        // Subtitle 2's lines are kept where they are; nothing stands in for Subtitle 1.
        assertEquals("1\n00:00:01,000 --> 00:00:03,500\nHello there.\nfa: Hello there.\n\n", text.substringBefore("2\n01:00:00"))
        assertFalse(text.contains("vi:"))
    }

    @Test
    fun subtitle2WithoutAnEngineIsCountedForEverySentence() {
        val (text, outcome) = transcript(subtitle2 = null).exported(TranscriptExportFormat.SRT, subtitle1 = true, subtitle2 = true)
        assertEquals(TranscriptExportOutcome(completed = true, missingSubtitle1 = 0, missingSubtitle2 = 2), outcome)
        assertEquals(TranscriptExportState.Result.PARTIAL, outcome.result)
        assertTrue(text.contains("Hello there.\nvi: Hello there.\n\n"))
        assertFalse(text.contains("fa:"))
        assertEquals(null, transcript(subtitle2 = null).subtitle2Language)
        assertEquals("fa", transcript().subtitle2Language)
        assertEquals("vi", transcript().subtitle1Language)
    }

    @Test
    fun aBlankTranslationCountsAsMissingAndABlankLineCannotEndACue() {
        val blank = transcript(subtitle2 = track { "   " })
        assertEquals(2, blank.exported(TranscriptExportFormat.SRT, subtitle1 = false, subtitle2 = true).second.missingSubtitle2)
        val twoLines = transcript(subtitle2 = track { "first\n\nsecond" })
        val text = twoLines.exported(TranscriptExportFormat.SRT, subtitle1 = false, subtitle2 = true).first
        assertTrue(text.startsWith("1\n00:00:01,000 --> 00:00:03,500\nHello there.\nfirst\nsecond\n\n2\n"))
    }

    @Test
    fun theOutcomeNamesWhatHappened() {
        assertEquals(TranscriptExportState.Result.FAILED, TranscriptExportOutcome(completed = false).result)
        assertEquals(TranscriptExportState.Result.FAILED, TranscriptExportOutcome(completed = false, missingSubtitle2 = 3).result)
        assertEquals(TranscriptExportState.Result.PARTIAL, TranscriptExportOutcome(completed = true, missingSubtitle1 = 1).result)
        assertEquals(TranscriptExportState.Result.SAVED, TranscriptExportOutcome(completed = true).result)
        assertEquals(
            TranscriptExportOptions(TranscriptExportFormat.TEXT, subtitle1 = true, subtitle2 = false),
            TranscriptExportOptions(TranscriptExportFormat.TEXT),
        )
    }

    @Test
    fun germanPersianAndArabicSurviveAsUtf8WithoutAByteOrderMark() {
        val german = "Ich lerne Deutsch.\nDas ist schön, größer und ähnlich."
        val persian = "من آلمانی یاد می‌گیرم."
        val arabic = "أنا أتعلم الألمانية، وهذا ممتع جدًا."
        val segments = listOf(SubtitleSegment(1, 1_000, 4_000, german))
        val whole = transcript(segments, subtitle1 = { arabic }, subtitle2 = track { persian })
        val bytes = ByteArrayOutputStream()
        runBlocking {
            bytes.bufferedWriter(Charsets.UTF_8).use { out ->
                whole.export(out, TranscriptExportOptions(TranscriptExportFormat.SRT, subtitle1 = true, subtitle2 = true), title = null)
            }
        }
        val raw = bytes.toByteArray()
        assertFalse("No byte order mark", raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte())
        assertEquals("1\n00:00:01,000 --> 00:00:04,000\n$german\n$arabic\n$persian\n\n", String(raw, Charsets.UTF_8))
        // The zero-width non-joiner of Persian is kept as it was written.
        assertTrue(String(raw, Charsets.UTF_8).contains("می‌گیرم"))
    }

    /** A 6-hour video (a line every 1.5 s): both tracks stream in bounded batches, each sentence is asked once, nothing is collected. */
    @Test
    fun aSixHourTranscriptAsksBothTracksInBoundedBatchesAndKeepsNothingBack() {
        val count = 6 * 60 * 60 * 2 / 3
        val long = List(count) { SubtitleSegment(it.toLong(), it * 1_500L, it * 1_500L + 1_400, "Line number $it of the talk.") }
        val whole = transcript(long, subtitle1 = { "b" }, subtitle2 = track { "c" })
        var written = 0L
        val counting =
            object : Writer() {
                override fun write(
                    cbuf: CharArray,
                    off: Int,
                    len: Int,
                ) {
                    written += len
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        val outcome =
            runBlocking {
                whole.export(counting, TranscriptExportOptions(TranscriptExportFormat.SRT, subtitle1 = true, subtitle2 = true), null)
            }
        assertEquals(TranscriptExportOutcome(completed = true), outcome)
        assertTrue("Batches stay within $EXPORT_BATCH_SENTENCES sentences", subtitle2Batches.all { it.size <= EXPORT_BATCH_SENTENCES })
        assertEquals(count, subtitle2Batches.sumOf { it.size })
        assertTrue("Only $written characters written", written > count * 40L)
    }

    @Test
    fun cancellingTheExportStopsAskingForSubtitle2() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val stuck =
                track {
                    started.complete(Unit)
                    awaitCancellation()
                }
            val whole = transcript(subtitle2 = stuck)
            val job =
                launch(Dispatchers.Default) {
                    whole.export(
                        StringWriter(),
                        TranscriptExportOptions(TranscriptExportFormat.SRT, subtitle1 = true, subtitle2 = true),
                        null,
                    )
                }
            started.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            val asked = subtitle1Asked.size
            kotlinx.coroutines.delay(50)
            assertEquals("Nothing is asked after the cancel", asked, subtitle1Asked.size)
        }

    @Test
    fun aVideoThatChangesMidExportEndsItWithoutASuccessAndWithoutMixingVideos() =
        runBlocking {
            val long = List(1_200) { SubtitleSegment(it.toLong(), it * 1_000L, it * 1_000L + 900, "Line $it.") }
            val store = SubtitleStore.create(folder.newFolder(), long)
            val closing =
                track {
                    store.close()
                    "fa"
                }
            val whole = WholeTranscript(store, { "vi" }, null, "vi", closing)
            val out = StringWriter()
            val outcome = whole.export(out, TranscriptExportOptions(TranscriptExportFormat.SRT, subtitle1 = true, subtitle2 = true), null)
            assertFalse(outcome.completed)
            assertEquals(TranscriptExportState.Result.CHANGED, outcome.result)
            assertFalse("Rows of the next video are never read", out.toString().contains("Line 700."))
        }

    /** A document provider's stream: keeps what was written, can fail, and says whether it was closed. */
    private class FakeDocument(
        private val failWrites: Boolean = false,
    ) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var closed = false

        override fun write(b: Int) {
            if (failWrites) throw IOException("provider refused")
            bytes.write(b)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if (failWrites) throw IOException("provider refused")
            bytes.write(b, off, len)
        }

        override fun close() {
            closed = true
        }
    }

    private val both = TranscriptExportOptions(TranscriptExportFormat.SRT, subtitle1 = true, subtitle2 = true)

    @Test
    fun aSavedFileIsWrittenAsUtf8AndTheStreamIsClosed() =
        runBlocking {
            val document = FakeDocument()
            val outcome = writeTranscript(transcript(), { document }, both, null) { _, _ -> }
            assertEquals(TranscriptExportState.Result.SAVED, outcome.result)
            assertTrue(document.closed)
            assertTrue(
                String(
                    document.bytes.toByteArray(),
                    Charsets.UTF_8,
                ).startsWith("1\n00:00:01,000 --> 00:00:03,500\nHello there.\nvi: Hello there.\nfa: Hello there."),
            )
        }

    @Test
    fun aFileThatCannotBeWrittenFailsAndIsNotAMissingTranslationOrAChangedVideo() =
        runBlocking {
            val document = FakeDocument(failWrites = true)
            val outcome = writeTranscript(transcript(), { document }, both, null) { _, _ -> }
            assertEquals(TranscriptExportState.Result.FAILED, outcome.result)
            assertFalse(outcome.changed)
            assertTrue("The stream is closed even when writing failed", document.closed)
            assertEquals(TranscriptExportState.Result.FAILED, writeTranscript(transcript(), { null }, both, null) { _, _ -> }.result)
            assertEquals(
                "A provider that throws anything is a failed file",
                TranscriptExportState.Result.FAILED,
                writeTranscript(transcript(), { throw SecurityException("revoked") }, both, null) { _, _ -> }.result,
            )
        }

    @Test
    fun cancellingClosesTheStreamAndLetsTheCancellationThrough() =
        runBlocking {
            val document = FakeDocument()
            val started = CompletableDeferred<Unit>()
            val stuck =
                track {
                    started.complete(Unit)
                    awaitCancellation()
                }
            val job = launch(Dispatchers.Default) { writeTranscript(transcript(subtitle2 = stuck), { document }, both, null) { _, _ -> } }
            started.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue("The stream is closed after a Stop", document.closed)
        }

    @Test
    fun anEngineEndedByAVideoOrLanguageChangeIsAChangedExportNotAStopOrAFailure() =
        runBlocking {
            // The engine's own load was cancelled while the export itself is still running.
            val ended = transcript(subtitle2 = track { throw CancellationException("load ended") })
            val document = FakeDocument()
            val outcome = writeTranscript(ended, { document }, both, null) { _, _ -> }
            assertEquals(TranscriptExportOutcome(completed = false, changed = true), outcome)
            assertEquals(TranscriptExportState.Result.CHANGED, outcome.result)
            assertTrue(document.closed)
        }

    @Test
    fun rowsOnScreenWhoseEngineEndsAreLeftUntranslatedInsteadOfCrashingTheSheet() =
        runBlocking {
            val whole = transcript(subtitle1 = { throw CancellationException("load ended") })
            assertEquals(emptyMap<Int, String>(), whole.translateStarts(0..2))
        }

    @Test
    fun aClosedStoreIsAChangedExport() =
        runBlocking {
            val store = SubtitleStore.create(folder.newFolder(), rows)
            val whole = WholeTranscript(store, { it }, null, "vi")
            store.close()
            assertEquals(TranscriptExportState.Result.CHANGED, whole.export(StringWriter(), both, null).result)
        }

    @Test
    fun theTranscriptKnowsWhichLinesMayAskTheAi() {
        assertFalse(transcript().subtitle1UsesAi)
        assertFalse(transcript().subtitle2UsesAi)
        val ai =
            runBlocking {
                WholeTranscript(SubtitleStore.create(folder.newFolder(), rows), {
                    it
                }, null, "vi", TranscriptTrack("fr", { it }, null, usesAi = true), subtitle1UsesAi = true)
            }
        assertTrue(ai.subtitle1UsesAi)
        assertTrue(ai.subtitle2UsesAi)
    }
}
