package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.chatgpt.ChatGptStatus
import com.kienhoang.dualsubreplay.chatgpt.ChatGptSubscription
import com.kienhoang.dualsubreplay.chatgpt.ChatGptUiState
import kotlinx.coroutines.flow.StateFlow

/** What Settings → Captions & translation → AI can do with the ChatGPT Subscription connection. */
internal class ChatGptActions(
    val state: StateFlow<ChatGptUiState>,
    val select: (Boolean) -> Unit,
    val signIn: () -> Unit,
    val refresh: () -> Unit,
    val disconnect: () -> Unit,
    val cancel: () -> Unit,
    val selectModel: (String) -> Unit,
    val test: () -> Unit,
)

internal fun ChatGptSubscription.actions() =
    ChatGptActions(state, ::select, ::signIn, ::refresh, ::disconnect, ::cancel, ::selectModel, ::test)

@get:StringRes
internal val ChatGptStatus.labelRes: Int
    get() =
        when (this) {
            ChatGptStatus.NOT_CONNECTED -> R.string.chatgpt_status_not_connected
            ChatGptStatus.CONNECTING -> R.string.chatgpt_status_connecting
            ChatGptStatus.CHECKING -> R.string.chatgpt_status_checking
            ChatGptStatus.READY -> R.string.chatgpt_status_ready
            ChatGptStatus.NO_PLAN_USAGE -> R.string.chatgpt_status_no_plan
            ChatGptStatus.NOT_ELIGIBLE -> R.string.chatgpt_status_not_eligible
            ChatGptStatus.SIGN_IN_REQUIRED -> R.string.chatgpt_status_sign_in
            ChatGptStatus.USAGE_LIMIT -> R.string.chatgpt_status_limit
            ChatGptStatus.TEMPORARY_FAILURE -> R.string.chatgpt_status_temporary
        }

internal val ChatGptStatus.isProblem: Boolean
    get() =
        this == ChatGptStatus.NOT_ELIGIBLE || this == ChatGptStatus.USAGE_LIMIT || this == ChatGptStatus.TEMPORARY_FAILURE ||
            this == ChatGptStatus.SIGN_IN_REQUIRED
