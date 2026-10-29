package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.chatgpt.ChatGptModel
import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatGptSubscriptionUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(state: ChatGptUiState) {
        val flow = MutableStateFlow(state)
        val actions =
            ChatGptActions(
                state = flow,
                select = { calls += "select:$it" },
                signIn = { calls += "signIn" },
                refresh = { calls += "refresh" },
                disconnect = { calls += "disconnect" },
                cancel = { calls += "cancel" },
                selectModel = { calls += "model:$it" },
                test = { calls += "test" },
            )
        composeRule.setContent {
            DualSubTheme {
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AiAccessSettings(actions) { Text("keys panel") }
                    }
                }
            }
        }
    }

    @Test
    fun theKeyedServiceStaysTheDefaultAndChatGptIsOptIn() {
        show(ChatGptUiState())
        composeRule.onNodeWithTag("ai_access_keys").assertIsSelected()
        composeRule.onNodeWithText("keys panel").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_access_chatgpt").performClick()
        assertEquals(listOf("select:true"), calls)
    }

    @Test
    fun notConnectedOffersContinueWithChatGpt() {
        show(ChatGptUiState(useSubscription = true))
        composeRule.onNodeWithTag("chatgpt_status").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with ChatGPT").assertIsDisplayed().performClick()
        assertEquals(listOf("signIn"), calls)
        saveUiEvidence("chatgpt-not-connected", composeRule.onRoot().captureToImage())
    }

    @Test
    fun connectedShowsAccountModelsAndTheTestButton() {
        show(
            ChatGptUiState(
                useSubscription = true,
                status = ChatGptStatus.READY,
                account = "me@example.com",
                models = listOf(ChatGptModel("m1", "Model One"), ChatGptModel("m2", "Model Two")),
                model = "m1",
                testResult = "من هر روز آلمانی یاد می‌گیرم.",
            ),
        )
        composeRule.onNodeWithText("Account: me@example.com").assertIsDisplayed()
        composeRule.onNodeWithTag("chatgpt_model_m1").assertIsSelected()
        composeRule.onNodeWithTag("chatgpt_model_m2").performClick()
        composeRule.onNodeWithTag("chatgpt_test").performClick()
        composeRule.onNodeWithTag("chatgpt_disconnect").performClick()
        composeRule.onNodeWithTag("chatgpt_test_result").assertIsDisplayed()
        assertEquals(listOf("model:m2", "test", "disconnect"), calls)
        saveUiEvidence("chatgpt-connected", composeRule.onRoot().captureToImage())
    }

    @Test
    fun everyProblemStateExplainsItselfAndOffersTheRightWayOut() {
        val flow =
            MutableStateFlow(ChatGptUiState(useSubscription = true, status = ChatGptStatus.NO_PLAN_USAGE, account = "me@example.com"))
        composeRule.setContent {
            val state = flow.collectAsState().value
            DualSubTheme {
                Surface {
                    ChatGptSubscriptionPanel(state, ChatGptActions(flow, {}, { calls += "signIn" }, {}, {}, {}, {}, {}))
                }
            }
        }
        listOf(
            ChatGptStatus.NO_PLAN_USAGE to true,
            ChatGptStatus.SIGN_IN_REQUIRED to true,
            ChatGptStatus.NOT_ELIGIBLE to false,
            ChatGptStatus.USAGE_LIMIT to false,
            ChatGptStatus.TEMPORARY_FAILURE to false,
        ).forEach { (status, offersSignIn) ->
            flow.value = flow.value.copy(status = status)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chatgpt_status").assertIsDisplayed()
            composeRule.onNodeWithTag("chatgpt_disconnect").assertIsDisplayed()
            if (offersSignIn) {
                composeRule.onNodeWithTag("chatgpt_continue").assertIsDisplayed()
            } else {
                composeRule.onNodeWithTag("chatgpt_continue").assertDoesNotExist()
            }
            saveUiEvidence("chatgpt-${status.name.lowercase()}", composeRule.onRoot().captureToImage())
        }
    }

    @Test
    fun connectingCanBeCancelled() {
        show(ChatGptUiState(useSubscription = true, status = ChatGptStatus.CONNECTING))
        composeRule.onNodeWithTag("chatgpt_cancel").performClick()
        assertEquals(listOf("cancel"), calls)
    }
}
