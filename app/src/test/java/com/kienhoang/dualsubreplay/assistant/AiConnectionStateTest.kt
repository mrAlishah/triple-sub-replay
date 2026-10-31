package com.kienhoang.dualsubreplay.assistant

import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class AiConnectionStateTest {
    private fun gpt(
        status: ChatGptStatus,
        signInFailed: Boolean = false,
    ) = chatGptConnectionState(ChatGptUiState(status = status, signInFailed = signInFailed))

    @Test
    fun chatGptStatesMapOneToOne() {
        assertEquals(AiConnectionState.NOT_CONFIGURED, gpt(ChatGptStatus.NOT_CONNECTED))
        assertEquals(AiConnectionState.AUTHORIZATION_REQUIRED, gpt(ChatGptStatus.NOT_CONNECTED, signInFailed = true))
        assertEquals(AiConnectionState.CONNECTING, gpt(ChatGptStatus.CONNECTING))
        assertEquals(AiConnectionState.CONNECTING, gpt(ChatGptStatus.CHECKING))
        assertEquals(AiConnectionState.CONNECTED, gpt(ChatGptStatus.READY))
        assertEquals(AiConnectionState.PERMISSION_MISSING, gpt(ChatGptStatus.NO_PLAN_USAGE))
        assertEquals(AiConnectionState.PERMISSION_MISSING, gpt(ChatGptStatus.NOT_ELIGIBLE))
        assertEquals(AiConnectionState.CREDENTIAL_EXPIRED, gpt(ChatGptStatus.SIGN_IN_REQUIRED))
        assertEquals(AiConnectionState.RATE_LIMITED, gpt(ChatGptStatus.USAGE_LIMIT))
        assertEquals(AiConnectionState.UNAVAILABLE, gpt(ChatGptStatus.TEMPORARY_FAILURE))
    }

    @Test
    fun choosingChatGptIsNotConnecting() {
        // useSubscription is a selection, not a credential: only the status decides.
        val selected = ChatGptUiState(useSubscription = true, status = ChatGptStatus.NOT_CONNECTED)
        assertEquals(AiConnectionState.NOT_CONFIGURED, chatGptConnectionState(selected))
    }

    @Test
    fun keyStatesFollowKeysAndTheLastTest() {
        assertEquals(AiConnectionState.NOT_CONFIGURED, keyConnectionState(0, AiConnectionTest.Passed))
        assertEquals(AiConnectionState.CONNECTED, keyConnectionState(1, AiConnectionTest.Idle))
        assertEquals(AiConnectionState.CONNECTING, keyConnectionState(2, AiConnectionTest.Testing))
        fun failed(kind: AiErrorKind) = keyConnectionState(1, AiConnectionTest.Failed(AiFailure(kind)))
        assertEquals(AiConnectionState.CREDENTIAL_EXPIRED, failed(AiErrorKind.INVALID_KEY))
        assertEquals(AiConnectionState.PERMISSION_MISSING, failed(AiErrorKind.NO_CREDIT))
        assertEquals(AiConnectionState.RATE_LIMITED, failed(AiErrorKind.RATE_LIMITED))
        assertEquals(AiConnectionState.UNAVAILABLE, failed(AiErrorKind.NETWORK))
    }

    @Test
    fun accessMethodComesFromMetadataNotNames() {
        AiProvider.entries.forEach {
            assertEquals(AiAccessMethod.API_KEY, AiConnectionTarget.Key(it).accessMethod)
        }
        assertEquals(AiAccessMethod.SUBSCRIPTION_OAUTH, AiConnectionTarget.ChatGpt.accessMethod)
    }

    @Test
    fun theTranslationModelFollowsTheBoxOnlyOnceNothingOlderCompetes() {
        assertEquals("box", aiTranslationModelChoice("box", "", unified = false, default = "d"))
        assertEquals("d", aiTranslationModelChoice(null, "", unified = false, default = "d"))
        assertEquals("old", aiTranslationModelChoice(null, "old", unified = false, default = "d"))
        assertEquals("old", aiTranslationModelChoice("box", "old", unified = false, default = "d"))
        assertEquals("box", aiTranslationModelChoice("box", "old", unified = true, default = "d"))
        assertEquals("old", aiTranslationModelConflict("box", "old", unified = false))
        assertEquals(null, aiTranslationModelConflict("box", "old", unified = true))
        assertEquals(null, aiTranslationModelConflict("old", "old", unified = false))
        assertEquals(null, aiTranslationModelConflict(null, "old", unified = false))
    }

    @Test
    fun aServiceThinkingLevelBecomesItsTranslationEffort() {
        assertEquals(AiEffort.HIGH, aiTranslationSavedEffort(AiThinking.HIGH, AiEffort.MINIMAL))
        assertEquals(AiEffort.AUTO, aiTranslationSavedEffort(AiThinking.AUTO, AiEffort.MAX))
        assertEquals(AiEffort.MAX, aiTranslationSavedEffort(null, AiEffort.MAX))
    }

    @Test
    fun targetIdsRoundTrip() {
        AiProvider.entries.forEach { assertEquals(AiConnectionTarget.Key(it), aiConnectionTargetFor(AiConnectionTarget.Key(it).id)) }
        assertEquals(AiConnectionTarget.ChatGpt, aiConnectionTargetFor(AiConnectionTarget.ChatGpt.id))
        assertEquals(null, aiConnectionTargetFor("nonsense"))
    }
}
