package com.kienhoang.dualsubreplay.chatgpt

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val MAX_REPLY_CHARS = 2 * 1024 * 1024

internal data class ChatGptModel(
    val slug: String,
    val displayName: String,
)

/**
 * The only fields the plan-usage preview accepts for what this app needs: no temperature, token limits,
 * metadata, conversation or `previous_response_id`, and the instructions travel as `instructions` because
 * system-role input items are rejected.
 */
internal fun chatGptResponsesBody(
    model: String,
    instructions: String,
    user: String,
): String =
    JSONObject()
        .put("model", model)
        .put("instructions", instructions)
        .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        .put("store", false)
        .put("stream", true)
        .toString()

/** Models the signed-in account may use: `visibility == "list"`, in the server's order. */
internal fun parseChatGptModels(json: String): List<ChatGptModel> =
    try {
        val models = JSONObject(json).getJSONArray("models")
        (0 until models.length())
            .map { models.getJSONObject(it) }
            .filter { it.optString("visibility") == "list" && it.optString("slug").isNotEmpty() }
            .map { ChatGptModel(it.getString("slug"), it.optString("display_name").ifEmpty { it.getString("slug") }) }
    } catch (_: JSONException) {
        emptyList()
    }

private fun kindForCode(
    code: String,
    param: String,
): ChatGptErrorKind? =
    when (code) {
        "subscription_sharing_user_not_eligible",
        "chatpass_v2_scope_not_authorized",
        "chatpass_v2_invalid_authorization_context",
        -> ChatGptErrorKind.NOT_ELIGIBLE
        "subscription_sharing_usage_limit_exceeded" -> ChatGptErrorKind.USAGE_LIMIT
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> ChatGptErrorKind.UNAVAILABLE
        "subscription_sharing_unsupported_capability" ->
            if (param ==
                "model"
            ) {
                ChatGptErrorKind.UNSUPPORTED_MODEL
            } else {
                ChatGptErrorKind.BAD_REQUEST
            }
        "subscription_sharing_route_not_supported" -> ChatGptErrorKind.BAD_REQUEST
        "subscription_sharing_invalid_user" -> ChatGptErrorKind.SIGN_IN_REQUIRED
        else -> null
    }

private fun messageFor(kind: ChatGptErrorKind): String =
    when (kind) {
        ChatGptErrorKind.NOT_ELIGIBLE -> "ChatGPT plan usage is not available for this account."
        ChatGptErrorKind.USAGE_LIMIT -> "The ChatGPT plan usage limit was reached."
        ChatGptErrorKind.UNSUPPORTED_MODEL -> "ChatGPT does not offer this model for the account."
        ChatGptErrorKind.SIGN_IN_REQUIRED -> "ChatGPT sign-in is required."
        ChatGptErrorKind.BAD_REQUEST -> "ChatGPT refused the request."
        ChatGptErrorKind.BAD_REPLY -> "ChatGPT sent an incomplete reply."
        ChatGptErrorKind.PLAN_USAGE_NOT_GRANTED -> "ChatGPT plan usage was not authorized."
        ChatGptErrorKind.SIGN_IN_FAILED -> "ChatGPT sign-in failed."
        ChatGptErrorKind.UNAVAILABLE -> "ChatGPT is temporarily unavailable."
    }

/** Maps an error code and HTTP status to what the app does next. The message never repeats the server's body (it may echo input). */
internal fun chatGptErrorFor(
    status: Int,
    body: String,
): ChatGptException {
    val error =
        try {
            JSONObject(body).let { it.optJSONObject("error") ?: it }
        } catch (_: JSONException) {
            null
        }
    val code = error?.optString("code").orEmpty()
    val param = error?.optString("param").orEmpty()
    val kind =
        kindForCode(code, param)
            ?: when (status) {
                401 -> ChatGptErrorKind.SIGN_IN_REQUIRED
                403 -> ChatGptErrorKind.NOT_ELIGIBLE
                429 -> ChatGptErrorKind.USAGE_LIMIT
                400, 404, 422 -> ChatGptErrorKind.BAD_REQUEST
                else -> ChatGptErrorKind.UNAVAILABLE
            }
    return ChatGptException(kind, messageFor(kind) + (if (code.isNotEmpty()) " ($code, HTTP $status)" else " (HTTP $status)"))
}

/** One function call the model asked for, as the stream carried it. */
internal data class ChatGptFunctionCall(
    val callId: String,
    val name: String,
    /** The arguments as JSON text. */
    val arguments: String,
    /** The namespace the tool was grouped in, sent back with the call when the answer is replayed. */
    val namespace: String? = null,
)

/** A completed reply: its text and the function calls it asked for. */
internal data class ChatGptResult(
    val text: String,
    val calls: List<ChatGptFunctionCall> = emptyList(),
)

private fun functionCallFrom(item: JSONObject?): ChatGptFunctionCall? {
    if (item == null || item.optString("type") != "function_call") return null
    val callId = item.optString("call_id").ifEmpty { return null }
    val name = item.optString("name").ifEmpty { return null }
    return ChatGptFunctionCall(
        callId,
        name,
        item.optString("arguments").ifBlank { "{}" },
        item.optString("namespace").takeIf { it.isNotEmpty() },
    )
}

/**
 * Reads server-sent events from [nextLine] (null at the end) and passes each text delta to [onText]. The result is
 * returned only after `response.completed`; a stream that ends, fails or is incomplete is an error, never a result.
 * Function calls come from `response.output_item.done` and, for any it did not carry, from the completed response.
 */
internal fun readChatGptResponse(
    nextLine: () -> String?,
    onText: (String) -> Unit,
): ChatGptResult {
    val text = StringBuilder()
    val calls = LinkedHashMap<String, ChatGptFunctionCall>()
    while (true) {
        val line = nextLine() ?: break
        if (!line.startsWith("data:")) continue
        val event =
            try {
                JSONObject(line.removePrefix("data:").trim())
            } catch (_: JSONException) {
                continue
            }
        when (event.optString("type")) {
            "response.output_text.delta" -> {
                val delta = event.optString("delta")
                if (delta.isEmpty()) continue
                text.append(delta)
                if (text.length > MAX_REPLY_CHARS) throw ChatGptException(ChatGptErrorKind.BAD_REPLY, "The ChatGPT reply was too large.")
                onText(delta)
            }
            "response.output_item.done" -> functionCallFrom(event.optJSONObject("item"))?.let { calls.putIfAbsent(it.callId, it) }
            "response.completed" -> {
                val output = event.optJSONObject("response")?.optJSONArray("output")
                if (output != null) {
                    for (index in 0 until output.length()) {
                        functionCallFrom(output.optJSONObject(index))?.let { calls.putIfAbsent(it.callId, it) }
                    }
                }
                return ChatGptResult(text.toString(), calls.values.toList())
            }
            "response.incomplete" -> throw ChatGptException(ChatGptErrorKind.BAD_REPLY, messageFor(ChatGptErrorKind.BAD_REPLY))
            "response.failed" -> throw chatGptErrorFor(
                500,
                JSONObject()
                    .put(
                        "error",
                        event.optJSONObject("response")?.optJSONObject("error") ?: JSONObject(),
                    ).toString(),
            )
            "error" -> throw chatGptErrorFor(500, event.toString())
        }
    }
    throw ChatGptException(ChatGptErrorKind.UNAVAILABLE, "The ChatGPT reply ended before it was complete.")
}

/** The translation's reading of the same stream: text only. */
internal fun readChatGptStream(
    nextLine: () -> String?,
    onText: (String) -> Unit,
): String = readChatGptResponse(nextLine, onText).text
