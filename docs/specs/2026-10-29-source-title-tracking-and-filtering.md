# Source title tracking and filtering for Practice, due words, Anki and import/export

## Status

Implemented on `mydevelop` (owner request 2026-10-29); not merged, not released. Physical-device and live-YouTube checks are listed under Validation result. Implementation authorization does not authorize merge or release.

## Context / problem

A saved Practice word keeps only the technical `videoId`, so the user cannot see, filter or practice by the video it came from, and Anki cards do not carry the video's name.

## Goals

- Every saved word keeps the human-readable title of its video (`SavedWord.sourceTitle`), captured automatically when saved.
- Practice has a searchable Source / Video selector. It filters the list and the Practice Due Words queue.
- The title appears on each list row, in the detail view (editable) and in the review screen.
- Anki gets a `Source Title` field (new note type `TripleSub Replay v4`) and a stable `yt_<videoId>` tag.
- JSON backup and TSV export/import keep the title; older files still import.

## Non-goals

No new table, global store, permission, server, network request, second WebView or title scraping. `videoId`, the `Source` URL field and every id stay as they are. Old Anki notes are not rewritten or re-sent.

## User-visible behavior

- Save from subtitles: the word keeps the open video's title if the page reported it for that same video; otherwise the title stays unknown (never guessed) and the word still saves at once.
- Practice: "Source / Video" button above the search field opens a searchable list: All Sources, one entry per video (title and word count), and Unknown Source. Two videos with the same title are told apart by their video id. A video with no title shows as "Unknown Source · id".
- The word search and the source combine with AND. The word and due counters, the "next review" line and the Practice Due Words queue use the selected source. A source with no due words disables the button.
- A review session is a fixed list of ids chosen when it starts, so changing nothing mid-session can mix in another source; each card shows "Source: title". Ratings update the real record as before.
- Detail view: an editable "Source title" field; saving applies to every word of the same video (or only that word when it has no video id).

## Technical constraints / invariants

- `sourceTitle` is optional, stored in the existing JSON payload column (`encodeWord`/`decodeWord`), no schema change. It is not part of `savedWordId()` or `transferIdentity()`.
- `mergeResaved` and `keepSavedNotes` keep an existing title when the new record has none. Titles are whitespace-collapsed, trimmed and capped at 200 characters (`normalizeSourceTitle`).
- Capture reuses `onReceivedTitle` -> `onYouTubePageTitle`: the page title is stored with the video id parsed from the URL it was reported for (`VideoTitle`), and `DualSubUiState.titleOf(videoId)` returns it only for that same video, so a stale title of another video or tab is never used. The title is read again when the word is saved, because it can arrive after the tap.
- Source identity (`sourceKey`): `v:<videoId>`, else `t:<normalized title, NFC, lower case>`, else the Unknown group. Logic lives in `data/SourceFilter.kt` as small `internal` functions.
- Anki: v3 fields stay in order, `Source Title` is appended (v4). v3 notes and types are never changed.

## Proposed approach / plan

1. Model, merge/import preservation, JSON. 2. `SourceFilter.kt` logic. 3. Capture in `AppViewModel`. 4. `SavedWordsScreen` and `SourceFilterPicker.kt`, strings in 8 locales. 5. TSV column `sourceTitle`. 6. Anki v4, tags, fallbacks. 7. Tests.

## Acceptance criteria

- Save a word from a video, see its title in Practice, pick that video, see only its words, start Practice Due Words and review only its due words.
- Send to Anki puts the title in `Source Title` (v4) and tags the card `yt_<videoId>`.
- Export and import (JSON and TSV) keep titles; older backups and TSV files without the column import unchanged.
- Existing words, review schedules, ids and Anki cards are untouched.

## Validation plan

`formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`; `SavedWordsScreenTest` on the managed device; a phone check of the capture on a live video (not possible offline).

## Risks / edge cases

- YouTube's single-page navigation can report a title late; until then the title is unknown and the word saves without it (edit it later in the detail view).
- If the page reports a new video's title while its address still shows the previous video, the pair is the address's video, which is correct for the address.
- Same title, different videos: separate sources. Renamed video: still one source (by video id).
- Words imported without a video id are grouped by normalized title; words with neither are one Unknown group.
- Anki:
  - AnkiDroid creates v4 when missing; a same-name type with other fields still stops with the conflict message.
  - AnkiConnect desktop creates v4. AnkiConnect Android (cannot create types) uses v4 if present, else v3, else the six-field type, else Basic.
  - Types without a `Source Title` field get the title inside their Source field (v3, six-field) or on the back (Basic); the `yt_<id>` tag is added in every case.
  - Words already sent stay sent; nothing is re-sent or replaced, so old cards keep their old note type until the user sends new ones.

## Release intent

Reaches the APK (`app/src/main/**`): keep patch unless the owner says otherwise. No release label or version change was made.

## Implementation result

As described above. Files: `data/SavedWord.kt`, `data/SourceFilter.kt` (new), `data/VocabularyRepository.kt`, `data/PracticeTransfer.kt`, `data/AnkiTripleNote.kt`, `data/AnkiConnectNotes.kt`, `data/AnkiDroidProvisioning.kt`, `ui/AppViewModel.kt`, `ui/TripleSubtitles.kt` (`VideoTitle`, `titleOf`), `ui/SavedWordsScreen.kt`, `ui/SourceFilterPicker.kt` (new), `strings_practice.xml` in all 8 locales. Backup format stays version 1: `sourceTitle` is an optional key.

Deviations: the JSON backup and the TSV normalize whitespace inside a title (tabs and line breaks become one space), because titles are single-line.

## Follow-up: editable title in the subtitle header (owner request 2026-10-30)

Manual testing showed `sourceTitle` stayed empty, so automatic detection cannot be the only source.

- The subtitle panel header (normal and side panel) shows the video title where the source/target language line was, with a pencil that opens an edit dialog (Save / Cancel; Save is off for a blank title). Without a title it shows "Set video title". The old `Text(sourceDescription(state))` blocks are commented out in place and `sourceDescription()` is kept.
- Priority per `videoId`: the title the user typed, then the title the page reported for that same video, then unknown. Typed titles live in the `video_titles` preferences file (video id -> title), so they exist before any word is saved and survive restarts. `DualSubUiState.titleOf(videoId)` applies the priority for the header and for saving.
- Saving a title also gives it to every saved word with that exact `videoId` in one transaction (`VocabularyRepository.retitleVideo`); other fields and other videos are untouched. Practice, Anki, JSON and TSV read the same `sourceTitle`.

Remaining limits: automatic detection depends on WebView reporting the page title and was not re-verified on a device; a title edited only in Practice's detail view updates that video's words but not the header title; typed titles are not part of the JSON backup (the words' `sourceTitle` is).

## Validation result

Filled in at hand-off with the real command results.
