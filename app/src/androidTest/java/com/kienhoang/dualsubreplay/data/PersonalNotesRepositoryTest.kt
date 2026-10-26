package com.kienhoang.dualsubreplay.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class PersonalNotesRepositoryTest {
    private val notes = "Grammar note\n\nنکته فارسی"
    private val word =
        SavedWord(
            id = "word-1",
            word = "word",
            reading = null,
            wordLanguage = "en",
            meaningLanguage = "vi",
            meaning = "từ",
            sentence = "sentence with word",
            translatedSentence = "câu với từ",
            videoId = "dQw4w9WgXcQ",
            startMs = 1000,
            endMs = 3000,
            translated = false,
        )

    @Test
    fun personalNotesSurviveReopenResaveEditsAndAnImportWithoutNotes() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "vocabulary-notes-${UUID.randomUUID()}.db"
            var repository = VocabularyRepository(context, name)
            try {
                repository.save(word.copy(meanings = mapOf("fa" to "واژه")))
                repository.update(word.id) { it.copy(personalNotes = notes) }
                repository.save(word.copy(meaning = "edited", meanings = mapOf("fa" to "واژه")))
                repository.update(word.id) { it.copy(meaning = "edited again", meanings = emptyMap()) }
                repository.close()

                repository = VocabularyRepository(context, name)
                repository.refresh()
                assertEquals(
                    notes,
                    repository.words.value
                        .single()
                        .personalNotes,
                )

                repository.importWords(listOf(word.copy(meaning = "from file")), replace = true)
                assertEquals(
                    "from file",
                    repository.words.value
                        .single()
                        .meaning,
                )
                assertEquals(
                    notes,
                    repository.words.value
                        .single()
                        .personalNotes,
                )

                repository.importWords(listOf(word.copy(personalNotes = "new notes")), replace = true)
                assertEquals(
                    "new notes",
                    repository.words.value
                        .single()
                        .personalNotes,
                )
            } finally {
                repository.close()
                context.deleteDatabase(name)
            }
        }
}
