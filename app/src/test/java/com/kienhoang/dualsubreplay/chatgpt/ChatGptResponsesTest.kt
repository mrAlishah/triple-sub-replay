package com.kienhoang.dualsubreplay.chatgpt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatGptResponsesTest {
    private val forbidden =
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
        )

    @Test
    fun requestHasOnlyTheDocumentedFieldsAndNoSystemRole() {
        val body = JSONObject(chatGptResponsesBody("gpt-x", "Translate.", "Hallo"))
        assertEquals("gpt-x", body.getString("model"))
        assertEquals("Translate.", body.getString("instructions"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        val input = body.getJSONArray("input")
        assertEquals(1, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("Hallo", input.getJSONObject(0).getString("content"))
        forbidden.forEach { assertFalse(it, body.has(it)) }
        assertFalse(body.toString().contains("\"system\""))
    }

    private fun sse(vararg events: Pair<String, String>) = events.flatMap { (type, data) -> listOf("event: $type", "data: $data", "") }

    private fun read(
        lines: List<String>,
        pieces: MutableList<String> = mutableListOf(),
    ): String {
        val iterator = lines.iterator()
        return readChatGptStream({ if (iterator.hasNext()) iterator.next() else null }, pieces::add)
    }

    @Test
    fun deltasStreamAndTheTextIsReturnedOnlyAfterCompletion() {
        val pieces = mutableListOf<String>()
        val text =
            read(
                sse(
                    "response.created" to """{"type":"response.created"}""",
                    "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"Ich "}""",
                    "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"lerne"}""",
                    "response.completed" to """{"type":"response.completed","response":{"status":"completed"}}""",
                ),
                pieces,
            )
        assertEquals("Ich lerne", text)
        assertEquals(listOf("Ich ", "lerne"), pieces)
    }

    private fun failure(lines: List<String>): ChatGptException {
        try {
            read(lines)
        } catch (error: ChatGptException) {
            return error
        }
        fail("no failure")
        throw IllegalStateException()
    }

    @Test
    fun aStreamThatEndsWithoutCompletionIsNeverASuccess() {
        val partial = sse("response.output_text.delta" to """{"type":"response.output_text.delta","delta":"half"}""")
        assertEquals(ChatGptErrorKind.UNAVAILABLE, failure(partial).kind)
        assertEquals(ChatGptErrorKind.UNAVAILABLE, failure(emptyList()).kind)
    }

    @Test
    fun incompleteAndFailedResponsesAreErrors() {
        val incomplete =
            sse(
                "response.incomplete" to
                    """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""",
            )
        assertEquals(ChatGptErrorKind.BAD_REPLY, failure(incomplete).kind)
        val limit =
            sse(
                "response.failed" to
                    """{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"x"}}}""",
            )
        assertEquals(ChatGptErrorKind.USAGE_LIMIT, failure(limit).kind)
        val topLevel = sse("error" to """{"type":"error","code":"subscription_sharing_user_not_eligible","message":"x"}""")
        assertEquals(ChatGptErrorKind.NOT_ELIGIBLE, failure(topLevel).kind)
    }

    @Test
    fun errorCodesMapToWhatTheAppDoesNext() {
        fun kind(
            status: Int,
            code: String?,
            param: String? = null,
        ) = chatGptErrorFor(status, JSONObject().put("error", JSONObject().put("code", code).put("param", param)).toString()).kind
        assertEquals(ChatGptErrorKind.NOT_ELIGIBLE, kind(403, "subscription_sharing_user_not_eligible"))
        assertEquals(ChatGptErrorKind.USAGE_LIMIT, kind(429, "subscription_sharing_usage_limit_exceeded"))
        assertEquals(ChatGptErrorKind.UNAVAILABLE, kind(503, "subscription_sharing_usage_unavailable"))
        assertEquals(ChatGptErrorKind.UNAVAILABLE, kind(503, "subscription_sharing_user_unavailable"))
        assertEquals(ChatGptErrorKind.UNSUPPORTED_MODEL, kind(400, "subscription_sharing_unsupported_capability", "model"))
        assertEquals(ChatGptErrorKind.BAD_REQUEST, kind(400, "subscription_sharing_unsupported_capability", "temperature"))
        assertEquals(ChatGptErrorKind.BAD_REQUEST, kind(403, "subscription_sharing_route_not_supported"))
        assertEquals(ChatGptErrorKind.NOT_ELIGIBLE, kind(403, "chatpass_v2_scope_not_authorized"))
        assertEquals(ChatGptErrorKind.NOT_ELIGIBLE, kind(403, "chatpass_v2_invalid_authorization_context"))
        assertEquals(ChatGptErrorKind.SIGN_IN_REQUIRED, kind(401, "subscription_sharing_invalid_user"))
    }

    @Test
    fun plainStatusesWithoutAStructuredBodyStillMap() {
        assertEquals(ChatGptErrorKind.SIGN_IN_REQUIRED, chatGptErrorFor(401, """{"detail":"nope"}""").kind)
        assertEquals(ChatGptErrorKind.NOT_ELIGIBLE, chatGptErrorFor(403, "not json").kind)
        assertEquals(ChatGptErrorKind.UNAVAILABLE, chatGptErrorFor(503, "").kind)
        assertEquals(ChatGptErrorKind.USAGE_LIMIT, chatGptErrorFor(429, "").kind)
        assertEquals(ChatGptErrorKind.UNAVAILABLE, chatGptErrorFor(500, "").kind)
    }

    @Test
    fun errorTextNeverCarriesTheServersBody() {
        val error = chatGptErrorFor(400, """{"error":{"message":"secret subtitle text Bearer abc"}}""")
        assertFalse(error.message.orEmpty().contains("secret"))
    }

    @Test
    fun modelCatalogKeepsOnlyListedModelsInServerOrder() {
        val json =
            """{"models":[
                {"slug":"a","display_name":"A","visibility":"list"},
                {"slug":"hidden","display_name":"H","visibility":"hide"},
                {"slug":"b","display_name":"B","visibility":"list"},
                {"slug":"","display_name":"X","visibility":"list"}]}"""
        assertEquals(listOf(ChatGptModel("a", "A"), ChatGptModel("b", "B")), parseChatGptModels(json))
        assertTrue(parseChatGptModels("garbage").isEmpty())
    }
}
