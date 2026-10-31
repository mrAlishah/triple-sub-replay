package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiConnectionTarget
import com.kienhoang.dualsubreplay.assistant.AiEffort
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.chatgpt.ChatGptModel
import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState
import com.kienhoang.dualsubreplay.translation.TranslationOrigin
import com.kienhoang.dualsubreplay.translation.TranslationProvider
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Fake actions that record what Settings asked for, shared with [OnlineTranslationUiTest]. */
internal fun fakeAiActions(
    state: AiTranslationUiState,
    calls: MutableList<String> = mutableListOf(),
    gpt: ChatGptUiState = ChatGptUiState(),
): AiTranslationActions = fakeAiActions(MutableStateFlow(state), calls, MutableStateFlow(gpt))

internal fun fakeAiActions(
    state: MutableStateFlow<AiTranslationUiState>,
    calls: MutableList<String>,
    gpt: MutableStateFlow<ChatGptUiState>,
): AiTranslationActions =
    AiTranslationActions(
        state = state,
        selectAccess = { calls += "access:$it" },
        ensureModels = { calls += "ensure" },
        refresh = { calls += "refresh" },
        test = { calls += "test" },
        chatGpt =
            ChatGptActions(
                gpt,
                { calls += "select:$it" },
                { calls += "signIn" },
                { calls += "refreshChatGpt" },
                { calls += "disconnect" },
                { calls += "cancel" },
                {},
                {},
            ),
    )

class AiTranslationSettingsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val opened = mutableListOf<AiConnectionTarget>()
    private lateinit var stateFlow: MutableStateFlow<AiTranslationUiState>
    private lateinit var gptFlow: MutableStateFlow<ChatGptUiState>

    private fun show(
        state: AiTranslationUiState,
        gpt: ChatGptUiState = ChatGptUiState(),
        widthDp: Int? = null,
    ) {
        stateFlow = MutableStateFlow(state)
        gptFlow = MutableStateFlow(gpt)
        val actions = fakeAiActions(stateFlow, calls, gptFlow)
        composeRule.setContent {
            DualSubTheme {
                Surface {
                    Box(if (widthDp != null) Modifier.width(widthDp.dp) else Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                            AiConfigurationSection(actions) { opened += it }
                        }
                    }
                }
            }
        }
    }

    private val claude =
        AiTranslationUiState(
            accessId = "claude",
            model = "claude-opus-5-5",
            models = listOf(AiModelChoice("claude-opus-5-5", "Claude Opus 5.5"), AiModelChoice("claude-haiku-5-5", "Claude Haiku 5.5")),
            modelsState = AiModelsState.LOADED,
            savedEffort = AiEffort.HIGH,
            effort = AiEffort.HIGH,
            effortOptions = listOf(AiEffort.AUTO, AiEffort.LOW, AiEffort.MEDIUM, AiEffort.HIGH, AiEffort.XHIGH, AiEffort.MAX),
            keyPresent = true,
        )

    private val chatGpt =
        AiTranslationUiState(
            accessId = "chatgpt",
            model = "m1",
            models = listOf(AiModelChoice("m1", "Model One"), AiModelChoice("m2", "Model Two")),
            modelsState = AiModelsState.LOADED,
        )

    @Test
    fun theProviderDropdownListsChatGptSubscriptionAndTheApiProvidersOnly() {
        show(claude)
        composeRule.onNodeWithTag("ai_provider_field").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("ai_provider_field").performClick()
        listOf(
            "chatgpt",
            "gemini",
            "claude",
            "openai",
            "openrouter",
        ).forEach { composeRule.onNodeWithTag("ai_provider_$it").assertIsEnabled() }
        composeRule.onNodeWithTag("ai_provider_claude_subscription").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_provider_gemini_subscription").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_provider_gemini").performClick()
        assertEquals(listOf("access:gemini"), calls.filter { it.startsWith("access:") })
    }

    @Test
    fun captionsAndTranslationHoldsOnlyTheServiceChoiceItsStatusAndTheTest() {
        show(claude)
        composeRule.onNodeWithTag("ai_provider_field").assertIsDisplayed()
        composeRule.onNodeWithText("Translation AI Service").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_connection_summary").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_test").assertIsDisplayed()
        // Model, effort and every credential control live in Settings → AI Assistant.
        listOf("ai_model_field", "ai_effort_field", "ai_models_refresh", "ai_key_field", "chatgpt_continue").forEach {
            composeRule.onNodeWithTag(it).assertDoesNotExist()
        }
    }

    @Test
    fun theServiceListSaysWhichServicesAreConnected() {
        show(claude.copy(connected = setOf("claude")))
        composeRule.onNodeWithTag("ai_provider_field").performClick()
        composeRule.onNodeWithTag("ai_provider_claude").assertIsDisplayed()
        composeRule.onAllNodesWithText("Connected").assertCountEquals(1)
        composeRule.onAllNodesWithText("Not connected").assertCountEquals(7)
    }

    @Test
    fun chatGptShowsOnlyItsStatusAndAManageLinkWithNoCredentialControls() {
        show(chatGpt, ChatGptUiState(useSubscription = true))
        listOf(
            ChatGptStatus.NOT_CONNECTED,
            ChatGptStatus.NO_PLAN_USAGE,
            ChatGptStatus.SIGN_IN_REQUIRED,
            ChatGptStatus.READY,
            ChatGptStatus.NOT_ELIGIBLE,
            ChatGptStatus.USAGE_LIMIT,
        ).forEach { status ->
            gptFlow.value = ChatGptUiState(useSubscription = true, status = status, account = "me@example.com")
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("ai_connection_summary").assertIsDisplayed()
            listOf(
                "chatgpt_continue",
                "chatgpt_refresh",
                "chatgpt_disconnect",
                "chatgpt_cancel",
                "chatgpt_account",
                "ai_key_field",
                "ai_save_key",
            ).forEach { composeRule.onNodeWithTag(it).assertDoesNotExist() }
        }
        gptFlow.value = ChatGptUiState(useSubscription = true, status = ChatGptStatus.READY, account = "me@example.com")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AI connection: ChatGPT Subscription · Connected · plan usage allowed").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_manage_connection").performClick()
        assertEquals(listOf<AiConnectionTarget>(AiConnectionTarget.ChatGpt), opened)
    }

    @Test
    fun anApiProviderShowsItsKeyStatusBillingNoteAndManageOpensThatProvider() {
        show(claude.copy(keyPresent = false))
        composeRule.onNodeWithTag("ai_connection_summary").assertIsDisplayed()
        composeRule.onNodeWithText("AI connection: Claude API · No API key for this provider.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Claude Pro and Max subscriptions can't be used here.", substring = true).assertExists()
        listOf("ai_key_field", "ai_save_key", "ai_remove_key", "ai_test_connection").forEach {
            composeRule.onNodeWithTag(it).assertDoesNotExist()
        }
        composeRule.onNodeWithTag("ai_manage_connection").performClick()
        assertEquals(listOf<AiConnectionTarget>(AiConnectionTarget.Key(AiProvider.CLAUDE)), opened)
    }

    @Test
    fun testTranslationNamesTheProviderModelEffortAndTheVerifiedOrigin() {
        val outcome =
            AiTestOutcome(
                "claude",
                "claude-opus-5-5",
                AiEffort.HIGH,
                text = "I learn German every day.",
                origin = TranslationOrigin(TranslationProvider.AI_PROVIDER, cached = false, model = "claude/claude-opus-5-5@high"),
            )
        show(claude.copy(test = outcome))
        composeRule.onNodeWithTag("ai_test").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("ai_test_setup").assertIsDisplayed()
        composeRule.onNodeWithText("Claude API · claude-opus-5-5 · High").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_test_result").assertIsDisplayed()
        composeRule.onNodeWithText("Answered by: AI provider").assertIsDisplayed()
        assertEquals(true, "test" in calls)
    }

    @Test
    fun aFailedTestShowsAReadableReasonWithoutDetails() {
        val outcome = AiTestOutcome("claude", "claude-opus-5-5", AiEffort.XHIGH, errorRes = R.string.ai_translation_test_effort_refused)
        show(claude.copy(test = outcome))
        composeRule.onNodeWithTag("ai_test_error").assertIsDisplayed()
        composeRule.onNodeWithText("This model refused the chosen effort. Pick Automatic or another level.").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_test_result").assertDoesNotExist()
    }

    @Test
    fun theTestIsOffUntilTheProviderCanAnswer() {
        show(claude.copy(keyPresent = false))
        composeRule.onNodeWithTag("ai_test").assertIsNotEnabled()
    }

    @Test
    fun everythingFitsAPhoneAtThreeHundredTwentyDp() {
        show(claude, widthDp = 320)
        listOf("ai_provider_field", "ai_connection_card", "ai_test").forEach {
            composeRule.onNodeWithTag(it).assertIsDisplayed()
            assertTrue(it, composeRule.onNodeWithTag(it).getUnclippedBoundsInRoot().width <= 320.dp)
        }
        saveUiEvidence("ai-translation-settings-320dp", composeRule.onRoot().captureToImage())
    }

    @Test
    fun evidenceForTheMainStates() {
        show(chatGpt, ChatGptUiState(useSubscription = true, status = ChatGptStatus.READY, account = "me@example.com"))
        composeRule.onNodeWithTag("ai_connection_summary").assertIsDisplayed()
        saveUiEvidence("ai-translation-settings-chatgpt", composeRule.onRoot().captureToImage())
    }
}
