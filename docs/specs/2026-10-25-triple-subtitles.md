# Triple subtitles (Triple Sub Replay fork)

## Status

Approved for implementation. The fork owner asked for this change and authorized local implementation
and local commits on `feat_triple_subtitles`. This does not authorize push, PR, merge, tag or release.

> **Revision 1 (owner request, same day) replaces the interaction model described below.** The separate "Triple subtitles" switch and "Third language" list in Settings → Translation are gone; see [Revision 1: subtitle slots](#revision-1-subtitle-slots). Everything about the third line's data flow (side map, translation from the source text, failure isolation) is unchanged.

## Context / problem

Upstream DualSub Replay shows two lines per caption: the original and one translation
(`SubtitleSegment.translatedText`). The fork owner studies German with English and Persian side by side
and wants a third line (default: German, English, Persian), while dual mode keeps working unchanged
and upstream merges stay cheap.

## Goals

- Optional **Triple subtitles** mode: original, translation (existing target language), and a third
  translation in a user-chosen language. Default third language is Persian (`fa`).
- All three lines belong to the same row and time; seeking, replay, word taps and row selection are unchanged.
- The third line is translated from the **source caption** (not from the second language) with the existing
  translators (Google web, on-device ML Kit fallback). No new provider, key or server.
- Failure or delay of one translation never hides the other lines.
- Persian renders right-to-left; German and English stay left-to-right.
- The debug build installs next to the original app and never consumes upstream updates.

## Non-goals

- Tap-to-learn, part-of-speech colors or word saving on the third line (plain text only).
- Third line for **live fallback** subtitles (no full transcript) and for AI-assistant tools.
- Changing the existing dual-mode pipeline, `SubtitleSegment`, the store format, or the YouTube-translation fallback.
- Renaming Kotlin packages, release signing, or the release/update infrastructure.

## User-visible behavior

- Settings → Translation gains **Triple subtitles** (off by default) and, when on, **Third language**
  (chips/list over `TranslationLanguages`, default Persian).
- On: each active row shows original, translation, then the third line. Until the third line arrives the
  first two lines show as before and the third line is simply absent (no spinner, no layout jump beyond one line).
- The third language equal to the caption language or to the translation language is not requested and not
  shown (it would repeat another line).
- The translation visibility setting (always / paused / hidden) applies to the third line as well.
- If the third translation fails, the other two lines are unaffected and no error banner is raised
  (diagnostics only); toggling the mode or changing the language retries.

## Technical constraints / invariants

Preserves the [architecture invariants](../project/tech-stack.md#architecture-invariants): one WebView, replaceable
caption-provider boundary, translation via existing translators, no project server or key, all UI text in
`strings_*.xml` in all 8 interface languages, colors from `MaterialTheme`.

## Approaches considered

| Design | Backward compat | Persistence | Merge-conflict surface | Verdict |
| --- | --- | --- | --- | --- |
| A. Add `thirdText: String?` to `SubtitleSegment` | Source-compatible (default null) | `SubtitleStore` binary writer/reader must change; `copy(translatedText=…)` sites in the window loop must carry it | `SubtitleModels`, `SubtitleStore`, `PlaybackTranslation`, `SubtitleMerger` | Rejected: touches the store format and 3 hot upstream files |
| B. `translations: Map<lang, String>` replacing `translatedText` | Breaks 23 files, tests and AI tools using `translatedText` | Store format change | Very large | Rejected: widest blast radius |
| C. **Side map** `thirdLines` in UI state, produced by a second run of the existing `translatePlaybackWindow`, joined at presentation time by row id | Fully additive; dual mode untouched | None (row ids are per-load store indices; cache is the in-memory `TranslationCache`) | New file + ~3 small hooks | **Chosen** |

Choice: C. The window loop already is the "translate the rows near playback" engine; running it a second time
with a different target (its rows are private copies, its `translatedText` is the memo) gives the same
cancellation, error-stop-until-retry, prefetch-free windowing and out-of-order protection for free.

## Proposed approach / plan

New code lives in `ui/TripleSubtitles.kt` (+ `TripleSubtitleSettings.kt`); upstream-owned files get only hooks.

1. `TripleSubtitles.kt`: `ThirdLines(videoId, generation, lines: Map<Long,String>)`, `thirdLanguageToTranslate(source, target, third, enabled)`
   (null for same-language/disabled), `thirdLinesFrom(rows)`, and `runThirdLanguage(...)` which runs `translatePlaybackWindow`
   on the same display store with its own translate lambda and publishes `ThirdLines`; all non-cancellation errors are swallowed.
2. `DualSubUiState`: `tripleSubtitlesEnabled`, `thirdLanguage` (default `fa`), `thirdLines`.
3. `AppViewModel`: persist the two settings (`triple_subtitles_enabled`, `third_language`), reset in `resetAllSettings`,
   `launch` the third run beside the existing translation inside `runStoredTranslation`, restart on settings change by reloading translation.
4. Presentation: `LearningOverlayContent.thirdText`; `CompactSubtitleCard` renders it with `TextDirection.Content` below the translation, same color as translation.
5. Settings: composable `TripleSubtitleSettings` in the Translation section; strings in all 8 locales.
6. Fork identity: `app/src/debug/res/values/strings.xml` overrides `app_name`; debug `applicationIdSuffix = ".triple"`;
   the updater treats a non-official package as a local build, so upstream releases are never installed.
7. Tests (JUnit4, fake translators): see acceptance criteria. `myCHANGELOG.md` entry.

Translation scheduling: one extra window loop (one row at a time, same pace as the main one), Google first through
the existing `googleTranslator` (its retries, address memory and 2-minute recheck apply), then the on-device translator
for the pair; engine setting "on device" uses the device translator only. Third-language requests never raise `translationError`.
Cache identity: `TranslationCache` keys `(source, target, text)`, so English and Persian never collide; `thirdLines`
carries `(videoId, generation)` so a stale video or an out-of-order completion is ignored.

## Acceptance criteria

- [ ] Triple off (default): overlay content, state and translation calls are identical to before (existing tests pass unchanged).
- [ ] `thirdLanguageToTranslate` returns null when disabled, or when third == source or third == target; otherwise the normalized code.
- [ ] Third text for a row comes from the source text with target = third language, never from the second-language text.
- [ ] A third-language failure leaves `segments` (original + translation) intact and sets no `translationError`; a second-language failure leaves third lines intact.
- [ ] Rows from a previous video/generation never show (join checks `videoId` + `generation`).
- [ ] Persian third line resolves to RTL and German/English stay LTR (`TextDirection.Content`; unit test on direction choice, UI evidence screenshot).
- [ ] Settings persist; absent keys default to off / `fa`; Reset all settings restores them.
- [ ] `AppLanguageTest` / lint `MissingTranslation` pass with the new strings.
- [ ] Debug APK installs beside the original (different applicationId) and has the Triple Sub Replay label.
- [ ] `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` pass.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest --tests '*TripleSubtitles*'`, then the full CI-parity command | macOS, JDK 17 |
| Android lint/build | CI-parity command from AGENTS.md using `./gradlew` | macOS |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest` only if the system image exists | Marked not run otherwise |
| Physical-device/manual | Install debug APK, play a German video, enable Triple subtitles | Pending; owner |
| Live YouTube | Not run by the agent | Pending; owner |
| Documentation/process | Spec, myCHANGELOG.md, tech-stack invariant line | Reviewed in diff |

## Risks / edge cases

- Persian from Google can be blank for a row: that row simply has no third line.
- A second window loop doubles translation traffic while triple is on; the existing 3 s background prefetch is not duplicated for the third language (latency can be higher right after seeks). Mitigation: the in-memory cache and sentence-level requests.
- Debug `applicationIdSuffix` changes the test applicationId; `adb` commands in docs use the suffixed package.
- Upstream merge risk: hooks in `AppViewModel.runStoredTranslation`, `DualSubUiState`, `CompactSubtitleCard`, `LearningOverlayContent`, `SubtitleSettingsDialog`, `AppUpdates.kt`.
- Rollback: delete the two new files and revert the hooks; no data migration to undo.

## Release intent

`release:patch` (code changes; fork has no automatic upstream release). The fork does not publish; no label is applied because no PR is opened without owner approval.

## Implementation result

Implemented as planned (design C). Deviations from the plan:

- `UPDATE_REPOSITORY` was **not** changed: `updateChannelFor` already treats any package other than the official id
  (or `.preview` with a preview version) as a *local build* that never installs updates, so the `.triple` suffix is enough.
- `ThirdLines` carries `videoId` only (no generation); publishing is guarded by `isCurrentLoad(videoId, generation)` instead.
- `ThirdLines` is public because the public `DualSubUiState` holds it.
- The third translation falls back Google → on-device on any Google error or blank line (a slow Google does not stop the third line).
- No RTL unit test: direction is delegated to Compose (`TextDirection.Content`); verification is a device check (below).

### Fork customization map (for upstream merges)

| Area | File | Kind |
| --- | --- | --- |
| Feature code (fork-owned) | `ui/TripleSubtitles.kt`, `ui/TripleSubtitlesTest.kt` | new |
| UI state, prefs, launch of the third run | `ui/AppViewModel.kt` (3 state fields, 2 prefs reads, 2 setters, `startThirdLine`/`translateThird`/`publishThirdLines`, 1 line in `followStoredTranslation`, 3 lines in `runStoredTranslation`) | hook |
| Overlay content | `ui/CaptionPresentation.kt` (`thirdText`) | hook |
| Panel card / on-video overlay | `ui/SubtitleCard.kt`, `ui/DualSubApp.kt` (`SubtitleTimeline`), `ui/LearningPlayerRoot.kt` | hook |
| Settings | `ui/SubtitleSettingsDialog.kt` (1 line), `ui/LearningPlayerRoot.kt` (provides `LocalTripleSubtitleControls`), `ui/SubtitleColors.kt` (reset keys) | hook |
| Strings | `values*/strings_settings.xml` (3 strings each, 8 locales) | additive |
| Identity | `app/build.gradle.kts` (debug `applicationIdSuffix = ".triple"`), `app/src/debug/res/values/strings.xml` | fork |
| Docs | this spec, `myCHANGELOG.md` | fork |

Merging upstream: conflicts can only occur at the hooks above; each hook is one or a few lines. Resolve semantically
(keep upstream's change, re-apply the hook). After a merge run the CI-parity command and `TripleSubtitlesTest`.
If upstream changes `translatePlaybackWindow`'s signature, update `runThirdLanguage` (its only caller).

### Build and install on macOS

```bash
# JDK: Android Studio's bundled JBR works (Gradle 9.5 runs on it); formatCheck needs a `python` on PATH (macOS has only python3).
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk     # phone with USB debugging; package com.kienhoang.dualsubreplay.triple
```

On the phone: open **Triple Sub Replay**, finish the language setup (learning German, translate to English), then
Settings → Translation → Triple subtitles → on (third language: Persian by default).

## Validation result

| Check | Result |
| --- | --- |
| `./gradlew formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | **Passed** on macOS with JBR 25 and a temporary `python` shim (not in repo); 924+ unit tests green |
| `TripleSubtitlesTest` (11 tests: language gating, defaults, per-video join, isolation, cancellation, overlay join/visibility) | Passed |
| Debug APK | Built: `app/build/outputs/apk/debug/app-debug.apk`; package `com.kienhoang.dualsubreplay.triple`, label "Triple Sub Replay" (checked with `aapt2 dump badging`) |
| Instrumented/managed-device tests | **Not run** (no connected device; no API 36 AOSP x86_64 image) |
| Physical device, RTL rendering, rotation, live YouTube | **Not run**; pending owner |

Acceptance criteria still open: Persian RTL appearance, rotation state, and duplicate-translation work on reopening are
verified only by design (Compose `TextDirection.Content`; per-run state in the ViewModel; `TranslationCache` for repeats)
and need a device check.

Known limitations: no tap-to-learn on the third line; no third line for live-fallback subtitles; no dedicated
background prefetch for the third language; the second language is still chosen with the existing target-language setting.

## Revision 1: subtitle slots

Owner request: language activation and choice happen in the existing **Subtitle languages** dialog (and the same
block on the full settings page), not in separate settings.

### Behavior
- Three slots, always in this order: line 1 Original, line 2 Second, line 3 Third. Each has a checkbox and a language button.
- Original: checkbox plus the existing "Original language" list. Second: checkbox plus the existing target-language list
  (default **English**, changed from upstream's Vietnamese; the setup flow still sets it explicitly). Third: checkbox
  (default **off**) plus the same searchable list (default **Persian**).
- One checked slot shows one line, two show the dual layout, three show the triple layout. Persian is right-to-left
  (`TextDirection.Content`, unchanged).
- A third language equal to the caption language, or equal to the second language while the second slot is on, is not
  requested. When the second slot is off, a third language equal to it is requested like any other.

### Design (smallest change)
- Original and Second checkboxes read and write the existing `originalVisibility` / `translatedVisibility` settings
  (checked = anything but NEVER; unchecking sets NEVER, checking sets ALWAYS). No new persistence, the settings page's
  "Original captions" / "Translated captions" and the assistant stay in sync, and Reset all settings already covers them.
- Third keeps its two preferences (`triple_subtitles_enabled`, `third_language`); `tripleSubtitlesEnabled` now means "third slot checked".
- The dialog reads `LocalTripleSubtitleControls` (default: no-op controls), so `QuickLanguageSettingsDialog` and its call site are untouched.
  `LanguagePickerButtons` renders the three `SubtitleSlotRow`s; `LanguagePickerMode` gains `THIRD`.
- The third line is gated by `showThird()`: its checkbox, plus the second line's play-mode and pause rules while the second slot is on.
  It no longer depends on the second slot being visible.
- Removed: `TripleSubtitleSettings` (switch and dropdown) and its hook in the Translation section; the three `settings_triple_*`
  strings are replaced by `settings_slot_original`, `settings_slot_second`, `settings_slot_third` in all 8 locales.

### Deviations and limits
- Unchecking all three slots is allowed (same as hiding both lines today).
- Re-checking Original or Second restores "always"; a previous "on pause" mode is not remembered.
- If Original is off and the third language equals the caption language, nothing is shown for the third slot (it would need an identity "translation").
- Not verified: the dialog on a device or emulator, RTL rendering, rotation, live YouTube. The new `SubtitleUiTest` case
  compiles (`assembleDebugAndroidTest`) but was not run.

## Revision 2: RTL checkbox per slot

Owner request: every subtitle slot gets an **RTL** checkbox, so any language can be shown right-to-left.

- Each slot row in the Subtitle languages dialog shows `[ ] <slot>` and `[ ] RTL`. Checked forces that line right-to-left
  (flush right, words flowing from the right). Unchecked leaves the layout alone: the text's own script decides, exactly
  as upstream does, so Arabic or Persian in an unchecked slot still lays out right-to-left on its own.
- Defaults: Original off, Second off, **Third on** (Persian). This replaces Revision 1's fixed "third line is always content-directed".
- Saved in `rtl_original`, `rtl_second`, `rtl_third`; all three are in `TRIPLE_SETTING_KEYS`, so Reset all settings clears them.
- Implementation: `SlotRtl` in `DualSubUiState`; `LineDirection` provides `LayoutDirection.Rtl` around a line. The card and the video overlay
  read `LocalTripleSubtitleControls.current.rtl`, so no composable signature changed. The card needs one changed line
  (`lines.forEachDirected(slotRtl)`) and the overlay one changed call; the original and translated line composables are untouched.
- Not verified on a device: how tap-to-learn word flow and highlighting look inside an RTL line.
