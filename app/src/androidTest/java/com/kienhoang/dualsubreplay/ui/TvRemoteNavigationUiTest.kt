package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.espresso.Espresso.pressBack
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The remote's ▲/▼ leave sliders and fields, ◀/▶ still change a slider, and the menu opens and closes (owner TV test 2026-11-13). */
class TvRemoteNavigationUiTest {
    @get:Rule
    val compose = createComposeRule()

    private var slider by mutableFloatStateOf(0.5f)
    private var text by mutableStateOf("")

    private fun setTvContent(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { DualSubTheme { CompositionLocalProvider(LocalTvLayout provides true) { content() } } }

    private fun press(
        tag: String,
        key: Key,
    ) = compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }

    @Test
    fun upAndDownLeaveASliderAndLeftRightStillChangeIt() {
        setTvContent {
            Column {
                Button(onClick = {}, modifier = Modifier.testTag("above")) { Text("above") }
                Slider(value = slider, onValueChange = { slider = it }, modifier = Modifier.tvArrowsLeave().testTag("slider"))
                Button(onClick = {}, modifier = Modifier.testTag("below")) { Text("below") }
            }
        }
        compose.onNodeWithTag("slider").requestFocus()
        press("slider", Key.DirectionDown)
        compose.onNodeWithTag("below").assertIsFocused()
        compose.onNodeWithTag("slider").requestFocus()
        press("slider", Key.DirectionUp)
        compose.onNodeWithTag("above").assertIsFocused()
        compose.onNodeWithTag("slider").requestFocus()
        val before = slider
        press("slider", Key.DirectionRight)
        assertTrue("▶ raises the slider", slider > before)
        press("slider", Key.DirectionLeft)
        assertTrue("◀ lowers it again", slider <= before + 1e-4f)
    }

    @Test
    fun upAndDownLeaveATextField() {
        setTvContent {
            Column {
                Button(onClick = {}, modifier = Modifier.testTag("above")) { Text("above") }
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.tvArrowsLeave().testTag("field"))
                Button(onClick = {}, modifier = Modifier.testTag("below")) { Text("below") }
            }
        }
        compose.onNodeWithTag("field").requestFocus()
        press("field", Key.DirectionDown)
        compose.onNodeWithTag("below").assertIsFocused()
        compose.onNodeWithTag("field").requestFocus()
        press("field", Key.DirectionUp)
        compose.onNodeWithTag("above").assertIsFocused()
    }

    @Test
    fun searchIsAButtonThatOnlyOKTurnsIntoTypingAndDownPassesOver() {
        var query by mutableStateOf("")
        setTvContent {
            Column {
                SettingsSearchEntry(query, shown = true, onFocusChange = {}) { query = it }
                Button(onClick = {}, modifier = Modifier.testTag("next")) { Text("next") }
            }
        }
        // Focus on the search control opens no text field and no keyboard: only the button exists.
        compose.onNodeWithTag("settings_search").requestFocus()
        compose.onNodeWithTag("tv_search_field").assertDoesNotExist()
        press("settings_search", Key.DirectionDown)
        compose.onNodeWithTag("next").assertIsFocused()
        compose.onNodeWithTag("next").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("settings_search").requestFocus()
        press("settings_search", Key.DirectionCenter)
        compose.onNodeWithTag("tv_search_field").assertIsDisplayed().performTextInput("font")
        compose.onNodeWithTag("tv_search_go").performClick()
        compose.onNodeWithTag("tv_search_field").assertDoesNotExist()
        assertTrue("the submitted query is kept", query == "font")
    }

    @Test
    fun hidingSearchLeavesNoFocusTargetAndDropsTheQuery() {
        var query by mutableStateOf("font")
        var shown by mutableStateOf(true)
        setTvContent {
            Column {
                SettingsSearchEntry(query, shown, onFocusChange = {}) { query = it }
                Button(onClick = {}, modifier = Modifier.testTag("next")) { Text("next") }
            }
        }
        compose.onNodeWithTag("settings_search").assertIsDisplayed()
        shown = false
        compose.waitForIdle()
        compose.onNodeWithTag("settings_search").assertDoesNotExist()
        assertTrue("the hidden search keeps no query", query.isEmpty())
    }

    @Test
    fun theMenuButtonShowsOpensTheDrawerAndBackReturnsToIt() {
        setTvContent {
            AppNavigation(onPractice = {}, onSettings = {}) { menuButton -> menuButton() }
        }
        compose.onNodeWithTag("navigation_menu_button").assertIsDisplayed().requestFocus()
        press("navigation_menu_button", Key.DirectionCenter)
        compose.waitForIdle()
        compose.onNodeWithTag("open_saved_words").assertIsDisplayed()
        closeSystemDialogs()
        pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("navigation_menu_button").assertIsFocused()
    }
}
