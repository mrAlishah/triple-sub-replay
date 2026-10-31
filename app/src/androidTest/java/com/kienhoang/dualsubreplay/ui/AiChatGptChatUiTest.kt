package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.kienhoang.dualsubreplay.assistant.AI_NEWS_VERSION
import com.kienhoang.dualsubreplay.assistant.AiAppSnapshot
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantSettings
import com.kienhoang.dualsubreplay.assistant.AiChat
import com.kienhoang.dualsubreplay.assistant.AiChatRequest
import com.kienhoang.dualsubreplay.assistant.AiChatTransport
import com.kienhoang.dualsubreplay.assistant.AiHistoryStorage
import com.kienhoang.dualsubreplay.assistant.AiKeyStore
import com.kienhoang.dualsubreplay.assistant.AiReply
import com.kienhoang.dualsubreplay.assistant.AiRole
import com.kienhoang.dualsubreplay.assistant.AiSettingsStorage
import com.kienhoang.dualsubreplay.assistant.AiWireFormat
import com.kienhoang.dualsubreplay.assistant.InMemoryAiMemoryStorage
import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.SecretStorage
import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Streams "Hal" and "lo" at once, then waits for [hold] before the reply completes, as a slow ChatGPT answer would. */
private class StreamingTransport : AiChatTransport {
    val requests = mutableListOf<AiChatRequest>()
    var hold = CompletableDeferred<Unit>()

    override suspend fun complete(request: AiChatRequest): AiReply = stream(request) { }

    override suspend fun stream(
        request: AiChatRequest,
        onText: (String) -> Unit,
    ): AiReply {
        requests += request
        onText("Hal")
        onText("lo")
        hold.await()
        return AiReply("Hallo")
    }
}

/** The assistant chat with ChatGPT Subscription as the chat service: streamed draft, Stop, the real account state. No network. */
class AiChatGptChatUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val transport = StreamingTransport()
    private val account = MutableStateFlow(ChatGptUiState(status = ChatGptStatus.READY, model = "gpt-x"))
    private var settings = AiAssistantSettings(introSeen = true, newsSeen = AI_NEWS_VERSION, chatGptService = true)
    private val secrets = mutableMapOf<String, String>()

    private val controller by lazy {
        AiAssistantController(
            scope = scope,
            transport = transport,
            keyStore =
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
                ),
            settingsStorage =
                object : AiSettingsStorage {
                    override fun load() = settings

                    override fun save(settings: AiAssistantSettings) {
                        this@AiChatGptChatUiTest.settings = settings
                    }
                },
            historyStorage =
                object : AiHistoryStorage {
                    override fun load() = emptyList<AiChat>()

                    override fun save(chats: List<AiChat>) = Unit

                    override fun clear() = Unit
                },
            io = Dispatchers.Unconfined,
            memoryStorage = InMemoryAiMemoryStorage(),
        ).also { it.attachChatGpt(account) }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun showPanel() {
        val host = AiAssistantHost(controller) { "Guide" }
        val snapshot = AiAppSnapshot("1.0", "English", "German", "English", "on the device", emptyList())
        composeRule.setContent {
            DualSubTheme {
                Box(Modifier.fillMaxSize()) { AiAssistantPanel(host, emptyList(), { snapshot }, currentLineQuestion = null) }
            }
        }
        composeRule.runOnIdle { controller.openPanel() }
    }

    private fun ask(text: String) {
        composeRule.onNodeWithTag("ai_input").performTextInput(text)
        composeRule.onNodeWithTag("ai_send_button").performClick()
        composeRule.waitForIdle()
    }

    private fun count(tag: String) = composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun noDraft() = count("ai_draft") == 0

    @Test
    fun theReplyAppearsAsItStreamsThenBecomesTheStoredAnswer() {
        showPanel()
        ask("Hello")
        composeRule.onNodeWithTag("ai_draft").assertIsDisplayed().assertTextContains("Hallo")
        assertEquals(0, count("ai_thinking"))
        val request = transport.requests.single()
        assertEquals(AiWireFormat.CHATGPT_RESPONSES, request.format)
        assertEquals("gpt-x", request.model)
        assertEquals("", request.apiKey)
        transport.hold.complete(Unit)
        composeRule.waitUntil(5_000) { noDraft() }
        composeRule.onNodeWithText("Hallo").assertIsDisplayed()
        assertEquals(
            AiRole.ASSISTANT,
            controller.state.value.messages
                .last()
                .role,
        )
        assertEquals("", controller.state.value.draft)
    }

    @Test
    fun stopRemovesTheDraftAndStoresNoAnswer() {
        showPanel()
        ask("Hello")
        composeRule.onNodeWithTag("ai_draft").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_stop_button").performClick()
        composeRule.waitUntil(5_000) { noDraft() }
        assertEquals(
            AiRole.USER,
            controller.state.value.messages
                .last()
                .role,
        )
        assertTrue(controller.state.value.stopped)
    }

    @Test
    fun theModelAndThinkingBarIsNotShownBecauseTheyAreSetInAiConnection() {
        showPanel()
        assertEquals(0, count("ai_effort_button"))
        composeRule.onNodeWithTag("ai_input").assertIsDisplayed()
    }

    @Test
    fun anAccountThatCannotAnswerShowsItsRealStateAndNothingElse() {
        account.value = ChatGptUiState(status = ChatGptStatus.USAGE_LIMIT)
        showPanel()
        composeRule.onNodeWithTag("ai_chat_chatgpt_card").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_chat_chatgpt_status").assertTextContains("usage limit", substring = true)
        assertEquals(0, count("ai_input"))
        assertEquals(0, count("ai_setup_card"))
        assertTrue(transport.requests.isEmpty())
    }
}
