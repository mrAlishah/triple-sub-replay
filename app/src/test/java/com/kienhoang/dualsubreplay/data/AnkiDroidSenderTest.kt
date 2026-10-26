package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiDroidSenderTest {
    /** A pretend AnkiDroid: nothing here touches a real collection. */
    private class FakeAnki : AnkiDroidProvider {
        val deckList = mutableListOf<Pair<Long, String>>()
        val typeList = mutableListOf<AnkiNoteType>()
        val notes = mutableListOf<Pair<Long, List<String>>>()
        val moves = mutableListOf<Pair<Long, Long>>()
        var deckCreates = 0
        var typeCreates = 0
        var refuseDecks: Exception? = null
        var failDeckCreate = false
        var failTypeCreate = false
        var failInsert = false
        var failMove = false
        private var next = 100L

        override fun decks(): List<Pair<Long, String>> = refuseDecks?.let { throw it } ?: deckList.toList()

        override fun createDeck(name: String): Long? {
            deckCreates++
            if (failDeckCreate) return null
            return (next++).also { deckList += it to name }
        }

        override fun noteTypes(): List<AnkiNoteType> = typeList.toList()

        override fun createNoteType(
            name: String,
            fields: List<String>,
            deckId: Long,
        ): Long? {
            typeCreates++
            if (failTypeCreate) return null
            return (next++).also { typeList += AnkiNoteType(it, name, fields) }
        }

        override fun styleNoteType(id: Long) = Unit

        override fun insertNote(
            noteType: Long,
            fields: List<String>,
        ): Long? {
            if (failInsert) return null
            val id = next++
            notes += id to fields
            return id
        }

        override fun moveToDeck(
            noteId: Long,
            deck: Long,
        ) {
            if (failMove) throw IllegalStateException("move refused")
            moves += noteId to deck
        }
    }

    private val sentIds = mutableSetOf<String>()
    private var marks = 0

    private fun sender(
        anki: FakeAnki,
        deck: String = TRIPLE_DECK_NAME,
    ) = AnkiDroidSender(anki, { it in sentIds }, {
        marks++
        sentIds += it
    }, deck)

    private fun word(
        id: String = "1",
        meaning: String = "injured",
    ) = SavedWord(
        id = id,
        word = "Verletzten",
        reading = null,
        wordLanguage = "de",
        meaningLanguage = "en",
        meaning = meaning,
        sentence = "Er kümmert sich um die Verletzten.",
        translatedSentence = "He takes care of the injured.",
        videoId = "abcdefghijk",
        startMs = 971_000,
        endMs = 974_000,
        translated = false,
        sentenceTranslations = mapOf("fa" to "او از مجروحان مراقبت می‌کند."),
        meanings = mapOf("fa" to "مجروح شد"),
        personalNotes = "a <b> & c\nline 2",
    )

    private fun ready(anki: FakeAnki) {
        anki.deckList += 5L to TRIPLE_DECK_NAME
        anki.typeList += AnkiNoteType(7, TRIPLE_NOTE_TYPE_NAME, TRIPLE_FIELDS)
    }

    @Test
    fun aMissingDeckAndNoteTypeAreCreatedThenTheNoteIsAddedToThatDeck() {
        val anki = FakeAnki()
        val result = sender(anki).send(listOf(word()))
        assertEquals(AnkiSendResult(sent = 1), result)
        assertEquals(listOf(TRIPLE_DECK_NAME), anki.deckList.map { it.second })
        assertEquals(listOf(TRIPLE_NOTE_TYPE_NAME to TRIPLE_FIELDS), anki.typeList.map { it.name to it.fields })
        assertEquals(anki.deckList.single().first, anki.moves.single().second)
        assertEquals(1, marks)
    }

    @Test
    fun anExistingDeckAndNoteTypeAreReusedNotDuplicated() {
        val anki = FakeAnki().also(::ready)
        assertEquals(AnkiSendResult(sent = 1), sender(anki).send(listOf(word())))
        assertEquals(0, anki.deckCreates)
        assertEquals(0, anki.typeCreates)
        assertEquals(5L, anki.moves.single().second)
        assertEquals(1, anki.notes.size)
    }

    @Test
    fun aSameNameNoteTypeWithOtherFieldsStopsSafelyWithoutCreatingAnotherOrSending() {
        val anki = FakeAnki()
        anki.deckList += 5L to TRIPLE_DECK_NAME
        anki.typeList += AnkiNoteType(7, TRIPLE_NOTE_TYPE_NAME, listOf("Front", "Back"))
        val result = sender(anki).send(listOf(word()))
        assertEquals(AnkiProblem.NOTE_TYPE_CONFLICT, result.problem)
        assertEquals(0, result.sent)
        assertEquals(0, anki.typeCreates)
        assertTrue(anki.notes.isEmpty())
        assertEquals(0, marks)
        assertEquals(listOf("Front", "Back"), anki.typeList.single().fields)
    }

    @Test
    fun aCompatibleTypeIsUsedEvenWhenAnotherSameNameTypeExists() {
        val anki = FakeAnki().also(::ready)
        anki.typeList += AnkiNoteType(8, TRIPLE_NOTE_TYPE_NAME, listOf("Front", "Back"))
        assertEquals(AnkiSendResult(sent = 1), sender(anki).send(listOf(word())))
        assertEquals(0, anki.typeCreates)
    }

    @Test
    fun aRefusedProviderNamesTheDeckStepAndSendsNothing() {
        val anki = FakeAnki().apply { refuseDecks = SecurityException("secret: Verletzten") }
        val result = sender(anki).send(listOf(word()))
        assertEquals(AnkiProblem.DECK_FAILED, result.problem)
        assertEquals("SecurityException", result.detail)
        assertTrue(anki.notes.isEmpty())
        assertEquals(0, marks)
    }

    @Test
    fun aFailedDeckCreationInsertsNothing() {
        val anki = FakeAnki().apply { failDeckCreate = true }
        val result = sender(anki).send(listOf(word()))
        assertEquals(AnkiProblem.DECK_FAILED, result.problem)
        assertEquals(0, anki.typeCreates)
        assertTrue(anki.notes.isEmpty())
        assertEquals(0, marks)
    }

    @Test
    fun aFailedNoteTypeCreationInsertsNothing() {
        val anki = FakeAnki().apply { failTypeCreate = true }
        val result = sender(anki).send(listOf(word()))
        assertEquals(AnkiProblem.NOTE_TYPE_FAILED, result.problem)
        assertTrue(anki.notes.isEmpty())
        assertEquals(0, marks)
    }

    @Test
    fun aFailedInsertionDoesNotMarkTheWordSent() {
        val anki = FakeAnki().also(::ready).apply { failInsert = true }
        val result = sender(anki).send(listOf(word()))
        assertEquals(AnkiProblem.INSERT_FAILED, result.problem)
        assertEquals(0, marks)
        assertTrue(sentIds.isEmpty())
    }

    @Test
    fun aCardAnkiDroidWillNotMoveIsStillSentOnceAndTheDeckProblemIsReported() {
        val anki = FakeAnki().also(::ready).apply { failMove = true }
        val result = sender(anki).send(listOf(word("1"), word("2")))
        assertEquals(AnkiSendResult(sent = 2, problem = AnkiProblem.WRONG_DECK), result)
        assertEquals(2, marks)
        assertEquals(2, anki.notes.size)
        val again = sender(anki).send(listOf(word("1"), word("2")))
        assertEquals(AnkiSendResult(skipped = 2), again)
        assertEquals(2, anki.notes.size)
    }

    @Test
    fun theNoteIdAnkiDroidGivesBackIsTheConfirmationSoNoSecondLookIsNeeded() {
        val anki = FakeAnki().also(::ready)
        assertEquals(AnkiSendResult(sent = 1), sender(anki).send(listOf(word())))
        assertEquals(1, marks)
    }

    @Test
    fun aSuccessfulInsertionMarksEachWordSentExactlyOnce() {
        val anki = FakeAnki().also(::ready)
        val result = sender(anki).send(listOf(word("1"), word("2"), word("1")))
        assertEquals(AnkiSendResult(sent = 2), result)
        assertEquals(2, marks)
        assertEquals(setOf("1", "2"), sentIds)
    }

    @Test
    fun sendingAgainAddsNoSecondNote() {
        val anki = FakeAnki()
        sender(anki).send(listOf(word()))
        val again = sender(anki).send(listOf(word()))
        assertEquals(AnkiSendResult(skipped = 1), again)
        assertEquals(1, anki.notes.size)
        assertEquals(1, anki.deckList.size)
        assertEquals(1, anki.typeList.size)
    }

    @Test
    fun aDeckTheUserChoseIsUsedAndTheDefaultDeckIsNotMade() {
        val anki = FakeAnki().apply { deckList += 9L to "My deck" }
        assertEquals(AnkiSendResult(sent = 1), sender(anki, "My deck").send(listOf(word())))
        assertEquals(9L, anki.moves.single().second)
        assertEquals(0, anki.deckCreates)
        assertFalse(anki.deckList.any { it.second == TRIPLE_DECK_NAME })
    }

    @Test
    fun theNoteHasTheNineFieldsInOrderWithPersianAndEscapedText() {
        val anki = FakeAnki().also(::ready)
        sender(anki).send(listOf(word()))
        val fields = anki.notes.single().second
        assertEquals(TRIPLE_FIELDS.size, fields.size)
        assertEquals(tripleNoteFields(word()), fields)
        val byName = TRIPLE_FIELDS.zip(fields).toMap()
        assertEquals("injured", byName["Meaning 1"])
        assertEquals("مجروح شد", byName["Meaning 2"])
        assertEquals("a &lt;b&gt; &amp; c<br>line 2", byName["Desc"])
        assertTrue(byName.getValue("Translation").contains("او از مجروحان مراقبت می‌کند."))
        assertTrue(byName.getValue("Sentence").contains("<b>Verletzten</b>"))
    }

    @Test
    fun theOlderDualSubDeckAndNoteTypeAreLeftAsTheyWere() {
        val legacyType = AnkiNoteType(3, ANKI_NOTE_TYPE_NAME, ANKI_FIELDS)
        val anki =
            FakeAnki().apply {
                deckList += 2L to ANKI_DECK_NAME
                typeList += legacyType
            }
        sender(anki).send(listOf(word()))
        assertTrue(anki.deckList.contains(2L to ANKI_DECK_NAME))
        assertEquals(legacyType, anki.typeList.first())
        assertTrue(anki.notes.none { it.first == 3L })
        assertTrue(anki.moves.none { it.second == 2L })
    }

    @Test
    fun aRetryAfterAPartialFailureReusesTheDeckAndCreatesTheRestOnce() {
        val anki = FakeAnki().apply { failTypeCreate = true }
        assertEquals(AnkiProblem.NOTE_TYPE_FAILED, sender(anki).send(listOf(word())).problem)
        assertEquals(1, anki.deckList.size)
        anki.failTypeCreate = false
        assertEquals(AnkiSendResult(sent = 1), sender(anki).send(listOf(word())))
        assertEquals(1, anki.deckList.count { it.second == TRIPLE_DECK_NAME })
        assertEquals(1, anki.typeList.size)
        assertEquals(1, anki.notes.size)
        assertEquals(1, marks)
    }

    @Test
    fun aFailureMidwayKeepsWhatWasSentAndReportsTheStep() {
        val anki = FakeAnki().also(::ready)
        val first = sender(anki).send(listOf(word("1")))
        anki.failInsert = true
        val second = sender(anki).send(listOf(word("1"), word("2")))
        assertEquals(1, first.sent)
        assertEquals(AnkiSendResult(sent = 0, skipped = 1, problem = AnkiProblem.INSERT_FAILED), second)
        assertNull(second.detail)
    }
}
