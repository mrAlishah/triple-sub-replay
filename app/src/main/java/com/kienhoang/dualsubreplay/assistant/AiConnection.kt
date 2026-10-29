package com.kienhoang.dualsubreplay.assistant

import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState

/** How a service is reached; the settings UI branches on this, never on a display name. */
internal enum class AiAccessMethod { API_KEY, SUBSCRIPTION_OAUTH }

/** One credential the settings page can manage: a keyed service or the ChatGPT Subscription sign-in. */
internal sealed interface AiConnectionTarget {
    val accessMethod: AiAccessMethod

    /** Stable id: the provider's key, or [CHATGPT_ACCESS_ID]; the same ids as the translation assignment. */
    val id: String
        get() = (this as? Key)?.provider?.key ?: CHATGPT_ACCESS_ID

    data class Key(
        val provider: AiProvider,
    ) : AiConnectionTarget {
        override val accessMethod get() = AiAccessMethod.API_KEY
    }

    data object ChatGpt : AiConnectionTarget {
        override val accessMethod get() = AiAccessMethod.SUBSCRIPTION_OAUTH
    }
}

internal fun aiConnectionTargetFor(id: String?): AiConnectionTarget? =
    when (id) {
        null -> null
        CHATGPT_ACCESS_ID -> AiConnectionTarget.ChatGpt
        else -> AiProvider.entries.firstOrNull { it.key == id }?.let(AiConnectionTarget::Key)
    }

/** What a credential can do right now. Choosing a service in a dropdown is never one of these. */
internal enum class AiConnectionState {
    NOT_CONFIGURED,
    CONNECTING,
    CONNECTED,
    CREDENTIAL_EXPIRED,
    AUTHORIZATION_REQUIRED,
    PERMISSION_MISSING,
    RATE_LIMITED,
    UNAVAILABLE,
}

internal fun chatGptConnectionState(state: ChatGptUiState): AiConnectionState =
    when (state.status) {
        ChatGptStatus.NOT_CONNECTED ->
            if (state.signInFailed) AiConnectionState.AUTHORIZATION_REQUIRED else AiConnectionState.NOT_CONFIGURED
        ChatGptStatus.CONNECTING, ChatGptStatus.CHECKING -> AiConnectionState.CONNECTING
        ChatGptStatus.READY -> AiConnectionState.CONNECTED
        ChatGptStatus.NO_PLAN_USAGE, ChatGptStatus.NOT_ELIGIBLE -> AiConnectionState.PERMISSION_MISSING
        ChatGptStatus.SIGN_IN_REQUIRED -> AiConnectionState.CREDENTIAL_EXPIRED
        ChatGptStatus.USAGE_LIMIT -> AiConnectionState.RATE_LIMITED
        ChatGptStatus.TEMPORARY_FAILURE -> AiConnectionState.UNAVAILABLE
    }

/** A saved key counts as connected until a test says otherwise; no key is not configured. */
internal fun keyConnectionState(
    keyCount: Int,
    test: AiConnectionTest,
): AiConnectionState =
    when {
        keyCount == 0 -> AiConnectionState.NOT_CONFIGURED
        test == AiConnectionTest.Testing -> AiConnectionState.CONNECTING
        test is AiConnectionTest.Failed ->
            when (test.failure.kind) {
                AiErrorKind.INVALID_KEY, AiErrorKind.KEY_UNREADABLE -> AiConnectionState.CREDENTIAL_EXPIRED
                AiErrorKind.NO_CREDIT -> AiConnectionState.PERMISSION_MISSING
                AiErrorKind.RATE_LIMITED -> AiConnectionState.RATE_LIMITED
                else -> AiConnectionState.UNAVAILABLE
            }
        else -> AiConnectionState.CONNECTED
    }
