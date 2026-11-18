package com.kienhoang.dualsubreplay.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.kienhoang.dualsubreplay.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** AnkiDroid's public content provider and the permission it asks for (no AnkiDroid library). */
internal const val ANKIDROID_AUTHORITY = "com.ichi2.anki.flashcards"
internal const val ANKIDROID_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
internal const val ANKIDROID_PACKAGE = "com.ichi2.anki"

internal const val ANKI_DECK_NAME = "DualSub Replay"

/**
 * The note type with the two sound fields (owner pick 2026-11-09). AnkiDroid's API can't add fields to the
 * older "DualSub Replay" type people already have, so the new fields come with a new name; old cards keep theirs.
 */
internal const val ANKI_NOTE_TYPE_NAME = "DualSub Replay audio"
internal const val ANKI_WORD_AUDIO_FIELD = "Word audio"
internal const val ANKI_SENTENCE_AUDIO_FIELD = "Sentence audio"
internal val ANKI_FIELDS =
    listOf("Word", "Meaning", "Reading", "Sentence", "Translation", "Source", ANKI_WORD_AUDIO_FIELD, ANKI_SENTENCE_AUDIO_FIELD)

/** Anki keeps a note's fields in one text, separated by this character. */
internal const val ANKI_FIELD_SEPARATOR = "\u001f"

internal const val ANKI_TAG = "DualSubReplay"

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

/**
 * Links to the line's moment in the video, or empty text when the word has no video. The first link
 * opens this app (an intent link naming [appPackage], because Android gives youtube.com links to the
 * YouTube app); the plain YouTube link next to it keeps working in desktop Anki. No text sits between
 * the links, because a picture-front template hides only the links and line breaks of this field.
 */
internal fun ankiSourceLink(
    videoId: String?,
    startMs: Long,
    appPackage: String = BuildConfig.APPLICATION_ID,
): String {
    if (videoId == null || !videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return ""
    val seconds = (startMs.coerceAtLeast(0) / MS_PER_SECOND).toInt()
    val label = String.format(Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
    val watch = "www.youtube.com/watch?v=$videoId&amp;t=${seconds}s"
    val app = "<a href=\"intent://$watch#Intent;scheme=https;package=$appPackage;end\">DualSub Replay $label</a>"
    return "$app<a style=\"margin-left:12px\" href=\"https://$watch\">YouTube</a>"
}

/**
 * A card's media as Anki field text: the sounds (`[sound:name]`) and the picture (`<img>`), each
 * empty when missing.
 */
internal data class CardMedia(
    val word: String = "",
    val sentence: String = "",
    val picture: String = "",
    /** Read aloud on the back: the meanings' sounds, after the Meaning field's text. */
    val meaningSounds: String = "",
    /** Read aloud on the back: the sentence translation's sound, after the Translation field's text. */
    val translationSound: String = "",
)

/** The field text that plays the media file [name] in Anki; empty for a name Anki couldn't play. */
internal fun ankiSound(name: String?): String {
    val clean = name?.trim()?.removePrefix("/").orEmpty()
    if (clean.isEmpty() || clean.any { it == '[' || it == ']' || it == '/' || it == '\\' || it.isISOControl() }) return ""
    return "[sound:$clean]"
}

/**
 * The fields of [word]'s note, in [ANKI_FIELDS] order. The picture goes in Source, above the video
 * link: AnkiDroid can't add a field to the note type people already have.
 */
internal fun ankiNoteFields(
    word: SavedWord,
    media: CardMedia = CardMedia(),
): List<String> =
    listOf(
        ankiHtml(word.word),
        ankiMeaningHtml(word) + media.meaningSounds,
        ankiHtml(word.reading.orEmpty()),
        ankiSentenceHtml(word.sentence, word.word),
        ankiTranslationHtml(word) + media.translationSound,
        listOf(media.picture, ankiSourceLink(word.videoId, word.startMs)).filter { it.isNotEmpty() }.joinToString("<br>"),
        media.word,
        media.sentence,
    )

/** The Meaning field: the primary meaning, then each further language as its own labeled right-to-left-safe block. */
internal fun ankiMeaningHtml(word: SavedWord): String =
    ankiHtml(word.meaning) +
        word.meanings.entries.joinToString("") { (language, text) -> "<div dir=\"auto\">${ankiHtml(language)}: ${ankiHtml(text)}</div>" }

/** The Translation field: the second line, then each further sentence translation as its own right-to-left-safe block. */
internal fun ankiTranslationHtml(word: SavedWord): String =
    ankiHtml(word.translatedSentence.orEmpty()) +
        word.sentenceTranslations.values.joinToString("") { "<div dir=\"auto\">${ankiHtml(it)}</div>" }

/** [fields] (a note's fields in [TRIPLE_FIELDS] or [ANKI_FIELDS] order) with [field] set to [value]; null when the note has other fields. */
internal fun ankiFieldsWith(
    fields: List<String>,
    field: String,
    value: String,
): List<String>? {
    val names = listOf(TRIPLE_FIELDS, ANKI_FIELDS).firstOrNull { it.size == fields.size } ?: return null
    val index = names.indexOf(field)
    if (index < 0) return null
    return fields.toMutableList().also { it[index] = value }
}

/** The media files of one card: the word's voice, the line's clip and its picture, each null when there is none. */
internal data class AnkiCardFiles(
    val word: File? = null,
    val sentence: File? = null,
    val picture: File? = null,
    /** Read aloud on the back: the meaning in this sentence and the dictionary meaning, in the Meaning field's order. */
    val meanings: List<File> = emptyList(),
    val translation: File? = null,
)

/**
 * Where cards get their media (owner picks 2026-11-09 and 2026-11-10): [files] makes or finds the
 * ones [options] asks for, and [awaitSentence] remembers a card sent before its line's clip was
 * ready, so the clip is added later.
 */
internal interface AnkiCardMedia {
    suspend fun files(
        word: SavedWord,
        options: CardOptions,
    ): AnkiCardFiles

    fun awaitSentence(item: PendingCardAudio)

    /** The small label before the line's sound on the card, in the app's language; empty for none. */
    val sentenceLabel: String get() = ""
}

/**
 * The text before the line's play button in the Sentence audio field, so learners know that button
 * plays the line from the video (owner request 2026-11-16); empty for an empty [label].
 */
internal fun ankiSentenceSoundLabel(label: String?): String =
    label?.takeIf { it.isNotBlank() }?.let { "<span style=\"font-size:14px;opacity:.7\">${ankiHtml(it)}</span> " }.orEmpty()

/** [sound] with [label] before it; empty without a sound, so a card that waits for its clip shows no label yet. */
internal fun labeledSentenceSound(
    label: String?,
    sound: String,
): String = if (sound.isEmpty()) "" else ankiSentenceSoundLabel(label) + sound

/** The authority of the provider that lends AnkiDroid the media files (read-only, for one copy). */
internal fun cardAudioAuthority(context: Context): String = "${context.packageName}.cardaudio"

/**
 * The AnkiDroid deck for [word], split like Practice (owner pick 2026-11-16): a subdeck of
 * DualSub Replay named after the word's Practice deck, else after its language in English (so the
 * name doesn't change with the app's language). Anki reviews a parent deck with all its subdecks.
 * Not used by any send path: the fork keeps its one `TripleSub Replay` deck (owner decision 2026-11-18); a later
 * subdeck feature passes `TRIPLE_DECK_NAME` as [parent].
 */
internal fun ankiDeckName(
    word: SavedWord,
    parent: String = ANKI_DECK_NAME,
): String {
    val sub = word.deck?.let(::ankiDeckPart) ?: ankiDeckPart(ankiLanguageName(word.wordLanguage))
    return sub?.let { "$parent::$it" } ?: parent
}

/** [code]'s language name in English ("English", "Japanese"), or the code itself when unknown. */
internal fun ankiLanguageName(code: String): String {
    val name = Locale.forLanguageTag(code.trim()).getDisplayLanguage(Locale.ENGLISH)
    return name.ifBlank { code }
}

/** [name] as one level of a deck name: Anki's "::" separator and quotes taken out; null when nothing is left. */
private fun ankiDeckPart(name: String): String? =
    name
        .replace("::", " ")
        .replace("\"", "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotEmpty() }

/** The words of [words] that were not sent before, by id. */
internal fun unsentAnkiWords(
    words: List<SavedWord>,
    again: Boolean = false,
    sent: (String) -> Boolean,
): List<SavedWord> = words.distinctBy { it.id }.filterNot { !again && sent(it.id) }

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
 * with AnkiConnect's own error text when it gave one. [waiting] cards went without their
 * sentence's sound, which is added once the line has played.
 */
internal data class AnkiSendResult(
    val sent: Int = 0,
    val skipped: Int = 0,
    val problem: AnkiProblem? = null,
    val detail: String? = null,
    val waiting: Int = 0,
)

/**
 * Adds notes to AnkiDroid through its content provider ([AnkiDroidSender]): a "TripleSub Replay" deck and a
 * "TripleSub Replay v5" note type are made the first time (the older "DualSub Replay" deck and note type are left
 * alone). Which words were sent is kept on the phone, so a word is not sent twice.
 */
internal class AnkiDroidNotes(
    context: Context,
) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    // Its own list for the TripleSub Replay v2 note type: words sent before to the older DualSub Replay type can be sent again.
    private val sentIds = this.context.getSharedPreferences("ankidroid_sent_v2", 0)

    /** The card front last set on each note type (by id), see [cardFrontNeedsUpdate]. */
    private val cardFronts = PreferenceCardFronts(this.context, "ankidroid_card_front")
    private val base = Uri.parse("content://$ANKIDROID_AUTHORITY")
    private val provider = ContentProviderAnkiDroid(this.context)

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

    suspend fun send(
        words: List<SavedWord>,
        media: AnkiCardMedia? = null,
        options: CardOptions = CardOptions(),
        again: Boolean = false,
    ): AnkiSendResult =
        withContext(Dispatchers.IO) {
            if (!installed()) return@withContext AnkiSendResult(problem = AnkiProblem.NOT_INSTALLED)
            if (!permitted()) return@withContext AnkiSendResult(problem = AnkiProblem.NO_PERMISSION)
            AnkiDroidSender(
                provider,
                ::wasSent,
                { id -> sentIds.edit().putBoolean(id, true).apply() },
                cardFronts = cardFronts,
            ).send(words, media, options, again)
        }

    /**
     * Adds a waiting card's sentence clip: the clip goes into AnkiDroid's media and [item]'s field
     * plays it. False when AnkiDroid didn't take it (try again later); true also when the note is
     * gone or has other fields, so it stops waiting.
     */
    suspend fun addSentenceAudio(
        item: PendingCardAudio,
        clip: File,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!installed() || !permitted()) return@withContext false
            try {
                val note = Uri.withAppendedPath(base, "notes/${item.noteId}")
                val fields =
                    resolver.query(note, arrayOf("flds"), null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex("flds")
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    } ?: return@withContext true
                val sound = ankiSound(provider.addMedia(clip)).takeIf { it.isNotEmpty() } ?: return@withContext false
                val updated =
                    ankiFieldsWith(fields.split(ANKI_FIELD_SEPARATOR), item.field, item.base + sound)
                        ?: return@withContext true
                val values = ContentValues().apply { put("flds", updated.joinToString(ANKI_FIELD_SEPARATOR)) }
                resolver.update(note, values, null, null) > 0
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                false
            }
        }
}
