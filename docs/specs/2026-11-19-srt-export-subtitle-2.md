# Subtitle 1 and Subtitle 2 in the Whole transcript export

## Status

Implemented and audited in worktree `feat_srt_export_subtitle2` (branch `feat_srt_export_subtitle2`, from
`mydevelop`); not committed. Builds on the Whole transcript sheet ([spec](2026-11-17-tapped-word-first-and-custom-repeat.md)).
JVM tests, `formatCheck`, `complexityCheck`, `lintDebug`, `assembleDebug` and `assembleDebugAndroidTest` ran (see
Validation). The Compose tests were compiled, not run. Everything on a phone is MANUAL TEST REQUIRED.

## Problem

Player → subtitle gear → Whole transcript → Export (Text or SRT) offered one box, "Include the translation", and
that box was only ever Subtitle 1. Subtitle 2 (the third slot) had no way in. The reason: the sheet's
`WholeTranscript` was built with Subtitle 1's engine only (`translate` and `prefetch` from `runStoredTranslation`'s
`follow`), while Subtitle 2 lives in `ThirdLineTranslation`, which only fills `DualSubUiState.thirdLines`, a map of the
playback window. That map does not hold the whole video, so reading it for an export would have written a file with
holes.

## Behavior

Export dialog (scrolls on a small screen): format chips, then two independent boxes (tags
`whole_transcript_with_subtitle1`, `whole_transcript_with_subtitle2`), each labelled with its role and the language it
shows now ("Include Subtitle 1 (Vietnamese)"):

| Subtitle 1 | Subtitle 2 | File content (each cue / each Text entry) |
|---|---|---|
| off | off | Original only |
| on (default) | off (default) | Original + Subtitle 1, as before |
| off | on | Original + Subtitle 2 |
| on | on | Original + Subtitle 1 + Subtitle 2 |

- One combined file, same Android save screen (`CreateDocument`), UTF-8 without a byte order mark. SRT numbering and
  timestamps are unchanged. Blank lines inside a text are removed in SRT only, because a blank line ends a cue.
- Subtitle 2's box is disabled, unchecked and explained ("Subtitle 2 can't be included now: it is off, or it uses the
  same language as the Original or Subtitle 1") when the slot is off or `thirdLanguageToTranslate` returns null. Same
  language in Subtitle 1 and Subtitle 2 therefore means Subtitle 2 is unavailable, as it already is on screen.
- Text export applies the same two choices (indented lines under each sentence).
- AI note: when a chosen line's engine may ask the selected AI service (`TranscriptTrack.usesAi`, true when an AI model
  is the selected Translation AI Service in a Google or AI-only mode), a note says lines not translated yet are asked
  from that service, a long video can take a while and use a lot of its quota or credits. It is a note, not a
  confirmation: the AI authorization rules are unchanged.

## How the whole Subtitle 2 is produced

`ThirdLineTranslation.start` builds one engine per run: the AI window (the selected Translation AI Service, keyed or
ChatGPT Subscription), Google first or AI only, then on-device, always `Original → Subtitle 2 language`. It already
passed that engine to the playback loop. It now also hands it out as a `TranscriptTrack` (`onTrack`), and
`runStoredTranslation` gives it to `WholeTranscript`. So the export:

- translates Subtitle 2 from the Original sentence, never from Subtitle 1;
- uses the same service, model, effort, pacing (`AiWindowTranslation`, the translator's rests and limits), Google wait
  and fallbacks; it never switches provider or key, and ChatGPT Subscription stays on its own path;
- asks Google for a whole batch in one `translateAll` request (as Subtitle 1's export does), not a sentence at a time,
  unless AI only is on;
- asks in batches of `EXPORT_BATCH_SENTENCES` (40) as the rows stream from disk in `SCAN_ROWS` (500); each track has
  its own bounded cache (`WHOLE_TRANSCRIPT_TRANSLATIONS_KEPT`, 600, more than one 500-row chunk) and a sentence is
  asked once per track per export;
- never reads `thirdLines`; the exporter has no reference to playback UI state.

## Caches: what survives an export

- The tracks' own caches (600 sentences each) are dropped with the transcript and keep only the last chunk, so a
  second export does not rely on them.
- AI: `AiSubtitleTranslator`'s cache is keyed by model scope (provider, model, effort), prompt version, source and
  target, with 2,048 entries in memory and a 20,000-entry (16 MB) disk cache. A second export of the same video, setup
  and model finds most AI lines there; a different model, effort or language asks again.
- Google: the translator's cache holds 1,024 entries in memory only, so a long video's earlier lines are evicted and
  are asked again. On-device has no cache.
- Nothing is retained after the export: the engines live in the load, not in the exporter.

## Lifecycle, failures and incomplete files

| What happens | Result shown |
|---|---|
| All requested lines made | "Transcript saved." |
| A requested line could not be translated for some sentences (failed, blank or absent engine, AI limit) | saved, then "Transcript saved, but some lines have no translation in: <languages>" (`PARTIAL`; the languages are kept in the state, so the message stays right if the video changes) |
| Stop | "Export stopped." + "The file may be incomplete." |
| Video, a subtitle language or the translation setup changed (the load restarts, its store closes or its engine is cancelled) | "Export stopped: the video or its subtitle languages changed." + the same note (`CHANGED`) |
| The document could not be opened or written | "Couldn't save the transcript." + the note (`FAILED`) |

- The options and the transcript object are fixed when Save is tapped. The transcript belongs to one load, so a changed
  video or setup ends the export; nothing of two videos or two setups is ever mixed.
- The output stream is always closed (success, failure, Stop). Closing the sheet does not stop the export.
- The save screen creates the document before the app writes. The app is not told whether the file held something
  before, so a stopped or failed export never deletes it: a partial document can stay behind and the message says so.
- A new export replaces a running one; the older one never writes over the newer one's progress.

## Files

New: `ui/TranscriptTracks.kt` (fork-owned: `TranscriptTrack`, `TranscriptExportOptions`, `TranscriptExportOutcome`,
`writeTranscript`, `StaleTranscriptException`, `srtSafe`). Changed: `ui/WholeTranscript.kt`, `ui/TranscriptExporter.kt`,
`ui/WholeTranscriptSheet.kt`, `ui/ThirdLineTranslation.kt`, `ui/AppViewModel.kt`, `res/values*/strings_transcript.xml`
(9 locales: removed `whole_transcript_with_translation`; added `whole_transcript_include_subtitle`,
`whole_transcript_subtitle2_unavailable`, `whole_transcript_saved_missing`, `whole_transcript_ai_note`,
`whole_transcript_export_changed`, `whole_transcript_export_incomplete_note`). Tests: `WholeTranscriptSubtitle2Test`,
`WholeTranscriptTest`, `ThirdLineTranslationTest`, `WholeTranscriptUiTest`.

## Audit findings fixed (2026-11-19)

1. Subtitle 2's export asked Google one sentence at a time (Subtitle 1 asks batches), so a long video risked Google's
   refusals and a fall to on-device: Subtitle 2 now batches too.
2. A changed video or setup and a file that could not be written both ended as "Couldn't save": they are now `CHANGED`
   and `FAILED`, and a cancellation that came from the engine's own load ending is recognised as a change.
3. The "some lines have no translation" message took its language names from the sheet's current transcript, which
   can be a newer video's: the names are now kept in the export's state.
4. A provider that throws something other than `IOException` could crash the app: any such error is now a failed file.
5. A replaced export could overwrite the newer one's state when it finished cancelling.
6. No AI quota note; the dialog content could not scroll.

## Validation

Passed on the final tree in one run of `formatCheck complexityCheck :app:testDebugUnitTest :app:lintDebug
:app:assembleDebug :app:assembleDebugAndroidTest`: 1910 unit tests, 0 failures, 0 skipped (20 in
`WholeTranscriptSubtitle2Test`, 8 in `ThirdLineTranslationTest`, 7 in `WholeTranscriptTest`). Not run: device, emulator and Python suites. The Compose tests in
`WholeTranscriptUiTest` are compiled only (NOT RUN): no device is connected and no emulator was approved. Not covered
by any test: the Light/Dark look of the dialog, the progress messages' wording in each language, and a real export of
a long video.

## Manual acceptance tests on a phone (MANUAL TEST REQUIRED, not yet run)

Use a video with captions in a language other than Subtitle 1 and Subtitle 2, and install the debug APK yourself.

1. Settings → Languages: Triple subtitles on, Subtitle 1 and Subtitle 2 in two different languages. Player → gear →
   Whole transcript → Export: both boxes show their languages; Subtitle 1 is on, Subtitle 2 is off.
2. Export SRT four times (both off; 1 only; 2 only; both). Open each file: Original first, then only the chosen
   lines, Subtitle 1 before Subtitle 2, numbering and times right, Persian/Arabic/German text readable.
3. Repeat step 2 as a Text file.
4. Make Subtitle 2 the same language as Subtitle 1 (or turn Triple subtitles off): its box is disabled and explained.
5. Google only (no AI): export 1 + 2 of a long video; progress moves, Stop works, the "may be incomplete" note shows.
6. Only with a service and quota you accept to spend: pick an AI service, export a short video with both boxes. The
   AI note shows (with the sentence about mixed sources). In "AI only" the lines are the AI's; in "AI, with Google
   while waiting" some lines may be Google's.
7. Change the video, or a subtitle language, while an export runs: "Export stopped: the video or its subtitle
   languages changed." and the incomplete note; no file mixes videos or languages.
8. Make a translation fail (airplane mode in Google mode): "Transcript saved, but some lines have no translation in:
   <language>", naming each language that has gaps.
9. Close the sheet while exporting; reopen it: progress or the result is still shown.
10. Check the dialog on the Light and Dark themes and in landscape (it scrolls).

## Known limitations

- A sentence where only one of two requested tracks is missing is written without that line, so a cue can have two
  lines whose second is Subtitle 2 only; the "some lines have no translation in: …" message is the signal.
- OWNER DECISION (2026-11-19): mixed providers in one file are accepted. With Google first and an AI model chosen
  ("AI, with Google while waiting"), a line is the AI's only if the AI's answer has arrived when the line is written,
  else the existing Google or on-device fallback's (as on screen). The export never waits for every AI answer and
  never asks the AI more than the playing video's pipeline would, only to make the file uniform. AI only keeps its
  semantics (the AI's line; Google or the device only where it cannot answer). The AI note says in one sentence that a
  file can mix the sources. Each track keeps its own language and result and never takes the other's.
- The AI note is shown whenever the engine may ask the AI; it does not count the lines already cached.
- The sheet's rows still show only Subtitle 1 under each line; this change is for the export.
- The AI assistant cannot toggle the export boxes (there was no setting for the old box either).
- Live streams have no Whole transcript, so no export.
