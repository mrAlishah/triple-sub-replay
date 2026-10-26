# Anki meanings, translations, personal notes and online example

## Status

Implemented; waiting for the owner's phone test. The new note type is made in the owner's Anki only when
a word is sent from the phone (see "Approval needed"). Supersedes the first version of this spec (8 fields,
`TripleSub Replay`, `Meaning 3`); the Subtitle Context idea was dropped.

## Problem (owner's phone test, 2026-10-26)

Send to AnkiDroid made a note with only the second-language meaning. The third meaning, the third sentence
translation and the online example were missing, and the language was labeled with a code where it was kept.

## Root cause

`SendToButton` (`ui/StudyTools.kt`) rebuilt the note with `savedWordFrom(selection, meaning, online = false)`:
no further meanings, no third sentence translation, online forced off. Saving to vocabulary (`AppViewModel.saveWord`)
was correct; only Send used a thinner word. Send now builds the word from what the card shows (meanings of the
languages now selected, the online choice, the third line's translation from `StudyTools.uiState`) and waits
while a meaning is loading.

## Note type: `TripleSub Replay v2`

Fields in order: `Word`, `Meaning 1`, `Meaning 2`, `Reading`, `Sentence`, `Translation`, `Desc`, `Source`, `Online Example`.

- `Meaning 1`: the word's meaning in the second subtitle language only (`SavedWord.meaning`).
- `Meaning 2`: the meaning in the third subtitle language only (`SavedWord.meanings` values, plain text, no code or label).
- `Sentence`: the original line with the word in bold (unchanged).
- `Translation`: the second line's translation, then the third's, in slot order, each in `<span dir="auto">` and
  separated by `<br>`; no language names or codes; blank, missing and repeated lines are left out. Never a word meaning.
- `Desc`: the user's personal notes (below), escaped, line breaks as `<br>`; empty by default.
- `Source`: the video link with time (unchanged). `Online Example`: the saved clip link with start and end, only
  when the checkbox is on and the clip is valid, else empty. Nothing is fetched or generated.

The card back shows `Meaning 2`, `Desc` and `Online Example` only when filled and lays `Meaning 1`/`2` and `Desc`
out with `dir=auto`.

## Versioning decision

The first version of this change used `TripleSub Replay` with eight fields; it may already exist in the owner's
Anki. A type with that name and other fields must never be matched or duplicated, so this layout is a new name,
`TripleSub Replay v2`. AnkiDroid matches name and exact field list, else creates the type. The older
`DualSub Replay` and any `TripleSub Replay` are never read, changed or deleted. AnkiConnect desktop creates v2
with `createModel`; AnkiConnect Android cannot, so it uses an existing v2, else the six-field `DualSub Replay`
(unchanged mapping: extra meanings stay labeled inside `Meaning`, no `Desc`), else Basic (front: word and sentence;
back: every non-empty value in card order).

## Personal notes (`Desc`)

`SavedWord.personalNotes` (default empty; stored as an optional `personalNotes` key in the word's JSON payload, so
no schema change and older words decode with empty notes). The Practice dialog's word view (Vocabulary and, after
Reveal, the review card) has a multiline `Desc` field with Save notes, an "Unsaved changes" hint, and
auto-save on Back and on a review rating. Re-saving a word from the word card keeps old notes unless new ones are
given; editing meanings, reviewing and `update` keep them and the review schedule; an import that replaces a word
keeps its saved notes when the file has none. Backup JSON carries `personalNotes`; the Anki TSV export gets a
trailing `desc` column read by header name. Search covers notes. Export copies notes into Anki once; Anki and
Practice are not synchronized in either direction.

## Acceptance criteria

- The phone's note has separate `Meaning 1`, `Meaning 2`, a two-line `Translation` and `Desc`, no language prefixes.
- Unchecked online example, or no clip: `Online Example` is empty; export makes no request.
- Notes survive restart, editing, re-saving, backup/restore and TSV round trip; SRS data is unchanged.
- The legacy type and six-field mapping are untouched.

## Validation

Unit tests: `AnkiTripleNoteTest`, `PersonalNotesTest`, `AnkiConnectNotesTest`, `PracticeTransferTest`. Unverified:
phone, real AnkiDroid/AnkiConnect, the Compose notes field on a device, restart through the real database.

## Approval needed

The first Send creates `TripleSub Replay v2` in AnkiDroid. Rollback: Tools → Manage note types → delete it and its
notes; other note types are unaffected.

## AnkiDroid provisioning (send steps)

`AnkiDroidSender` (`data/AnkiDroidProvisioning.kt`) sends in steps: the `TripleSub Replay` deck (reused or made, then
listed again), the `TripleSub Replay v2` note type (reused only with the exact nine fields; the same name with other
fields stops the send; else made and listed again), then each note, confirmed by the note id AnkiDroid returns, after which
the word is marked sent. The provider ignores `deckId` on a single insert, so each new card is moved into the deck; a card
AnkiDroid will not move is still sent and the card says so (`WRONG_DECK`). Each failed step has its own message and shows
only the kind of error, never its text. Sent words are kept in `ankidroid_sent_v2`, so words sent before to the older
`DualSub Replay` note type can be sent to the new one. AnkiConnect keeps `ankiconnect_sent` and its `DualSub Replay` default deck.
