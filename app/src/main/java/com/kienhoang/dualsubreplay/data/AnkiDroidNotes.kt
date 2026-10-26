package com.kienhoang.dualsubreplay.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** AnkiDroid's public content provider and the permission it asks for (no AnkiDroid library). */
internal const val ANKIDROID_AUTHORITY = "com.ichi2.anki.flashcards"
internal const val ANKIDROID_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
internal const val ANKIDROID_PACKAGE = "com.ichi2.anki"

internal const val ANKI_DECK_NAME = "DualSub Replay"
internal const val ANKI_NOTE_TYPE_NAME = "DualSub Replay"
internal val ANKI_FIELDS = listOf("Word", "Meaning", "Reading", "Sentence", "Translation", "Source")

/** Anki keeps a note's fields in one text, separated by this character. */
internal const val ANKI_FIELD_SEPARATOR = "\u001f"

internal const val ANKI_TAG = "DualSubReplay"

internal const val ANKI_QUESTION_FORMAT = "<div class=word>{{Word}}</div><div class=sentence>{{Sentence}}</div>"
internal const val ANKI_ANSWER_FORMAT =
    "{{FrontSide}}<hr id=answer><div class=reading>{{Reading}}</div><div class=meaning>{{Meaning}}</div>" +
        "<div class=translation>{{Translation}}</div><div class=source>{{Source}}</div>"
internal const val ANKI_CSS =
    ".card{font-family:sans-serif;font-size:22px;text-align:center;}.word{font-size:34px;}" +
        ".sentence,.translation{font-size:18px;margin-top:12px;}.reading{font-size:18px;}.source{font-size:14px;margin-top:12px;}"

private const val SECONDS_PER_MINUTE = 60
private const val MS_PER_SECOND = 1_000

/** [text] as Anki field HTML: special characters escaped and line breaks kept. */
internal fun ankiHtml(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        .replace("\r\n", "\n")
        .replace("\n", "<br>")

/** The sentence as Anki HTML with the first appearance of [word] in bold. */
internal fun ankiSentenceHtml(
    sentence: String,
    word: String,
): String {
    val at = if (word.isBlank()) -1 else sentence.indexOf(word, ignoreCase = true)
    if (at < 0) return ankiHtml(sentence)
    val end = at + word.length
    return ankiHtml(sentence.substring(0, at)) + "<b>" + ankiHtml(sentence.substring(at, end)) + "</b>" + ankiHtml(sentence.substring(end))
}

/** A link to the line's moment in the video, or empty text when the word has no video. */
internal fun ankiSourceLink(
    videoId: String?,
    startMs: Long,
): String {
    if (videoId == null || !videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return ""
    val seconds = (startMs.coerceAtLeast(0) / MS_PER_SECOND).toInt()
    val label = String.format(Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
    return "<a href=\"https://www.youtube.com/watch?v=$videoId&amp;t=${seconds}s\">YouTube $label</a>"
}

/** The fields of [word]'s note, in [ANKI_FIELDS] order. */
internal fun ankiNoteFields(word: SavedWord): List<String> =
    listOf(
        ankiHtml(word.word),
        ankiMeaningHtml(word),
        ankiHtml(word.reading.orEmpty()),
        ankiSentenceHtml(word.sentence, word.word),
        ankiTranslationHtml(word),
        ankiSourceLink(word.videoId, word.startMs),
    )

/** The Meaning field: the primary meaning, then each further language as its own labeled right-to-left-safe block. */
internal fun ankiMeaningHtml(word: SavedWord): String =
    ankiHtml(word.meaning) +
        word.meanings.entries.joinToString("") { (language, text) -> "<div dir=\"auto\">${ankiHtml(language)}: ${ankiHtml(text)}</div>" }

/** The Translation field: the second line, then each further sentence translation as its own right-to-left-safe block. */
internal fun ankiTranslationHtml(word: SavedWord): String =
    ankiHtml(word.translatedSentence.orEmpty()) +
        word.sentenceTranslations.values.joinToString("") { "<div dir=\"auto\">${ankiHtml(it)}</div>" }

/** The words of [words] that were not sent before, by id. */
internal fun unsentAnkiWords(
    words: List<SavedWord>,
    sent: (String) -> Boolean,
): List<SavedWord> = words.distinctBy { it.id }.filterNot { sent(it.id) }

/**
 * Why a send stopped: AnkiDroid missing or not permitted, nothing answering at AnkiConnect's address, a refusal,
 * or (AnkiDroid) the step that failed: the deck, the note type, a note type of the same name with other fields,
 * or a note AnkiDroid did not confirm.
 */
internal enum class AnkiProblem {
    NOT_INSTALLED,
    NO_PERMISSION,
    NOT_RUNNING,
    FAILED,
    DECK_FAILED,
    NOTE_TYPE_FAILED,
    NOTE_TYPE_CONFLICT,
    INSERT_FAILED,
    WRONG_DECK,
}

/**
 * What one send did: notes added, words sent before (skipped), and any problem that stopped it,
 * with AnkiConnect's own error text when it gave one.
 */
internal data class AnkiSendResult(
    val sent: Int = 0,
    val skipped: Int = 0,
    val problem: AnkiProblem? = null,
    val detail: String? = null,
)

/**
 * Adds notes to AnkiDroid through its content provider ([AnkiDroidSender]): a "TripleSub Replay" deck and a
 * "TripleSub Replay v2" note type are made the first time (the older "DualSub Replay" deck and note type are left
 * alone). Which words were sent is kept on the phone, so a word is not sent twice.
 */
internal class AnkiDroidNotes(
    context: Context,
) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    // Its own list for the TripleSub Replay v2 note type: words sent before to the older DualSub Replay type can be sent again.
    private val sentIds = this.context.getSharedPreferences("ankidroid_sent_v2", 0)

    fun installed(): Boolean =
        runCatching { context.packageManager.resolveContentProvider(ANKIDROID_AUTHORITY, 0) != null }.getOrDefault(false)

    fun permitted(): Boolean = context.checkSelfPermission(ANKIDROID_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun wasSent(id: String): Boolean = sentIds.getBoolean(id, false)

    /**
     * Opens AnkiDroid. On many phones its provider answers only while AnkiDroid runs, so a card it
     * refused goes in once AnkiDroid was opened and left in the background.
     */
    fun open() {
        val launch = context.packageManager.getLaunchIntentForPackage(ANKIDROID_PACKAGE) ?: return
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    suspend fun send(words: List<SavedWord>): AnkiSendResult =
        withContext(Dispatchers.IO) {
            if (!installed()) return@withContext AnkiSendResult(problem = AnkiProblem.NOT_INSTALLED)
            if (!permitted()) return@withContext AnkiSendResult(problem = AnkiProblem.NO_PERMISSION)
            AnkiDroidSender(
                ContentProviderAnkiDroid(resolver),
                ::wasSent,
                { id -> sentIds.edit().putBoolean(id, true).apply() },
            ).send(words)
        }
}
