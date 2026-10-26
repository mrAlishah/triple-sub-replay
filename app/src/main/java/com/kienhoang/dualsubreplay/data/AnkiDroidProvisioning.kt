package com.kienhoang.dualsubreplay.data

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri

/** The deck new words go to in AnkiDroid; the older "DualSub Replay" deck and its notes are never touched. */
internal const val TRIPLE_DECK_NAME = "TripleSub Replay"

internal data class AnkiNoteType(
    val id: Long,
    val name: String,
    val fields: List<String>,
)

/**
 * What a send needs from AnkiDroid, one small step each. The real one talks to AnkiDroid's content
 * provider ([ContentProviderAnkiDroid]); tests use a fake. Every call may throw when AnkiDroid refuses.
 */
internal interface AnkiDroidProvider {
    fun decks(): List<Pair<Long, String>>

    fun createDeck(name: String): Long?

    fun noteTypes(): List<AnkiNoteType>

    fun createNoteType(
        name: String,
        fields: List<String>,
        deckId: Long,
    ): Long?

    /** Gives a note type made just now the TripleSub card layout. Refused layouts leave AnkiDroid's own. */
    fun styleNoteType(id: Long)

    /** The new note's id, or null when AnkiDroid did not make one. */
    fun insertNote(
        noteType: Long,
        fields: List<String>,
    ): Long?

    fun moveToDeck(
        noteId: Long,
        deck: Long,
    )
}

/**
 * Sends words to AnkiDroid in separately checked steps: deck, note type, then each note. The deck and the
 * note type are confirmed by asking AnkiDroid again; a note is confirmed by the id AnkiDroid gives back for
 * it, and only then is the word marked sent. A failure names its step. Existing decks and note types are
 * reused, never renamed or changed; a retry after a partly done send finds what exists and carries on
 * without making a second one. A card AnkiDroid will not move into the deck is still sent, and says so.
 */
internal class AnkiDroidSender(
    private val provider: AnkiDroidProvider,
    private val wasSent: (String) -> Boolean,
    private val markSent: (String) -> Unit,
    private val deckName: String = TRIPLE_DECK_NAME,
) {
    private class Stop(
        val problem: AnkiProblem,
        val detail: String? = null,
    ) : Exception()

    fun send(words: List<SavedWord>): AnkiSendResult {
        val unsent = unsentAnkiWords(words, wasSent)
        val skipped = words.distinctBy { it.id }.size - unsent.size
        if (unsent.isEmpty()) return AnkiSendResult(skipped = skipped)
        var sent = 0
        var misplaced = 0
        return try {
            val deck = resolveDeck()
            val noteType = resolveNoteType(deck)
            for (word in unsent) {
                if (!addNote(noteType, deck, word)) misplaced++
                markSent(word.id)
                sent++
            }
            AnkiSendResult(sent, skipped, AnkiProblem.WRONG_DECK.takeIf { misplaced > 0 })
        } catch (stop: Stop) {
            AnkiSendResult(sent, skipped, stop.problem, stop.detail)
        }
    }

    private fun <T> step(
        problem: AnkiProblem,
        block: () -> T?,
    ): T {
        val result =
            try {
                block()
            } catch (error: Exception) {
                // Only the kind of failure is kept: no message, so no word or note text can leak.
                throw Stop(problem, error.javaClass.simpleName)
            }
        return result ?: throw Stop(problem)
    }

    private fun resolveDeck(): Long =
        step(AnkiProblem.DECK_FAILED) {
            provider.decks().firstOrNull { it.second == deckName }?.first
                ?: provider.createDeck(deckName)?.takeIf { id -> provider.decks().any { it.first == id } }
        }

    private fun resolveNoteType(deck: Long): Long {
        val same = step(AnkiProblem.NOTE_TYPE_FAILED) { provider.noteTypes().filter { it.name == TRIPLE_NOTE_TYPE_NAME } }
        same.firstOrNull { it.fields == TRIPLE_FIELDS }?.let { return it.id }
        if (same.isNotEmpty()) throw Stop(AnkiProblem.NOTE_TYPE_CONFLICT)
        val id =
            step(AnkiProblem.NOTE_TYPE_FAILED) {
                provider
                    .createNoteType(TRIPLE_NOTE_TYPE_NAME, TRIPLE_FIELDS, deck)
                    ?.takeIf { made -> provider.noteTypes().any { it.id == made && it.fields == TRIPLE_FIELDS } }
            }
        runCatching { provider.styleNoteType(id) }
        return id
    }

    /** Adds the note; false when its card could not be moved into [deck] and stays in the note type's own deck. */
    private fun addNote(
        noteType: Long,
        deck: Long,
        word: SavedWord,
    ): Boolean {
        val noteId = step(AnkiProblem.INSERT_FAILED) { provider.insertNote(noteType, tripleNoteFields(word)) }
        return runCatching { provider.moveToDeck(noteId, deck) }.isSuccess
    }
}

/** [AnkiDroidProvider] over AnkiDroid's public content provider (no AnkiDroid library). */
internal class ContentProviderAnkiDroid(
    private val resolver: ContentResolver,
) : AnkiDroidProvider {
    private val base = Uri.parse("content://$ANKIDROID_AUTHORITY")
    private val decksUri = Uri.withAppendedPath(base, "decks")
    private val modelsUri = Uri.withAppendedPath(base, "models")
    private val notesUri = Uri.withAppendedPath(base, "notes")

    override fun decks(): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        resolver.query(decksUri, arrayOf("deck_id", "deck_name"), null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndex("deck_id")
            val name = cursor.getColumnIndex("deck_name")
            while (id >= 0 && name >= 0 && cursor.moveToNext()) result += cursor.getLong(id) to cursor.getString(name).orEmpty()
        } ?: error("AnkiDroid gave no deck list")
        return result
    }

    override fun createDeck(name: String): Long? =
        resolver.insert(decksUri, ContentValues().apply { put("deck_name", name) })?.lastPathSegment?.toLongOrNull()

    override fun noteTypes(): List<AnkiNoteType> {
        val result = mutableListOf<AnkiNoteType>()
        resolver.query(modelsUri, arrayOf("_id", "name", "field_names"), null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndex("_id")
            val name = cursor.getColumnIndex("name")
            val names = cursor.getColumnIndex("field_names")
            while (id >= 0 && name >= 0 && names >= 0 && cursor.moveToNext()) {
                result +=
                    AnkiNoteType(
                        cursor.getLong(id),
                        cursor.getString(name).orEmpty(),
                        cursor.getString(names).orEmpty().split(ANKI_FIELD_SEPARATOR),
                    )
            }
        } ?: error("AnkiDroid gave no note type list")
        return result
    }

    override fun createNoteType(
        name: String,
        fields: List<String>,
        deckId: Long,
    ): Long? {
        val values =
            ContentValues().apply {
                put("name", name)
                put("field_names", fields.joinToString(ANKI_FIELD_SEPARATOR))
                put("num_cards", 1)
                put("css", TRIPLE_CSS)
                put("deck_id", deckId)
                put("sort_field_index", 0)
            }
        return resolver.insert(modelsUri, values)?.lastPathSegment?.toLongOrNull()
    }

    override fun styleNoteType(id: Long) {
        val template =
            ContentValues().apply {
                put("question_format", TRIPLE_QUESTION_FORMAT)
                put("answer_format", TRIPLE_ANSWER_FORMAT)
            }
        resolver.update(Uri.withAppendedPath(modelsUri, "$id/templates/0"), template, null, null)
    }

    override fun insertNote(
        noteType: Long,
        fields: List<String>,
    ): Long? {
        val values =
            ContentValues().apply {
                put("mid", noteType)
                put("flds", fields.joinToString(ANKI_FIELD_SEPARATOR))
                put("tags", ANKI_TAG)
            }
        return resolver.insert(notesUri, values)?.lastPathSegment?.toLongOrNull()
    }

    override fun moveToDeck(
        noteId: Long,
        deck: Long,
    ) {
        val cards = Uri.withAppendedPath(notesUri, "$noteId/cards")
        val ords = mutableListOf<String>()
        resolver.query(cards, arrayOf("ord"), null, null, null)?.use { cursor ->
            val ord = cursor.getColumnIndex("ord")
            while (ord >= 0 && cursor.moveToNext()) ords += cursor.getString(ord)
        }
        for (ord in ords) resolver.update(Uri.withAppendedPath(cards, ord), ContentValues().apply { put("deck_id", deck) }, null, null)
    }
}
