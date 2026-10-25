# Third line: export and word taps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (native, this session). Steps use checkbox syntax.

**Goal:** Saved third-line translations reach Anki and TSV export, and words on the third line can be tapped, saved and spoken, with Persian-safe tokenizing and identity.

**Architecture:** Anki note types stay unchanged (extra translations are folded into the existing `Translation` field); the TSV gets one trailing column read by header name; the tap pipeline gets an additive optional language; Persian support is rule-based normalization only.

**Tech Stack:** Kotlin, Compose, JUnit4 (no mocks), Gradle wrapper.

**Spec:** `docs/specs/2026-10-25-triple-subtitles.md` (Revision 4, written in Task 4).

## Global Constraints

- No new dependency, no Persian morphological analyzer, no new network call.
- Existing Anki note type name and `ANKI_FIELDS` unchanged (changing them duplicates the note type for AnkiDroid and degrades AnkiConnect to the front/back layout).
- `transferColumns` unchanged (it also drives the import mapping UI); old TSV and backup files still import.
- No UI string literal in Kotlin; unit tests plain JUnit4; fork logic in `TripleSubtitles.kt`, upstream files get small hooks.
- No push, PR, merge or release.

## Review Focus

- Persian text with ZWNJ ("می‌خواهم") stays one word; Persian punctuation ("،", "؟") is not part of a word (Task 3 test).
- Arabic yeh/kaf and diacritics must not create a duplicate saved word (Task 3 test).
- A tapped third-line word is saved as `fa`, never as the source or target language (Task 2 test).
- Persian sentence tapped on the third line is not lost on save (Task 2 test).
- Old TSV/backups without the new column import unchanged (Task 1 test).

---

### Task 1: Export

**Files:** Modify `data/AnkiDroidNotes.kt` (`ankiNoteFields`), `data/PracticeTransfer.kt` (`exportAnkiTsv`, `previewImport`); Test `AnkiDroidNotesTest.kt`, `PracticeTransferTest.kt`.

**Interfaces:**
- Produces: `internal fun ankiTranslationHtml(word: SavedWord): String`; `internal fun encodeSentenceTranslations(map: Map<String,String>): String` and `internal fun decodeSentenceTranslationsText(text: String): Map<String,String>` ("fa: text" per line); TSV header `transferColumns + "sentenceTranslations"`.

- [ ] Test: `ankiTranslationHtml` equals `ankiHtml(translatedSentence)` when no extras; with `{"fa":"خانه"}` it ends with `<div dir="auto">خانه</div>`; `ankiNoteFields(word)[4]` uses it; field count still 6.
- [ ] Test: TSV round trip keeps `sentenceTranslations`; header ends with `sentenceTranslations`; a TSV without that column imports with empty map; text with a newline becomes one line.
- [ ] Implement; run `*AnkiDroidNotesTest *AnkiConnectNotesTest *PracticeTransferTest`.
- [ ] Commit.

### Task 2: Taps on the third line

**Files:** Modify `data/SavedWord.kt` (`WordTap.language`, `learningSelection`), `ui/PhraseSelection.kt` (`PhraseActions`, `phraseTap`, `SelectableSubtitleText`, `BindPhraseActions`), `ui/AppViewModel.kt` (`translateSubtitleText` callers), `ui/TripleSubtitles.kt` (`ThirdLineText`, `thirdSentenceTranslations`), `ui/SubtitleCard.kt`, `ui/LearningPlayerRoot.kt`, `ui/DualSubApp.kt`; Test `SavedWordTest.kt`, `TripleSubtitlesTest.kt`, `PhraseSelectionTest.kt`.

**Interfaces:**
- `WordTap(..., val language: String? = null)`; `learningSelection`: `wordLanguage = tap.language ?: (translated ? target : source)`, `meaningLanguage = source` when `tap.language` is set and differs from source, else existing rule; `translated = true` for a third-line tap.
- `PhraseActions` gains `pronounceIn(text, language)`, `selectIn(text, language, singleWord)`, `translateFrom: (suspend (text, language) -> String)?` (defaults keep old callers).
- `SelectableSubtitleText(..., lineLanguage: String? = null)`; `phraseTap(..., language: String? = null)`.
- `thirdSentenceTranslations(third, activeVideoId, segment, source, target)`: skips `source` and `target` (not the word's languages), so a tapped Persian line is kept.
- `ThirdLineText(..., tap: ThirdTap? = null)` with `class ThirdTap(language, segment, highlightColor, onWordClick, onBlankTap)`; callers pass it only when word learning and tap-to-learn are on.

- [ ] Tests: `learningSelection` with `language="fa"` -> word `fa`, meaning = source; `phraseTap(language="fa")` carries it; `thirdSentenceTranslations` keeps `fa` when the word is `fa`.
- [ ] Implement in the order above; run `*SavedWordTest *TripleSubtitlesTest *PhraseSelectionTest`.
- [ ] Commit.

### Task 3: Persian rules

**Files:** Modify `data/SavedWord.kt` (`savedWordId`), add `normalizePersian` to `ui/TripleSubtitles.kt`? No: put it in `data/PersianText.kt` (new, fork-owned); Test `PersianTextTest.kt`.

**Interfaces:** `internal fun normalizePersian(text: String): String` (Arabic yeh/kaf to Persian, drop diacritics U+064B-U+0652 and tatweel, keep ZWNJ); `savedWordId` uses it when `wordLanguage == "fa"`.

- [ ] Test: tokens of "من می‌خواهم خانه را ببینم، درسته؟" with language `fa` are 6 words, ZWNJ word intact, no punctuation inside; `normalizePersian("كتاب")` equals "کتاب"; ids of the two spellings are equal for `fa` and unchanged for `en`.
- [ ] If the tokenizer test fails, fix in `LanguageAwareTokenizer`'s generic path only for the failing rule.
- [ ] Implement; run tests; commit.

### Task 4: Docs and gate

- [ ] Spec Revision 4, `myCHANGELOG.md`.
- [ ] `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` (python shim on PATH); commit locally.
