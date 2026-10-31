package com.kienhoang.dualsubreplay.assistant

import com.kienhoang.dualsubreplay.chatgpt.ChatGptErrorKind
import com.kienhoang.dualsubreplay.chatgpt.ChatGptException
import com.kienhoang.dualsubreplay.chatgpt.ChatGptFunctionCall
import com.kienhoang.dualsubreplay.chatgpt.ChatGptResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val FORBIDDEN =
    listOf(
        "background",
        "conversation",
        "max_output_tokens",
        "max_tool_calls",
        "metadata",
        "moderation",
        "multi_agent",
        "prompt",
        "prompt_cache_retention",
        "safety_identifier",
        "temperature",
        "top_logprobs",
        "top_p",
        "truncation",
        "user",
        "previous_response_id",
        "reasoning",
        "include",
    )

private fun request(
    messages: List<AiWireMessage>,
    tools: List<AiTool> = emptyList(),
    model: String = "gpt-x",
    effort: String? = null,
) = AiChatRequest("", model, "", messages, reasoningEffort = effort, tools = tools, format = AiWireFormat.CHATGPT_RESPONSES)

private val speedTool = AiTool("set_speed", "Sets the speed", """{"type":"object","properties":{"speed":{"type":"number"}}}""")

class AiChatGptWireTest {
    private fun body(request: AiChatRequest) = JSONObject(chatGptChatBody(request))

    @Test
    fun everyBodyStoresNothingStreamsAndCarriesOnlyAllowedFields() {
        val json =
            body(
                request(
                    listOf(AiWireMessage(AiRole.SYSTEM, "Be brief."), AiWireMessage(AiRole.USER, "Hi")),
                    tools = listOf(speedTool),
                    effort = "high",
                ),
            )
        assertEquals(false, json.getBoolean("store"))
        assertEquals(true, json.getBoolean("stream"))
        assertEquals("gpt-x", json.getString("model"))
        FORBIDDEN.forEach { assertFalse("$it must be omitted", json.has(it)) }
    }

    @Test
    fun systemTextTravelsAsInstructionsNeverAsASystemItem() {
        val json = body(request(listOf(AiWireMessage(AiRole.SYSTEM, "Guide one."), AiWireMessage(AiRole.SYSTEM, "Guide two."), AiWireMessage(AiRole.USER, "Hi"))))
        assertEquals("Guide one.\n\nGuide two.", json.getString("instructions"))
        val roles = (0 until json.getJSONArray("input").length()).mapNotNull { json.getJSONArray("input").getJSONObject(it).optString("role") }
        assertFalse("system" in roles)
        assertEquals(listOf("user"), roles)
    }

    @Test
    fun aFollowUpCarriesTheEarlierTurnsInOrder() {
        val json =
            body(
                request(
                    listOf(
                        AiWireMessage(AiRole.SYSTEM, "s"),
                        AiWireMessage(AiRole.USER, "What does doch mean?"),
                        AiWireMessage(AiRole.ASSISTANT, "It means yet."),
                        AiWireMessage(AiRole.USER, "Three examples?"),
                    ),
                ),
            )
        val input = json.getJSONArray("input")
        assertEquals(3, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("assistant", input.getJSONObject(1).getString("role"))
        assertEquals("It means yet.", input.getJSONObject(1).getString("content"))
        assertEquals("Three examples?", input.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun picturesAndPdfsAreInRequestInputsNotUploads() {
        val picture = AiAttachment("shot.png", AiAttachmentKind.PICTURE, "data:image/png;base64,AAAA")
        val pdf = AiAttachment("doc.pdf", AiAttachmentKind.PDF, "data:application/pdf;base64,BBBB")
        val content = body(request(listOf(AiWireMessage(AiRole.USER, "Look", listOf(picture, pdf))))).getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("input_image", content.getJSONObject(1).getString("type"))
        assertEquals("data:image/png;base64,AAAA", content.getJSONObject(1).getString("image_url"))
        assertEquals("input_file", content.getJSONObject(2).getString("type"))
        assertEquals("doc.pdf", content.getJSONObject(2).getString("filename"))
        assertEquals("data:application/pdf;base64,BBBB", content.getJSONObject(2).getString("file_data"))
        assertFalse(content.getJSONObject(2).has("file_id"))
    }

    @Test
    fun toolsAreGroupedInOneNamespaceAndOmittedWhenThereAreNone() {
        val withTools = body(request(listOf(AiWireMessage(AiRole.USER, "Hi")), tools = listOf(speedTool))).getJSONArray("tools")
        assertEquals(1, withTools.length())
        val group = withTools.getJSONObject(0)
        assertEquals("namespace", group.getString("type"))
        assertEquals("triplesub", group.getString("name"))
        val function = group.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", function.getString("type"))
        assertEquals("set_speed", function.getString("name"))
        assertTrue(function.getJSONObject("parameters").has("properties"))
        assertFalse(body(request(listOf(AiWireMessage(AiRole.USER, "Hi")))).has("tools"))
    }

    @Test
    fun aToolRoundIsReplayedAsTypedItemsWithTheSameCallId() {
        val call = AiToolCall("call_1", "set_speed", """{"speed":1.5}""", """{"type":"function_call","call_id":"call_1","name":"set_speed","namespace":"triplesub","arguments":"{\"speed\":1.5}"}""")
        val input =
            body(
                request(
                    listOf(
                        AiWireMessage(AiRole.USER, "faster"),
                        AiWireMessage(AiRole.ASSISTANT, "", toolCalls = listOf(call)),
                        AiWireMessage(AiRole.TOOL, "Done.", toolCallId = "call_1"),
                    ),
                    tools = listOf(speedTool),
                ),
            ).getJSONArray("input")
        assertEquals("function_call", input.getJSONObject(1).getString("type"))
        assertEquals("call_1", input.getJSONObject(1).getString("call_id"))
        assertEquals("triplesub", input.getJSONObject(1).getString("namespace"))
        assertFalse("no server ids are sent, nothing is stored", input.getJSONObject(1).has("id"))
        assertEquals("function_call_output", input.getJSONObject(2).getString("type"))
        assertEquals("call_1", input.getJSONObject(2).getString("call_id"))
        assertEquals("Done.", input.getJSONObject(2).getString("output"))
    }

    @Test
    fun theReplyKeepsTheCallAsAReplayableItem() {
        val run = AiExecution(CHATGPT_ACCESS_ID, AiAuthMethod.OAUTH, AiApiFamily.CHATGPT_RESPONSES, "gpt-x", true)
        val reply = ChatGptResult("Hold on", listOf(ChatGptFunctionCall("c9", "pause", "{}", "triplesub")), "gpt-x-1").toAiReply(run)
        assertEquals("Hold on", reply.text)
        assertEquals("the server's model, not the requested one", "gpt-x-1", reply.model)
        assertEquals(run, reply.execution)
        val call = reply.toolCalls.single()
        assertEquals("c9", call.id)
        assertEquals("pause", call.name)
        assertEquals("{}", call.arguments)
        assertEquals("triplesub", JSONObject(call.rawJson).getString("namespace"))
        assertEquals("function_call", JSONObject(call.rawJson).getString("type"))
    }

    @Test
    fun accountErrorsKeepTheirMeaningInTheChat() {
        fun kind(error: ChatGptErrorKind) = ChatGptException(error, "x").toAiChatException(hadTools = false).kind
        assertEquals(AiErrorKind.PLAN_USAGE_LIMIT, kind(ChatGptErrorKind.USAGE_LIMIT))
        assertEquals(AiErrorKind.PLAN_NOT_AVAILABLE, kind(ChatGptErrorKind.NOT_ELIGIBLE))
        assertEquals(AiErrorKind.PLAN_SIGN_IN_REQUIRED, kind(ChatGptErrorKind.SIGN_IN_REQUIRED))
        assertEquals(AiErrorKind.PLAN_SIGN_IN_REQUIRED, kind(ChatGptErrorKind.PLAN_USAGE_NOT_GRANTED))
        assertEquals(AiErrorKind.UNKNOWN_MODEL, kind(ChatGptErrorKind.UNSUPPORTED_MODEL))
        assertEquals(AiErrorKind.BAD_REPLY, kind(ChatGptErrorKind.BAD_REPLY))
        assertEquals(AiErrorKind.SERVER, kind(ChatGptErrorKind.UNAVAILABLE))
    }

    @Test
    fun aRefusedRequestWithToolsIsReportedAsRefusedToolsSoTextChatCanGoOn() {
        val refused = ChatGptException(ChatGptErrorKind.BAD_REQUEST, "400")
        assertEquals(AiErrorKind.UNSUPPORTED_TOOLS, refused.toAiChatException(hadTools = true).kind)
        assertEquals(AiErrorKind.BAD_REQUEST, refused.toAiChatException(hadTools = false).kind)
    }

    @Test
    fun theRouterSendsEachFormatToItsOwnTransportOnly() =
        runBlocking {
            val keyed = mutableListOf<AiWireFormat>()
            val chatGpt = mutableListOf<AiWireFormat>()
            val router = AiTransportRouter(AiChatTransport { keyed += it.format; AiReply("keyed") })
            val keyedRequest = AiChatRequest("u", "m", "k", listOf(AiWireMessage(AiRole.USER, "x")))
            assertEquals("keyed", router.complete(keyedRequest).text)
            // Before ChatGPT is attached a ChatGPT request fails closed instead of reaching a keyed service.
            val chatRequest = request(listOf(AiWireMessage(AiRole.USER, "x")))
            try {
                router.complete(chatRequest)
                error("expected a failure")
            } catch (failure: AiChatException) {
                assertEquals(AiErrorKind.PLAN_SIGN_IN_REQUIRED, failure.kind)
            }
            router.chatGpt = AiChatTransport { chatGpt += it.format; AiReply("gpt") }
            assertEquals("gpt", router.complete(chatRequest).text)
            assertEquals("keyed", router.complete(keyedRequest).text)
            assertEquals(listOf(AiWireFormat.OPENAI_CHAT, AiWireFormat.OPENAI_CHAT), keyed)
            assertEquals(listOf(AiWireFormat.CHATGPT_RESPONSES), chatGpt)
            assertNull(keyed.firstOrNull { it == AiWireFormat.CHATGPT_RESPONSES })
        }
}
