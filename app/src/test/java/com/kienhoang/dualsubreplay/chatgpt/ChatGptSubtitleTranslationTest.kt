package com.kienhoang.dualsubreplay.chatgpt

import com.kienhoang.dualsubreplay.assistant.SecretCipher
import com.kienhoang.dualsubreplay.assistant.SecretStorage
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.ui.translationTexts
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RegressionStorage : SecretStorage {
    private val map = mutableMapOf<String, String>()

    override fun get(name: String) = map[name]

    override fun put(values: Map<String, String>) {
        map.putAll(values)
    }

    override fun remove(names: List<String>) {
        names.forEach(map::remove)
    }
}

private object RegressionCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain

    override fun decrypt(sealed: ByteArray) = sealed
}

/** Answers like the model: every sentence id gets the translation for the target language named in the instructions. */
private class TranslatingApi : ChatGptApi {
    val requests = mutableListOf<Pair<String, String>>()
    private val english =
        mapOf(
            "Ich lerne jeden Tag Deutsch." to "I learn German every day.",
            "Das macht mir Spaß." to "That is fun for me.",
        )
    private val persian =
        mapOf(
            "Ich lerne jeden Tag Deutsch." to "من هر روز آلمانی یاد می‌گیرم.",
            "Das macht mir Spaß." to "این برایم لذت‌بخش است.",
        )

    override suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ) = error("unused")

    override suspend fun refresh(
        clientId: String,
        refreshToken: String,
    ) = error("unused")

    override suspend fun revoke(
        clientId: String,
        token: String,
    ) = Unit

    override suspend fun jwks() = error("unused")

    override suspend fun models(accessToken: String) = emptyList<ChatGptModel>()

    override suspend fun stream(
        accessToken: String,
        body: String,
        onText: (String) -> Unit,
    ): String {
        val request = JSONObject(body)
        val instructions = request.getString("instructions")
        requests += instructions to request.getJSONArray("input").getJSONObject(0).getString("content")
        val table = if (instructions.contains("Persian")) persian else english
        val user = JSONObject(request.getJSONArray("input").getJSONObject(0).getString("content"))
        val sentences = user.getJSONArray("sentences")
        val answer =
            JSONObject()
                .put(
                    "sentences",
                    JSONArray().apply {
                        for (index in 0 until sentences.length()) {
                            val sentence = sentences.getJSONObject(index)
                            val text = sentence.getJSONArray("rows").getString(0)
                            put(JSONObject().put("id", sentence.getInt("id")).put("rows", JSONArray().put(table.getValue(text))))
                        }
                    },
                ).toString()
        // Two pieces, as a stream would deliver them.
        onText(answer.take(10))
        onText(answer.drop(10))
        return answer
    }
}

class ChatGptSubtitleTranslationTest {
    private val rows =
        listOf(
            SubtitleSegment(0, 0, 2_000, "Ich lerne jeden Tag Deutsch."),
            SubtitleSegment(1, 2_000, 4_000, "Das macht mir Spaß."),
        )
    private val texts = translationTexts(rows)

    private fun credentials(subject: String) =
        ChatGptCredentials("oaiapp_1", subject, null, "id", "access", "refresh", Long.MAX_VALUE / 2, setOf(CHATGPT_PLAN_SCOPE), null)

    private fun model(
        api: TranslatingApi,
        subject: String = "sub-1",
    ): ChatGptTranslationModel {
        val store = ChatGptCredentialStore(RegressionStorage(), RegressionCipher).also { it.save(credentials(subject)) }
        return ChatGptTranslationModel("gpt-x", credentials(subject).cacheScope("gpt-x"), ChatGptSession(store, api), api, ChatGptGate())
    }

    @Test
    fun subtitleOneAndSubtitleTwoAreEachTranslatedStraightFromTheGermanOriginal() =
        runBlocking {
            val api = TranslatingApi()
            val chatGpt = model(api)
            val translator = AiSubtitleTranslator(cacheDirectory = null)
            translator.translateAll(chatGpt, "de", "en", texts)
            translator.translateAll(chatGpt, "de", "fa", texts)

            assertEquals("I learn German every day.", translator.cachedTranslation(chatGpt, "de", "en", rows[0].originalText))
            assertEquals("That is fun for me.", translator.cachedTranslation(chatGpt, "de", "en", rows[1].originalText))
            assertEquals("من هر روز آلمانی یاد می‌گیرم.", translator.cachedTranslation(chatGpt, "de", "fa", rows[0].originalText))
            assertEquals("این برایم لذت‌بخش است.", translator.cachedTranslation(chatGpt, "de", "fa", rows[1].originalText))
            // Each slot asked once, always with the German text, and never with the other slot's translation.
            assertEquals(2, api.requests.size)
            assertTrue(api.requests.all { (_, user) -> user.contains("Ich lerne jeden Tag Deutsch.") && !user.contains("I learn German") })
            assertTrue(api.requests[0].first.contains("English") && api.requests[1].first.contains("Persian"))
        }

    @Test
    fun theSameSlotIsNotAskedTwice() =
        runBlocking {
            val api = TranslatingApi()
            val chatGpt = model(api)
            val translator = AiSubtitleTranslator(cacheDirectory = null)
            translator.translateAll(chatGpt, "de", "fa", texts)
            translator.translateAll(chatGpt, "de", "fa", texts)
            assertEquals(1, api.requests.size)
        }

    @Test
    fun anotherAccountNeverSeesTheFirstAccountsTranslations() =
        runBlocking {
            val api = TranslatingApi()
            val first = model(api, "sub-1")
            val second = model(api, "sub-2")
            val translator = AiSubtitleTranslator(cacheDirectory = null)
            translator.translateAll(first, "de", "fa", texts)
            assertNotEquals(first.scope, second.scope)
            assertNull(translator.cachedTranslation(second, "de", "fa", rows[0].originalText))
        }
}
