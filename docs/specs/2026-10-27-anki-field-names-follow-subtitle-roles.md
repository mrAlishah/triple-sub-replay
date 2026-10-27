# Anki fields and TSV columns follow the subtitle roles (Triple Sub Replay fork)

## Status

Approved by the fork owner (2026-10-27): "New note type v3". Local commits only.

## Goal

The Anki note and the TSV export use the same names as the app: `Subtitle 1` (the first translation, `SavedWord.meaning`), `Subtitle 2` (the second translation, `SavedWord.meanings`) and `Text` (the notes, `personalNotes`).

## Design

- AnkiDroid reuses a note type only when its name and field list both match, and refuses to send on a clash. So new fields need a new name: `TripleSub Replay v3` with `Word, Subtitle 1, Subtitle 2, Reading, Sentence, Translation, Text, Source, Online Example`. The card templates use the new names. Existing `TripleSub Replay v2` notes and cards are never touched or converted.
- TSV export columns: `word, subtitle1, reading, wordLanguage, subtitle1Language, sentence, translatedSentence, source, startMs, endMs, sentenceTranslations, subtitle2, text`. Import maps by header and still accepts the old `meaning`, `meaningLanguage`, `meanings`, `desc`, so older exports import unchanged.
- JSON backup keys are not renamed: `encodeWord`/`decodeWord` also write the app's own storage, so a rename would risk losing saved words. Backup format version stays 1.
- AnkiConnect (desktop Anki or AnkiConnect Android) creates/uses v3 when it can, otherwise the older six-field type or the front/back fallback, as before.
- Storage, language codes and Kotlin property names are unchanged.

## Validation

Unit tests: field list and values, card templates, sender field maps, TSV header and legacy-header import, Practice send. Full gate last.

## Verification

See the final report; nothing was tried against a real AnkiDroid or Anki.
