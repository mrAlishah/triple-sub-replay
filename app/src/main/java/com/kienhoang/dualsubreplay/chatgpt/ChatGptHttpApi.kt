package com.kienhoang.dualsubreplay.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val MODELS_URL = "$CHATGPT_RESOURCE/models"
private const val RESPONSES_URL = "$CHATGPT_RESOURCE/responses"
private const val MAX_ERROR_BODY = 16 * 1024L

/** Refresh answers that mean the refresh token is dead and the user must sign in again (errors-and-recovery). */
private val DEAD_REFRESH_TOKEN =
    setOf(
        "invalid_grant",
        "invalid_refresh_token",
        "token_expired",
        "refresh_token_expired",
        "refresh_token_invalidated",
        "refresh_token_reused",
    )

internal fun chatGptTokenFailure(
    status: Int,
    body: String,
): ChatGptException {
    val code =
        try {
            JSONObject(body).let { it.optString("error").ifEmpty { it.optJSONObject("error")?.optString("code").orEmpty() } }
        } catch (_: JSONException) {
            ""
        }
    return when {
        code in DEAD_REFRESH_TOKEN -> ChatGptException(ChatGptErrorKind.SIGN_IN_REQUIRED, "ChatGPT sign-in has ended ($code).")
        code == "invalid_client" -> ChatGptException(ChatGptErrorKind.BAD_REQUEST, "ChatGPT did not accept this app registration.")
        status in 400..499 -> ChatGptException(ChatGptErrorKind.SIGN_IN_FAILED, "ChatGPT refused the sign-in (HTTP $status).")
        else -> ChatGptException(ChatGptErrorKind.UNAVAILABLE, "ChatGPT sign-in is temporarily unavailable (HTTP $status).")
    }
}

internal fun chatGptTokensFrom(json: String): ChatGptTokens {
    try {
        val body = JSONObject(json)
        return ChatGptTokens(
            accessToken = body.getString("access_token"),
            refreshToken = body.optString("refresh_token").takeIf { it.isNotEmpty() },
            idToken = body.optString("id_token").takeIf { it.isNotEmpty() },
            expiresInSeconds = body.optLong("expires_in", 3600),
            scope = body.optString("scope"),
            earliestRefreshAt = body.opt("earliest_refresh_at")?.takeIf { it != JSONObject.NULL }?.toString(),
        )
    } catch (error: JSONException) {
        throw ChatGptException(ChatGptErrorKind.UNAVAILABLE, "ChatGPT sent an unreadable token answer.", error)
    }
}

/** The network side of the ChatGPT sign-in and Responses calls. Tokens are only ever put in headers or form bodies, never URLs or logs. */
internal class ChatGptHttpApi(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
) : ChatGptApi {
    override suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): ChatGptTokens =
        tokenCall(
            FormBody
                .Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", clientId)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("redirect_uri", redirectUri)
                .add("resource", CHATGPT_RESOURCE)
                .build(),
        )

    override suspend fun refresh(
        clientId: String,
        refreshToken: String,
    ): ChatGptTokens =
        tokenCall(
            FormBody
                .Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .add("resource", CHATGPT_RESOURCE)
                .build(),
        )

    override suspend fun revoke(
        clientId: String,
        token: String,
    ) {
        val form =
            FormBody
                .Builder()
                .add("client_id", clientId)
                .add("token", token)
                .add("token_type_hint", "refresh_token")
                .build()
        run(
            Request
                .Builder()
                .url(CHATGPT_REVOKE_URL.toHttpUrl())
                .post(form)
                .build(),
        ) { }
    }

    override suspend fun jwks(): String =
        run(
            Request
                .Builder()
                .url(CHATGPT_JWKS_URL.toHttpUrl())
                .get()
                .build(),
        ) { response ->
            if (!response.isSuccessful) {
                throw ChatGptException(
                    ChatGptErrorKind.UNAVAILABLE,
                    "ChatGPT signing keys are unavailable (HTTP ${response.code}).",
                )
            }
            response.body.string()
        }

    override suspend fun models(accessToken: String): List<ChatGptModel> =
        run(
            Request
                .Builder()
                .url(MODELS_URL.toHttpUrl())
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build(),
        ) { response ->
            if (!response.isSuccessful) throw chatGptErrorFor(response.code, response.peekBody(MAX_ERROR_BODY).string())
            parseChatGptModels(response.body.string())
        }

    override suspend fun stream(
        accessToken: String,
        body: String,
        onText: (String) -> Unit,
    ): String = respond(accessToken, body, onText).text

    override suspend fun respond(
        accessToken: String,
        body: String,
        onText: (String) -> Unit,
    ): ChatGptResult {
        val request =
            Request
                .Builder()
                .url(RESPONSES_URL.toHttpUrl())
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        return run(request) { response ->
            if (!response.isSuccessful) throw chatGptErrorFor(response.code, response.peekBody(MAX_ERROR_BODY).string())
            val source = response.body.source()
            readChatGptResponse({ source.readUtf8Line() }, onText)
        }
    }

    private suspend fun tokenCall(form: FormBody): ChatGptTokens =
        run(
            Request
                .Builder()
                .url(CHATGPT_TOKEN_URL.toHttpUrl())
                .post(form)
                .build(),
        ) { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw chatGptTokenFailure(response.code, text)
            chatGptTokensFrom(text)
        }

    /** Runs the call on the IO pool and cancels it when the coroutine is cancelled, so a closed sheet stops the request. */
    private suspend fun <T> run(
        request: Request,
        read: (Response) -> T,
    ): T {
        val job = coroutineContext[Job]
        return withContext(Dispatchers.IO) {
            val call: Call = client.newCall(request)
            val cancel = job?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use(read)
            } catch (error: ChatGptException) {
                throw error
            } catch (error: IOException) {
                throw ChatGptException(ChatGptErrorKind.UNAVAILABLE, "Could not reach ChatGPT.", error)
            } finally {
                cancel?.dispose()
            }
        }
    }
}
