package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantSettings
import com.kienhoang.dualsubreplay.assistant.AiChat
import com.kienhoang.dualsubreplay.assistant.AiChatException
import com.kienhoang.dualsubreplay.assistant.AiChatTransport
import com.kienhoang.dualsubreplay.assistant.AiConnectionTarget
import com.kienhoang.dualsubreplay.assistant.AiErrorKind
import com.kienhoang.dualsubreplay.assistant.AiHistoryStorage
import com.kienhoang.dualsubreplay.assistant.AiKeyAdded
import com.kienhoang.dualsubreplay.assistant.AiKeyStore
import com.kienhoang.dualsubreplay.assistant.AiModelInfo
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.assistant.AiReply
import com.kienhoang.dualsubreplay.assistant.AiSettingsStorage
import com.kienhoang.dualsubreplay.assistant.InMemoryAiMemoryStorage
import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.SecretStorage
import com.kienhoang.dualsubreplay.assistant.AiThinking
import com.kienhoang.dualsubreplay.chatgpt.ChatGptModel
import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Settings → AI Assistant → Connection: API keys for keyed services, the ChatGPT sign-in for the subscription, switching between
 * them without losing a credential, and the Available models picker above the editable Model box. No network.
 */
class AiConnectionPanelUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val requests = mutableListOf<String>()
    private val calls = mutableListOf<String>()
    private var settings = AiAssistantSettings(introSeen = true)
    private var listFails = false
    private var listedModels = listOf(AiModelInfo("gemini-flash-latest"), AiModelInfo("gemini-pro-latest", name = "Gemini Pro"))
    private val secrets = mutableMapOf<String, String>()
    private val gptFlow = MutableStateFlow(ChatGptUiState())

    private val keyStore =
        AiKeyStore(
            object : SecretStorage {
                override fun get(name: String) = secrets[name]

                override fun put(values: Map<String, String>) {
                    secrets += values
                }

                override fun remove(names: List<String>) {
                    names.forEach(secrets::remove)
                }
            },
            object : SecretCipher {
                override fun encrypt(plain: ByteArray) = plain.reversedArray()

                override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
            },
        )

    private val controller by lazy {
        AiAssistantController(
            scope = scope,
            transport =
                AiChatTransport { request ->
                    requests += request.baseUrl
                    AiReply("ok")
                },
            keyStore = keyStore,
            settingsStorage =
                object : AiSettingsStorage {
                    override fun load() = settings

                    override fun save(settings: AiAssistantSettings) {
                        this@AiConnectionPanelUiTest.settings = settings
                    }
                },
            historyStorage =
                object : AiHistoryStorage {
                    override fun load() = emptyList<AiChat>()

                    override fun save(chats: List<AiChat>) = Unit

                    override fun clear() = Unit
                },
            io = Dispatchers.Unconfined,
            modelLister = { _, _, _ -> if (listFails) throw AiChatException(AiErrorKind.NETWORK) else listedModels },
            memoryStorage = InMemoryAiMemoryStorage(),
        )
    }

    private val chatGpt =
        ChatGptActions(
            gptFlow,
            { calls += "select:$it" },
            { calls += "signIn" },
            { calls += "refresh" },
            { calls += "disconnect" },
            { calls += "cancel" },
            { calls += "model:$it" },
            { calls += "test" },
        )

    @After
    fun tearDown() = scope.cancel()

    private fun saveKey(
        provider: AiProvider,
        key: String,
    ) = (keyStore.add(provider, key) as AiKeyAdded.Saved).id

    private val translationChanges = mutableListOf<String>()
    private var translationService = "gemini"
    private val link =
        AiTranslationLink({ translationService }, { "" }) { translationChanges += "changed" }

    private fun show(
        target: AiConnectionTarget? = null,
        widthDp: Int? = null,
    ) {
        val host = AiAssistantHost(controller, chatGpt = chatGpt, translation = link) { "Guide" }
        composeRule.setContent {
            DualSubTheme {
                Surface {
                    Box(if (widthDp != null) Modifier.width(widthDp.dp) else Modifier.fillMaxWidth()) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            AiAssistantSettingsSection(host, connectionTarget = target)
                        }
                    }
                }
            }
        }
    }

    /** The AI service row: which service's settings are shown. */
    private fun chooseService(tag: String) {
        composeRule.onNodeWithTag("ai_service_menu").performScrollTo().performClick()
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitForIdle()
    }

    /** The Chat AI Service row: which service chat uses. */
    private fun chooseChat(tag: String) {
        composeRule.onNodeWithTag("ai_chat_service_menu").performScrollTo().performClick()
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun everyKeyedServiceShowsTheApiKeysAndChatGptShowsTheConnection() {
        show()
        listOf("ai_service_gemini", "ai_service_claude", "ai_service_openai").forEach { service ->
            chooseService(service)
            composeRule.onNodeWithText("API keys").assertIsDisplayed()
            composeRule.onNodeWithTag("ai_key_field").assertExists()
            composeRule.onNodeWithTag("chatgpt_continue").assertDoesNotExist()
        }
        chooseService("ai_service_chatgpt")
        composeRule.onNodeWithText("Connection").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_key_field").assertDoesNotExist()
        composeRule.onNodeWithTag("chatgpt_continue").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("chatgpt_status").assertTextContains("Not connected")
    }

    @Test
    fun aConnectedChatGptShowsItsStatusAndRefreshDisconnectAndTest() {
        gptFlow.value = ChatGptUiState(useSubscription = true, status = ChatGptStatus.READY, account = "me@example.com")
        show()
        chooseService("ai_service_chatgpt")
        composeRule.onNodeWithTag("chatgpt_status").assertTextContains("Connected · plan usage allowed")
        composeRule.onNodeWithTag("chatgpt_continue").assertDoesNotExist()
        composeRule.onNodeWithTag("chatgpt_refresh").performScrollTo().performClick()
        composeRule.onNodeWithTag("chatgpt_test").performScrollTo().performClick()
        composeRule.onNodeWithTag("chatgpt_disconnect").performScrollTo().performClick()
        assertEquals(listOf("refresh", "test", "disconnect"), calls)
        saveUiEvidence("ai-connection-chatgpt-connected", composeRule.onRoot().captureToImage())
    }

    @Test
    fun connectingShowsProgressAndCancelAndTheRealErrorsAreNotHidden() {
        gptFlow.value = ChatGptUiState(useSubscription = true, status = ChatGptStatus.CONNECTING)
        show()
        chooseService("ai_service_chatgpt")
        composeRule.onNodeWithTag("chatgpt_progress").assertExists()
        composeRule.onNodeWithTag("chatgpt_cancel").performScrollTo().performClick()
        listOf(ChatGptStatus.NOT_ELIGIBLE, ChatGptStatus.USAGE_LIMIT, ChatGptStatus.SIGN_IN_REQUIRED, ChatGptStatus.TEMPORARY_FAILURE).forEach {
            gptFlow.value = ChatGptUiState(useSubscription = true, status = it)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chatgpt_status").assertExists()
        }
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun switchingAwayFromChatGptAndBackKeepsTheSessionAndEveryKey() {
        saveKey(AiProvider.GEMINI, "AIza-FAKE-test-key-for-unit-tests-rstu")
        gptFlow.value = ChatGptUiState(useSubscription = true, status = ChatGptStatus.READY, account = "me@example.com")
        show()
        chooseService("ai_service_chatgpt")
        chooseService("ai_service_gemini")
        chooseService("ai_service_chatgpt")
        // The Connection card may be closed; its header says the state.
        composeRule.onNodeWithTag("ai_section_keys").assertTextContains("Connected · plan usage allowed", substring = true)
        assertEquals(ChatGptStatus.READY, gptFlow.value.status)
        assertTrue("disconnect" !in calls)
        assertEquals(1, keyStore.keys(AiProvider.GEMINI).size)
    }

    @Test
    fun choosingChatGptForChatSaysChatCannotUseItYetAndSendsNothing() {
        show()
        chooseChat("ai_chat_service_chatgpt")
        composeRule.onNodeWithTag("ai_chatgpt_chat_unavailable").performScrollTo().assertIsDisplayed()
        assertTrue(settings.chatGptService)
        assertTrue(requests.isEmpty())
        chooseChat("ai_chat_service_gemini")
        composeRule.onNodeWithTag("ai_chatgpt_chat_unavailable").assertDoesNotExist()
        assertFalse(settings.chatGptService)
    }

    @Test
    fun theShownServiceAndTheChatServiceAreIndependent() {
        settings = AiAssistantSettings(introSeen = true, provider = AiProvider.GEMINI)
        show()
        chooseService("ai_service_chatgpt")
        assertFalse(settings.chatGptService)
        assertEquals(AiProvider.GEMINI, settings.provider)
        composeRule.onNodeWithTag("chatgpt_continue").performScrollTo().assertIsDisplayed()
        chooseChat("ai_chat_service_openai")
        assertEquals(AiProvider.OPENAI, settings.provider)
        // Changing chat did not change the service whose settings are shown.
        composeRule.onNodeWithTag("chatgpt_continue").assertIsDisplayed()
        assertTrue(translationChanges.isEmpty())
    }

    @Test
    fun manageOpensTheTranslationServiceWithoutChangingChat() {
        settings = AiAssistantSettings(introSeen = true, provider = AiProvider.GEMINI)
        show(AiConnectionTarget.ChatGpt)
        composeRule.onNodeWithTag("chatgpt_continue").performScrollTo().assertIsDisplayed()
        assertEquals(AiProvider.GEMINI, settings.provider)
        assertFalse(settings.chatGptService)
        // Picking another service in the AI service row takes over from the opened one.
        chooseService("ai_service_gemini")
        composeRule.onNodeWithTag("ai_key_field").assertExists()
        composeRule.onNodeWithTag("chatgpt_continue").assertDoesNotExist()
    }

    @Test
    fun modelAndThinkingAreSavedPerServiceAndTranslationHearsOnlyAboutItsOwn() {
        saveKey(AiProvider.GEMINI, "AIza-FAKE-test-key-for-unit-tests-rstu")
        saveKey(AiProvider.OPENAI, "sk-proj-abcdefghijklmnop1234")
        settings = AiAssistantSettings(introSeen = true, provider = AiProvider.GEMINI)
        translationService = "openai"
        show()
        composeRule.onNodeWithTag("ai_section_model").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_settings_thinking_high").performScrollTo().performClick()
        // Gemini is chat only: translation is not told.
        assertEquals(AiThinking.HIGH, settings.thinkingFor(AiProvider.GEMINI))
        assertTrue(translationChanges.isEmpty())
        chooseService("ai_service_openai")
        composeRule.onNodeWithTag("ai_settings_thinking_low").performScrollTo().performClick()
        assertEquals(AiThinking.LOW, settings.thinkingFor(AiProvider.OPENAI))
        assertEquals(AiThinking.HIGH, settings.thinkingFor(AiProvider.GEMINI))
        assertEquals(1, translationChanges.size)
    }

    @Test
    fun chatGptShowsItsCatalogAboveTheModelBoxAndNoThinking() {
        gptFlow.value =
            ChatGptUiState(
                useSubscription = true,
                status = ChatGptStatus.READY,
                account = "me@example.com",
                models = listOf(ChatGptModel("gpt-x", "GPT X"), ChatGptModel("gpt-y", "GPT Y")),
                model = "gpt-x",
            )
        show()
        chooseService("ai_service_chatgpt")
        composeRule.onNodeWithTag("ai_section_model").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_models_picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_models_picker_gpt-y").performClick()
        assertEquals(listOf("model:gpt-y"), calls)
        composeRule.onNodeWithTag("ai_model").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_settings_thinking_high").assertDoesNotExist()
        composeRule.onNodeWithText("ChatGPT Subscription has no adjustable reasoning effort.").assertExists()
    }

    @Test
    fun managingAnotherKeyedServiceSavesItsKeyAndLeavesTheAssistantAlone() {
        saveKey(AiProvider.GEMINI, "AIza-FAKE-test-key-for-unit-tests-rstu")
        settings = AiAssistantSettings(introSeen = true, provider = AiProvider.GEMINI)
        show(AiConnectionTarget.Key(AiProvider.OPENAI))
        composeRule.onNodeWithTag("ai_key_row").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_key_field").performScrollTo().performTextInput("sk-proj-abcdefghijklmnop1234")
        composeRule.onNodeWithTag("ai_save_key").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, keyStore.keys(AiProvider.OPENAI).size)
        assertEquals(AiProvider.GEMINI, settings.provider)
    }

    @Test
    fun chosenModelFillsTheBoxAndAManualIdSurvivesRefreshAndALateList() {
        saveKey(AiProvider.GEMINI, "AIza-FAKE-test-key-for-unit-tests-rstu")
        settings = AiAssistantSettings(introSeen = true, provider = AiProvider.GEMINI)
        show()
        composeRule.onNodeWithTag("ai_section_model").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_models_picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_models_picker_gemini-pro-latest").performClick()
        assertEquals("gemini-pro-latest", settings.models[AiProvider.GEMINI])
        composeRule.onNodeWithTag("ai_model").performScrollTo().performTextReplacement("custom-provider-model-v2")
        composeRule.onNodeWithTag("ai_models_picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_models_picker_refresh").performClick()
        composeRule.waitForIdle()
        assertEquals("custom-provider-model-v2", settings.models[AiProvider.GEMINI])
        composeRule.onNodeWithText("Custom model").assertExists()
        composeRule.onNodeWithText("Not in the list", substring = true).assertExists()
    }

    @Test
    fun aFailedModelListNeverBlocksTypingAModel() {
        saveKey(AiProvider.GEMINI, "AIza-FAKE-test-key-for-unit-tests-rstu")
        settings = AiAssistantSettings(introSeen = true, provider = AiProvider.GEMINI)
        listFails = true
        show()
        composeRule.onNodeWithTag("ai_section_model").performScrollTo().performClick()
        composeRule.onNodeWithText("Couldn't load the list", substring = true).assertExists()
        composeRule.onNodeWithTag("ai_model").performScrollTo().performTextReplacement("claude-some-future-model")
        assertEquals("claude-some-future-model", settings.models[AiProvider.GEMINI])
    }

    @Test
    fun noKeyMeansNoListButTheModelBoxStillWorks() {
        settings = AiAssistantSettings(introSeen = true, provider = AiProvider.GEMINI)
        show()
        composeRule.onNodeWithTag("ai_section_model").performScrollTo().performClick()
        composeRule.onNodeWithText("Add a key to list models", substring = true).assertExists()
        composeRule.onNodeWithTag("ai_model").performScrollTo().performTextReplacement("gemini-flash-lite-latest")
        assertEquals("gemini-flash-lite-latest", settings.models[AiProvider.GEMINI])
    }

    @Test
    fun everythingFitsAPhoneAtThreeHundredTwentyDp() {
        gptFlow.value = ChatGptUiState(useSubscription = true, status = ChatGptStatus.READY, account = "me@example.com")
        show(widthDp = 320)
        chooseService("ai_service_chatgpt")
        composeRule.onNodeWithTag("chatgpt_disconnect").performScrollTo().assertIsDisplayed()
        saveUiEvidence("ai-connection-chatgpt-320dp", composeRule.onRoot().captureToImage())
    }
}
