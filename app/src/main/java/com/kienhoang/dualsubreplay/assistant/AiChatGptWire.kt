package com.kienhoang.dualsubreplay.assistant

import com.kienhoang.dualsubreplay.chatgpt.ChatGptChatBackend
import com.kienhoang.dualsubreplay.chatgpt.ChatGptErrorKind
import com.kienhoang.dualsubreplay.chatgpt.ChatGptException
import com.kienhoang.dualsubreplay.chatgpt.ChatGptResult
import org.json.JSONArray
import org.json.JSONObject

/** The one namespace the app's actions are grouped in; the plan-usage preview takes function tools in a namespace. */
internal const val CHATGPT_TOOL_NAMESPACE = "triplesub"

/** System text goes in `instructions`: the plan-usage route rejects explicit system items. */
internal fun chatGptInstructions(messages: List<AiWireMessage>): String =
    messages
        .filter { it.role == AiRole.SYSTEM }
        .joinToString("\n\n") { it.content }
        .trim()

private fun userContent(message: AiWireMessage): JSONArray =
    JSONArray().apply {
        put(JSONObject().put("type", "input_text").put("text", message.content))
        message.attachments.forEach { file ->
            when (file.kind) {
                AiAttachmentKind.PICTURE -> put(JSONObject().put("type", "input_image").put("image_url", file.data))
                // In-request data only: the Files upload API is not part of the plan-usage route.
                AiAttachmentKind.PDF -> put(JSONObject().put("type", "input_file").put("filename", file.name).put("file_data", file.data))
                AiAttachmentKind.TEXT -> Unit
            }
        }
    }

/**
 * The conversation as typed Responses items. Nothing is stored on the server, so every request carries what it needs:
 * the kept turns, and inside one answer the function calls and their outputs, replayed without server ids.
 */
internal fun chatGptInputItems(messages: List<AiWireMessage>): JSONArray =
    JSONArray().apply {
        messages.forEach { message ->
            when (message.role) {
                AiRole.SYSTEM -> Unit
                AiRole.USER -> put(JSONObject().put("role", "user").put("content", userContent(message)))
                AiRole.ASSISTANT -> {
                    if (message.content.isNotBlank()) put(JSONObject().put("role", "assistant").put("content", message.content))
                    message.toolCalls.forEach { call -> put(replayableCall(call)) }
                }
                AiRole.TOOL ->
                    put(
                        JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", message.toolCallId.orEmpty())
                            .put("output", message.content),
                    )
            }
        }
    }

private fun replayableCall(call: AiToolCall): JSONObject {
    val raw = runCatching { JSONObject(call.rawJson) }.getOrDefault(JSONObject())
    return JSONObject()
        .put("type", "function_call")
        .put("call_id", call.id)
        .put("name", call.name)
        .put("arguments", call.arguments)
        .also { item -> raw.optString("namespace").takeIf { it.isNotEmpty() }?.let { item.put("namespace", it) } }
}

/** The app's actions as `function` tools inside one namespace. */
internal fun chatGptToolsJson(tools: List<AiTool>): JSONArray =
    JSONArray().put(
        JSONObject()
            .put("type", "namespace")
            .put("name", CHATGPT_TOOL_NAMESPACE)
            .put("description", "Actions the TripleSub Replay app can run for the user.")
            .put(
                "tools",
                JSONArray().apply {
                    tools.forEach { tool ->
                        put(
                            JSONObject()
                                .put("type", "function")
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("parameters", JSONObject(tool.parametersJson)),
                        )
                    }
                },
            ),
    )

/**
 * The Responses request for a chat turn: `store:false`, `stream:true`, the conversation in `input`. Reasoning effort, the
 * service's web search and every field the plan-usage preview rejects are left out on purpose.
 */
internal fun chatGptChatBody(request: AiChatRequest): String =
    JSONObject()
        .put("model", request.model)
        .put("instructions", chatGptInstructions(request.messages))
        .put("input", chatGptInputItems(request.messages))
        .also { body -> if (request.tools.isNotEmpty()) body.put("tools", chatGptToolsJson(request.tools)) }
        .put("store", false)
        .put("stream", true)
        .toString()

/**
 * A completed ChatGPT reply as the chat's [AiReply]; each call keeps the item it will be replayed as. The reply's model is the one
 * the server named, or empty: the requested model is in [execution], never passed off as the server's.
 */
internal fun ChatGptResult.toAiReply(execution: AiExecution): AiReply =
    AiReply(
        text = text,
        toolCalls =
            calls.map { call ->
                val item =
                    JSONObject()
                        .put("type", "function_call")
                        .put("call_id", call.callId)
                        .put("name", call.name)
                        .put("arguments", call.arguments)
                        .also { json -> call.namespace?.let { json.put("namespace", it) } }
                AiToolCall(call.callId, call.name, call.arguments, item.toString())
            },
        model = responseModel.orEmpty(),
        execution = execution,
    )

/** A ChatGPT failure in the chat's words; a refused request that carried tools reads as refused tools so text chat can go on. */
internal fun ChatGptException.toAiChatException(hadTools: Boolean): AiChatException {
    val kind =
        when (kind) {
            ChatGptErrorKind.USAGE_LIMIT -> AiErrorKind.PLAN_USAGE_LIMIT
            ChatGptErrorKind.NOT_ELIGIBLE -> AiErrorKind.PLAN_NOT_AVAILABLE
            ChatGptErrorKind.SIGN_IN_REQUIRED, ChatGptErrorKind.PLAN_USAGE_NOT_GRANTED, ChatGptErrorKind.SIGN_IN_FAILED ->
                AiErrorKind.PLAN_SIGN_IN_REQUIRED
            ChatGptErrorKind.UNSUPPORTED_MODEL -> AiErrorKind.UNKNOWN_MODEL
            ChatGptErrorKind.BAD_REQUEST -> if (hadTools) AiErrorKind.UNSUPPORTED_TOOLS else AiErrorKind.BAD_REQUEST
            ChatGptErrorKind.BAD_REPLY -> AiErrorKind.BAD_REPLY
            ChatGptErrorKind.UNAVAILABLE -> AiErrorKind.SERVER
        }
    return AiChatException(kind, message, this)
}

/**
 * Chat requests in the Responses format, through the same session, refresh lock and gate as the translation. It stamps what it did:
 * a ChatGPT OAuth request with the permission checked. A failure before the request was sent (signed out, no plan-usage permission,
 * a blocking gate) carries no execution, because nothing reached OpenAI.
 */
internal class ChatGptChatTransport(
    private val backend: ChatGptChatBackend,
) : AiChatTransport {
    override suspend fun complete(request: AiChatRequest): AiReply = stream(request) { }

    override suspend fun stream(
        request: AiChatRequest,
        onText: (String) -> Unit,
    ): AiReply {
        var sent = false
        val execution = AiExecution(CHATGPT_ACCESS_ID, AiAuthMethod.OAUTH, AiApiFamily.CHATGPT_RESPONSES, request.model, planUsageVerified = true)
        return try {
            backend.respondTracked(chatGptChatBody(request), onText) { sent = true }.toAiReply(execution)
        } catch (error: ChatGptException) {
            throw error.toAiChatException(hadTools = request.tools.isNotEmpty()).also { if (sent) it.execution = execution }
        }
    }
}

/**
 * Sends each request to the transport that speaks its format: ChatGPT Subscription requests only to [chatGpt], every keyed
 * service to [keyed]. With no ChatGPT account attached a ChatGPT request fails; it never reaches a keyed service.
 */
internal class AiTransportRouter(
    private val keyed: AiChatTransport,
) : AiChatTransport {
    @Volatile var chatGpt: AiChatTransport? = null

    private fun chatGptTransport(): AiChatTransport = chatGpt ?: throw AiChatException(AiErrorKind.PLAN_SIGN_IN_REQUIRED)

    /** Runs a keyed request and stamps who ran it on the reply or the failure; a bad address never left the phone, so it is not stamped. */
    private suspend fun runKeyed(
        request: AiChatRequest,
        call: suspend () -> AiReply,
    ): AiReply {
        val execution = AiExecution(request.providerId, AiAuthMethod.API_KEY, aiApiFamily(request.format), request.model)
        return try {
            call().let { reply -> if (reply.execution == null) reply.copy(execution = execution) else reply }
        } catch (error: AiChatException) {
            if (error.execution == null && error.kind != AiErrorKind.BAD_ADDRESS) error.execution = execution
            throw error
        }
    }

    override suspend fun complete(request: AiChatRequest): AiReply =
        if (request.format == AiWireFormat.CHATGPT_RESPONSES) chatGptTransport().complete(request) else runKeyed(request) { keyed.complete(request) }

    override suspend fun stream(
        request: AiChatRequest,
        onText: (String) -> Unit,
    ): AiReply =
        if (request.format == AiWireFormat.CHATGPT_RESPONSES) {
            chatGptTransport().stream(request, onText)
        } else {
            runKeyed(request) { keyed.stream(request, onText) }
        }
}
