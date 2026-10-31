package com.kienhoang.dualsubreplay.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatGptHttpApiTest {
    @Test
    fun everyDeadRefreshTokenAnswerMeansSignInAgain() {
        listOf(
            "invalid_grant",
            "invalid_refresh_token",
            "token_expired",
            "refresh_token_expired",
            "refresh_token_invalidated",
            "refresh_token_reused",
        ).forEach {
            assertEquals(it, ChatGptErrorKind.SIGN_IN_REQUIRED, chatGptTokenFailure(400, """{"error":"$it"}""").kind)
        }
    }

    @Test
    fun passingFailuresKeepTheSession() {
        assertEquals(ChatGptErrorKind.UNAVAILABLE, chatGptTokenFailure(503, "").kind)
        assertEquals(ChatGptErrorKind.UNAVAILABLE, chatGptTokenFailure(500, "<html>").kind)
        assertEquals(ChatGptErrorKind.BAD_REQUEST, chatGptTokenFailure(401, """{"error":"invalid_client"}""").kind)
        assertEquals(ChatGptErrorKind.SIGN_IN_FAILED, chatGptTokenFailure(400, """{"error":"invalid_request"}""").kind)
    }

    @Test
    fun tokenAnswerIsReadWithItsOptionalFields() {
        val tokens =
            chatGptTokensFrom(
                """{"access_token":"a","refresh_token":"r","id_token":"i","expires_in":3600,"scope":"openid","earliest_refresh_at":"2026-10-29T10:00:00Z"}""",
            )
        assertEquals(ChatGptTokens("a", "r", "i", 3600, "openid", "2026-10-29T10:00:00Z"), tokens)
        val minimal = chatGptTokensFrom("""{"access_token":"a","scope":"openid"}""")
        assertNull(minimal.refreshToken)
        assertNull(minimal.earliestRefreshAt)
        assertEquals(3600, minimal.expiresInSeconds)
    }

    @Test
    fun anUnreadableTokenAnswerIsATemporaryFailure() {
        try {
            chatGptTokensFrom("not json")
            fail()
        } catch (error: ChatGptException) {
            assertEquals(ChatGptErrorKind.UNAVAILABLE, error.kind)
            assertTrue(error.message.orEmpty().isNotEmpty())
        }
    }
}
