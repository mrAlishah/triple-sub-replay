package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SubtitleSlotLayoutUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun movingASlotChangesOnlyItsPositionAndTheRoleLabelsStayPut() {
        var order by mutableStateOf(DEFAULT_SUBTITLE_ORDER)
        var rtl by mutableStateOf(SlotRtl())
        rule.setContent {
            val controls =
                TripleSubtitleControls(
                    originalEnabled = true,
                    secondEnabled = false,
                    enabled = true,
                    language = "fa",
                    onOriginalEnabledChange = {},
                    onSecondEnabledChange = {},
                    onEnabledChange = {},
                    onLanguageChange = {},
                    rtl = rtl,
                    order = order,
                    onMove = { slot, delta -> order = order.moved(slot, delta) },
                )
            CompositionLocalProvider(LocalTripleSubtitleControls provides controls) {
                DualSubTheme {
                    SubtitleSlotRow(
                        slot = SubtitleSlot.THIRD,
                        label = 0,
                        checked = true,
                        onCheckedChange = {},
                        languageLabel = "Persian",
                        onPickLanguage = {},
                        tag = "third",
                        languageHint = "Persian",
                    )
                }
            }
        }
        rule.onNodeWithText("Subtitle 2 · Persian").assertExists()
        rule.onNodeWithTag("third_down").assertIsNotEnabled()
        rule.onNodeWithTag("third_up").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(listOf(SubtitleSlot.ORIGINAL, SubtitleSlot.THIRD, SubtitleSlot.SECOND), order)
            // Moving never touches the slot's own settings.
            assertEquals(SlotRtl(), rtl)
        }
        rule.onNodeWithTag("third_down").assertIsEnabled()
    }
}
