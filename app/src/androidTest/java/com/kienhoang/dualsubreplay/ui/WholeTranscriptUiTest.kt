package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The Whole transcript sheet (25kiuo, Discussion #81, 2026-11-17). */
class WholeTranscriptUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.cacheDir, "whole-transcript-test").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    /** A 2-hour talk with a line every 6 seconds; one line in the middle says "kimchi". */
    private fun transcript(): WholeTranscript =
        runBlocking {
            val rows =
                List(1_200) { index ->
                    val text = if (index == 600) "Let's make kimchi together." else "This is line ${index + 1} of the talk."
                    SubtitleSegment(index.toLong(), index * 6_000L, index * 6_000L + 5_000, text)
                }
            WholeTranscript(
                SubtitleStore.create(folder, rows),
                { text -> "Dịch: $text" },
                null,
                "vi",
                TranscriptTrack("fr", { text -> "Traduction: $text" }, null),
            )
        }

    @Test
    fun theSheetOpensAtThePlayingLineTranslatesItAndJumpsWhereTapped() {
        val whole = transcript()
        var jumped: SubtitleSegment? = null
        var closed = false
        composeRule.setContent {
            DualSubTheme {
                WholeTranscriptSheet(
                    transcript = whole,
                    currentStartMs = 60_000,
                    exporter = TranscriptExporter(scope, context.contentResolver),
                    title = "Kimchi talk",
                    onJump = { jumped = it },
                    onDismiss = { closed = true },
                )
            }
        }
        composeRule.onNodeWithTag("whole_transcript").assertIsDisplayed()
        // Opens at the playing line (row 10 starts at 1:00), not at the start.
        composeRule.onNodeWithTag("whole_transcript_row_10").assertIsDisplayed()
        // A row is clickable, so its translation's tag lives in the unmerged tree.
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithTag("whole_transcript_translation_10", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Dịch: This is line 11 of the talk.").assertIsDisplayed()
        saveUiEvidence("whole-transcript")
        composeRule.onNodeWithTag("whole_transcript_search").performTextInput("kimchi")
        // Waits for the row's text, which loads from disk after the row appears.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Let's make kimchi together.").fetchSemanticsNodes().isNotEmpty() }
        saveUiEvidence("whole-transcript-search")
        composeRule.onNodeWithTag("whole_transcript_row_600").performClick()
        composeRule.runOnIdle {
            assertEquals(3_600_000L, jumped?.startMs)
            assertTrue(closed)
        }
    }

    @Test
    fun exportAsksForTheFormatAndTheTwoSubtitles() {
        val whole = transcript()
        composeRule.setContent {
            DualSubTheme {
                WholeTranscriptSheet(whole, null, TranscriptExporter(scope, context.contentResolver), "Kimchi talk", {}, {})
            }
        }
        composeRule.onNodeWithTag("whole_transcript_export").performClick()
        composeRule.onNodeWithTag("whole_transcript_format_srt").assertIsDisplayed()
        composeRule.onNodeWithTag("whole_transcript_with_subtitle1").assertIsDisplayed()
        composeRule.onNodeWithTag("whole_transcript_with_subtitle2").assertIsDisplayed()
        Thread.sleep(500)
        saveUiEvidence("whole-transcript-export")
    }

    private fun showExportDialog(
        subtitle2Language: String?,
        onExport: (TranscriptExportOptions) -> Unit = {},
    ) = composeRule.setContent {
        DualSubTheme { ExportDialog("vi", subtitle2Language, onDismiss = {}, onExport = onExport) }
    }

    @Test
    fun subtitle1IsOnAndSubtitle2IsOffByDefault() {
        showExportDialog("fr")
        composeRule.onNodeWithTag("whole_transcript_with_subtitle1").assertIsDisplayed().assertIsOn()
        composeRule
            .onNodeWithTag("whole_transcript_with_subtitle2")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertIsOff()
    }

    @Test
    fun theTwoSubtitleBoxesToggleIndependently() {
        showExportDialog("fr")
        composeRule.onNodeWithTag("whole_transcript_with_subtitle2").performClick().assertIsOn()
        composeRule.onNodeWithTag("whole_transcript_with_subtitle1").assertIsOn()
        composeRule.onNodeWithTag("whole_transcript_with_subtitle1").performClick().assertIsOff()
        composeRule.onNodeWithTag("whole_transcript_with_subtitle2").assertIsOn()
        composeRule.onNodeWithTag("whole_transcript_with_subtitle2").performClick().assertIsOff()
        composeRule.onNodeWithTag("whole_transcript_with_subtitle1").assertIsOff()
    }

    @Test
    fun theChosenFormatAndSubtitlesReachTheExporter() {
        val picked = mutableListOf<TranscriptExportOptions>()
        showExportDialog("fr") { picked += it }
        composeRule.onNodeWithTag("whole_transcript_export_save").performClick()
        composeRule.onNodeWithTag("whole_transcript_format_srt").performClick()
        composeRule.onNodeWithTag("whole_transcript_with_subtitle2").performClick()
        composeRule.onNodeWithTag("whole_transcript_export_save").performClick()
        composeRule.onNodeWithTag("whole_transcript_with_subtitle1").performClick()
        composeRule.onNodeWithTag("whole_transcript_export_save").performClick()
        composeRule.onNodeWithTag("whole_transcript_with_subtitle2").performClick()
        composeRule.onNodeWithTag("whole_transcript_export_save").performClick()
        assertEquals(
            listOf(
                TranscriptExportOptions(TranscriptExportFormat.TEXT, subtitle1 = true, subtitle2 = false),
                TranscriptExportOptions(TranscriptExportFormat.SRT, subtitle1 = true, subtitle2 = true),
                TranscriptExportOptions(TranscriptExportFormat.SRT, subtitle1 = false, subtitle2 = true),
                TranscriptExportOptions(TranscriptExportFormat.SRT, subtitle1 = false, subtitle2 = false),
            ),
            picked,
        )
    }

    @Test
    fun subtitle2IsDisabledAndExplainedWhenItIsOffOrRepeatsAnotherLine() {
        val picked = mutableListOf<TranscriptExportOptions>()
        showExportDialog(null) { picked += it }
        composeRule
            .onNodeWithTag("whole_transcript_with_subtitle2")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertIsOff()
        composeRule.onNodeWithTag("whole_transcript_subtitle2_unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("whole_transcript_with_subtitle2").performClick().assertIsOff()
        composeRule.onNodeWithTag("whole_transcript_export_save").performClick()
        assertEquals(listOf(TranscriptExportOptions(TranscriptExportFormat.TEXT, subtitle1 = true, subtitle2 = false)), picked)
    }

    @Test
    fun theAiQuotaNoteShowsOnlyWhileAChosenSubtitleMayAskTheAi() {
        composeRule.setContent {
            DualSubTheme { ExportDialog("vi", "fr", subtitle1UsesAi = false, subtitle2UsesAi = true, onDismiss = {}, onExport = {}) }
        }
        // Subtitle 1 (no AI) is the only one chosen: nothing is asked of the AI.
        composeRule.onAllNodesWithTag("whole_transcript_ai_note").assertCountEquals(0)
        composeRule.onNodeWithTag("whole_transcript_with_subtitle2").performClick()
        composeRule.onNodeWithTag("whole_transcript_ai_note").assertIsDisplayed()
        composeRule.onNodeWithTag("whole_transcript_with_subtitle2").performClick()
        composeRule.onAllNodesWithTag("whole_transcript_ai_note").assertCountEquals(0)
    }

    @Test
    fun noAiNoteWhenTheEnginesDoNotUseTheAi() {
        showExportDialog("fr")
        composeRule.onNodeWithTag("whole_transcript_with_subtitle2").performClick()
        composeRule.onAllNodesWithTag("whole_transcript_ai_note").assertCountEquals(0)
    }

    @Test
    fun subtitle2HasNoExplanationWhenItIsAvailable() {
        showExportDialog("fr")
        composeRule.onAllNodesWithTag("whole_transcript_subtitle2_unavailable").assertCountEquals(0)
    }

    @Test
    fun theGearPopupOpensTheWholeTranscript() {
        var opened = false
        composeRule.setContent {
            DualSubTheme {
                QuickLanguageSettingsDialog(
                    sourcePreference = "auto",
                    targetLanguage = "vi",
                    availableSourceLanguages = listOf(CaptionLanguage("en", "English")),
                    onSourceChange = {},
                    onTargetChange = {},
                    onOpenAllSettings = {},
                    onOpenWholeTranscript = { opened = true },
                    onDismiss = {},
                )
            }
        }
        Thread.sleep(700)
        saveUiEvidence("gear-popup-whole-transcript")
        composeRule.onNodeWithTag("open_whole_transcript").performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }
}
