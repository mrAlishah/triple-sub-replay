package com.kienhoang.dualsubreplay.data

import java.util.Locale

/**
 * The "TripleSub Replay v4" note type: the v3 fields in the same order plus "Source Title". It is a new type
 * beside "TripleSub Replay v3" and "DualSub Replay", whose notes and cards are never touched; the name carries
 * the version so it can never clash with another type of the same name that has other fields.
 */
internal const val TRIPLE_NOTE_TYPE_NAME = "TripleSub Replay v4"

/** The earlier type, still used by AnkiConnect Android (which cannot make note types) when a phone already has it. */
internal const val TRIPLE_V3_NOTE_TYPE_NAME = "TripleSub Replay v3"
internal val TRIPLE_V3_FIELDS =
    listOf("Word", "Subtitle 1", "Subtitle 2", "Reading", "Sentence", "Translation", "Text", "Source", "Online Example")
internal val TRIPLE_FIELDS = TRIPLE_V3_FIELDS + "Source Title"

internal const val TRIPLE_QUESTION_FORMAT = ANKI_QUESTION_FORMAT
internal const val TRIPLE_ANSWER_FORMAT =
    "{{FrontSide}}<hr id=answer><div class=reading>{{Reading}}</div><div class=meaning dir=auto>{{Subtitle 1}}</div>" +
        "{{#Subtitle 2}}<div class=meaning2 dir=auto>{{Subtitle 2}}</div>{{/Subtitle 2}}" +
        "<div class=translation>{{Translation}}</div>" +
        "{{#Text}}<div class=desc dir=auto>{{Text}}</div>{{/Text}}" +
        "{{#Online Example}}<div class=online>{{Online Example}}</div>{{/Online Example}}" +
        "{{#Source Title}}<div class=sourcetitle dir=auto>{{Source Title}}</div>{{/Source Title}}" +
        "<div class=source>{{Source}}</div>"
internal const val TRIPLE_CSS =
    ANKI_CSS + ".meaning2{font-size:20px;margin-top:8px;}.desc{font-size:16px;margin-top:12px;text-align:start;}" +
        ".online{font-size:14px;margin-top:12px;}.sourcetitle{font-size:13px;margin-top:12px;opacity:.7;}"

private const val SECONDS_PER_MINUTE = 60
private const val MS_PER_SECOND = 1_000

/** The fields of [word]'s note, in [TRIPLE_FIELDS] order. No field carries a language name or code. */
internal fun tripleNoteFields(word: SavedWord): List<String> =
    listOf(
        ankiHtml(word.word),
        ankiHtml(word.meaning),
        ankiMeaning2Html(word),
        ankiHtml(word.reading.orEmpty()),
        ankiSentenceHtml(word.sentence, word.word),
        ankiLinesHtml(word),
        ankiHtml(word.personalNotes),
        ankiSourceLink(word.videoId, word.startMs),
        ankiOnlineExampleHtml(word),
        ankiHtml(word.sourceTitle.orEmpty()),
    )

/** Anki tags of [word]'s note: the app tag, and `yt_<video id>` so cards of one video can be grouped in any note type. */
internal fun ankiTags(word: SavedWord): List<String> =
    listOfNotNull(ANKI_TAG, word.videoId?.takeIf { validClipRange(it, 0, 1) }?.let { "yt_$it" })

/** The Source field of a note type with no field for the title: the title above the link, so it is never lost. */
internal fun ankiSourceWithTitle(
    word: SavedWord,
    link: String,
): String = listOf(ankiHtml(word.sourceTitle.orEmpty()), link).filter { it.isNotBlank() }.joinToString("<br>")

/** The word's meaning in the further subtitle language(s), as plain text; empty when there is none. */
internal fun ankiMeaning2Html(word: SavedWord): String =
    word.meanings.values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("<br>") { ankiHtml(it) }

/**
 * The sentence translations in slot order (the second line, then the third), one per line with a real break,
 * each isolated with `dir=auto` so Persian reads right-to-left beside English. Blank and repeated lines are left out.
 */
internal fun ankiLinesHtml(word: SavedWord): String =
    (listOfNotNull(word.translatedSentence) + word.sentenceTranslations.values)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("<br>") { "<span dir=\"auto\">${ankiHtml(it)}</span>" }

/** A link to the saved clip with its start and end, only when the online example is on and the clip is valid. */
internal fun ankiOnlineExampleHtml(word: SavedWord): String {
    val videoId = word.videoId
    if (!word.online || !validClipRange(videoId, word.startMs, word.endMs)) return ""
    val start = (word.startMs / MS_PER_SECOND).toInt()
    val end = (word.endMs / MS_PER_SECOND).toInt()
    return "<a href=\"https://www.youtube.com/watch?v=$videoId&amp;t=${start}s\">YouTube ${clock(start)}-${clock(end)}</a>"
}

private fun clock(seconds: Int): String = String.format(Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
