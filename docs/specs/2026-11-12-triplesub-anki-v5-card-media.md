# TripleSub Anki cards with sound, picture and card front (note type v5)

Owner pick 2026-11-12 during the upstream sync of `main` (ed6e09c) into `mydevelop`. Upstream added word and sentence sound, a card picture and a chosen card front ([sound](2026-11-09-anki-word-and-sentence-audio.md), [picture and front](2026-11-10-anki-card-picture-and-front.md)) to its own "DualSub Replay audio" note type. The fork sends its own note type, so the features had to reach it.

## Decision

New note type `TripleSub Replay v5` = v4 fields (`Word`, `Subtitle 1`, `Subtitle 2`, `Reading`, `Sentence`, `Translation`, `Text`, `Source`, `Online Example`, `Source Title`) + `Word audio` + `Sentence audio`. AnkiDroid cannot add fields to a type that exists, so the name changes (`AGENTS.md`: a field change needs a new note type name). v4 and v3 notes and cards are never touched; they stay AnkiConnect Android fallbacks, where note types cannot be made.

## Acceptance criteria

- AnkiDroid: a first send creates v5 in the `TripleSub Replay` deck, with the card layout of the picked card front; an existing v5 gets a new layout only when the front (or Sentence sound on the front) changes.
- With Add audio to cards, the word voice and the line clip land in `Word audio` / `Sentence audio`; a card sent before its clip is ready is finished later in `Sentence audio` (any 12-field TripleSub note or the 8-field upstream type).
- With Add a picture to cards, the picture sits above the video link in `Source`.
- AnkiConnect: v5 is made where it can be (desktop), else v4, v3, upstream's audio type, then Basic; on Basic cards the card front picks the sides, with both subtitle meanings together and the extras before Source.
- No field carries a language name or code; tags stay `DualSubReplay` and `yt_<video id>`.

## Plan and implementation record

- `data/AnkiTripleNote.kt`: v5 constants, `tripleNoteFields(word, media)`, `tripleCardTemplate(front, frontSound)` (upstream's front layouts with the Meaning and Source blocks replaced by the fork's).
- `data/AnkiDroidProvisioning.kt`: `AnkiDroidSender.send` is now suspend and takes media, options and `again`; `AnkiDroidProvider.styleNoteType(id, template)` and `addMedia(file)`; `ContentProviderAnkiDroid(context)`.
- `data/AnkiDroidNotes.kt` delegates to the sender; `data/AnkiConnectNotes.kt` uses v5, `tripleCardTemplate` and `allowDuplicate = again`.
- `data/AnkiCardLayout.kt` (upstream-owned): `MEANING_HTML` and `SOURCE_HTML` made `internal`.

Checks: `AnkiTripleNoteTest`, `AnkiDroidSenderTest`, `AnkiConnectNotesTest`, `AnkiDroidNotesTest` and `AnkiCardLayoutTest` pass on the JVM. Not verified: a real AnkiDroid or AnkiConnect (v5 creation, the layouts, media copy, a late sentence clip) and card rendering on a phone.
