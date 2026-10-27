package com.kienhoang.dualsubreplay.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.BuildConfig
import com.kienhoang.dualsubreplay.data.AppUpdate
import com.kienhoang.dualsubreplay.data.UpdateChannel
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import com.kienhoang.dualsubreplay.update.APP_AUTO_UPDATE_PREFERENCE
import com.kienhoang.dualsubreplay.update.AppUpdater
import com.kienhoang.dualsubreplay.update.UpdateProblem
import com.kienhoang.dualsubreplay.update.UpdateState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppUpdateUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("dual_sub_preferences", Context.MODE_PRIVATE)

    private val update =
        AppUpdate(
            versionName = "1.7.2",
            downloadUrl = "https://github.com/hoangkien1703/dual-sub-replay/releases/download/v1.7.2/DualSub-Replay.apk",
            sha256 = null,
            checksumUrl = null,
            sizeBytes = 34_251_919,
            pageUrl = "https://github.com/hoangkien1703/dual-sub-replay/releases/tag/v1.7.2",
        )

    @After
    fun forget() {
        preferences.edit().remove(APP_AUTO_UPDATE_PREFERENCE).commit()
        AppUpdater.hideDialog()
    }

    @Test
    fun updateAutomaticallyIsOffUntilTurnedOn() {
        composeRule.setContent {
            DualSubTheme {
                SubtitleSettingsDialog(
                    sourcePreference = "auto",
                    targetLanguage = "vi",
                    availableSourceLanguages = emptyList(),
                    fontScale = 1f,
                    landscapeSplitEnabled = true,
                    onSourceChange = {},
                    onTargetChange = {},
                    onFontScaleChange = {},
                    onLandscapeSplitChange = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag("settings_section_updates").performScrollTo().performClick()
        composeRule.runOnIdle { assertFalse(preferences.getBoolean(APP_AUTO_UPDATE_PREFERENCE, false)) }
        composeRule
            .onNodeWithTag("auto_update_switch")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertTrue(preferences.getBoolean(APP_AUTO_UPDATE_PREFERENCE, false)) }
        saveUiEvidence("app-updates-settings")
    }

    @Test
    fun aNewerVersionOffersUpdateAndNotNow() {
        AppUpdater.showForTest(UpdateState.Available(update))
        composeRule.setContent { DualSubTheme { AppUpdateDialogHost(channel = UpdateChannel.Official) } }
        composeRule.onNodeWithText("Version 1.7.2 is available.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("update_download").assertIsDisplayed()
        composeRule.onNodeWithTag("update_whats_new").assertIsDisplayed()
        saveUiEvidence("app-update-available")
        composeRule.onNodeWithTag("update_not_now").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("update_message")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aLocalBuildOnlyOpensGitHub() {
        AppUpdater.showForTest(UpdateState.Idle)
        composeRule.setContent { DualSubTheme { AppUpdateDialogHost(channel = UpdateChannel.None) } }
        composeRule.onNodeWithTag("update_open_github").assertIsDisplayed()
        composeRule.onNodeWithTag("update_close").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("update_message")).fetchSemanticsNodes().isEmpty() }
    }

    private fun assertBothLinks(
        state: UpdateState,
        channel: UpdateChannel,
    ) {
        AppUpdater.showForTest(state)
        composeRule.setContent { DualSubTheme { AppUpdateDialogHost(channel = channel) } }
        composeRule.onNodeWithTag("update_link_upstream").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("DualSub Replay (Original)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open DualSub Replay (Original) releases on GitHub").assertExists()
        composeRule.onNodeWithTag("update_link_fork").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("TripleSub Replay (Forked)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open TripleSub Replay (Forked) releases on GitHub").assertExists()
    }

    @Test
    fun bothLinksShowWhileChecking() {
        assertBothLinks(UpdateState.Checking, UpdateChannel.Official)
    }

    @Test
    fun bothLinksShowWhenUpToDate() {
        assertBothLinks(UpdateState.UpToDate, UpdateChannel.Official)
    }

    @Test
    fun bothLinksShowWhenAnUpdateIsAvailable() {
        assertBothLinks(UpdateState.Available(update), UpdateChannel.Official)
    }

    @Test
    fun bothLinksShowWhenTheCheckFailed() {
        assertBothLinks(UpdateState.Failed(UpdateProblem.OFFLINE, null), UpdateChannel.Official)
    }

    @Test
    fun bothLinksShowWhenTheBuildCannotUpdateItself() {
        assertBothLinks(UpdateState.Idle, UpdateChannel.None)
    }

    @Test
    fun theLinksOpenTheForkPageFirstAndTheOriginalPageSecond() {
        val opened = mutableListOf<String>()
        AppUpdater.showForTest(UpdateState.UpToDate)
        composeRule.setContent {
            DualSubTheme {
                AppUpdateDialogHost(
                    channel = UpdateChannel.Official,
                    openPage = { _, url ->
                        opened += url
                        true
                    },
                )
            }
        }
        composeRule.onNodeWithTag("update_link_fork").performScrollTo().performClick()
        composeRule.onNodeWithTag("update_link_upstream").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(BuildConfig.FORK_RELEASES_URL, BuildConfig.UPSTREAM_RELEASES_URL), opened)
            assertEquals("https://github.com/mrAlishah/triple-sub-replay/releases", opened[0])
            assertEquals("https://github.com/hoangkien1703/dual-sub-replay/releases", opened[1])
        }
    }

    @Test
    fun noBrowserShowsAMessageUnderTheLinks() {
        AppUpdater.showForTest(UpdateState.Checking)
        composeRule.setContent {
            DualSubTheme {
                AppUpdateDialogHost(
                    channel = UpdateChannel.Official,
                    openPage = { _, _ -> false },
                )
            }
        }
        composeRule.onNodeWithTag("update_link_fork").performScrollTo().performClick()
        composeRule.onNodeWithText("browser", substring = true, ignoreCase = true).assertExists()
    }
}
