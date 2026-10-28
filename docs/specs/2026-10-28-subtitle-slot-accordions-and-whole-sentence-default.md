# Subtitle slot accordions and Whole sentence default (Triple Sub Replay fork)

## Status

Approved by the fork owner (2026-10-28) for local implementation and local commits. No push, PR, merge or release. Written after the implementation (commit `dbc162d`), against the request that asked for it.

## Goals

1. In both subtitle configuration interfaces, each slot's controls sit in their own collapsible panel: **Original Subtitle**, **Subtitle 1** (`SubtitleSlot.SECOND`), **Subtitle 2** (`SubtitleSlot.THIRD`). The two interfaces are the Subtitle languages popup (`QuickLanguageSettingsDialog`) and the Languages card of Settings (`SubtitleSettingsDialog`).
2. Each header reads `Role · Language`, resolved from the current settings. An unresolved or Auto language shows `Auto`.
3. Initial state: every panel collapsed, and so is every card on the Settings page (the Languages card previously started open). The first request had Subtitle 2 open; the owner changed this the same day.
4. With no saved Caption format, the app uses **Whole sentence**.

## Non-goals

No new settings screen. No change to slot state, persistence, translation, Practice, Anki or backup formats. Expansion state is not persisted. The rendering algorithm for caption formats is unchanged.

## Design

- Both interfaces already share `LanguagePickerButtons`, so the accordion is built once there: a private `SlotAccordion` wraps each `SubtitleSlotRow` in the existing `SettingsExpandableCard` (chevron, `Role.Button`, expanded/collapsed `stateDescription`, `AnimatedVisibility`). `SettingsExpandableCard` now skips an empty summary line. The accordions always list Original, Subtitle 1, Subtitle 2 in logical order, whatever the playback order.
- The header uses `slotRoleLabel(slot, language)`, so it follows the label convention (`subtitle_role_with_language`). The language is the same value the slot's picker button shows, or `Auto` when blank.
- Expansion state lives in `LanguagePickerState` (already created above the language-picker early return in both interfaces), so choosing a language does not reset the panels. Closing and reopening the interface resets them to the defaults. Panels are independent: any number may be open, or none.
- The checkbox, RTL, language picker and `SlotLayoutControls` (size, order, alignment) stay inside `SubtitleSlotRow`, reading and writing the same `TripleSubtitleControls` state as before. Collapsing only hides them.
- Caption format: `DEFAULT_CAPTION_FORMAT = WHOLE_SENTENCE` (`ui/CaptionFormat.kt`) is the single default for `storedCaptionFormat`, `DualSubUiState.captionFormat`, the reset in `AppViewModel`, and the `SubtitleSettingsDialog` parameter. A saved `caption_format` is read as before; a missing or unknown value gives the default. Reset all settings removes the key, so Whole sentence returns.
- The `legacySplit` parameter of `storedCaptionFormat` is removed. Users with no saved `caption_format` used to get Short phrases unless `split_long_sentences` was false; they now get Whole sentence on upgrade. This is the requested behavior. `SPLIT_LONG_SENTENCES_PREFERENCE` is no longer read; it stays in `RESETTABLE_SETTING_KEYS` so a reset still clears the old key.

## Acceptance criteria

1. Both interfaces show three panels, headed `Original Subtitle · <language>`, `Subtitle 1 · <language>`, `Subtitle 2 · <language>`.
2. First open: all three panels, and the Languages card on Settings, are collapsed. Tapping a header toggles only that panel.
3. Toggling a panel changes no slot setting (visibility, language, RTL, size, order, alignment) and no translation.
4. Picking a language and returning keeps the panels as they were.
5. Fresh preferences, or a missing or unknown `caption_format`, give Whole sentence; a saved value is kept; a saved choice survives restart; reset restores Whole sentence.

## Validation

- Unit: `CaptionFormatTest.missingOrUnknownPreferenceIsWholeSentenceAndSavedChoicesSurvive` (default constant, `DualSubUiState` default, missing and unknown values, every saved value round-trips).
- Instrumented (written and compiled, **not run**): `SubtitleUiTest.slotAccordionsStartClosedAndToggleIndependently`, plus the existing popup and settings tests updated to expand the panels before clicking the pickers.
- Quality gate on 2026-10-28: `formatCheck`, `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest` passed. `complexityCheck` failed on `DualSubExperience` in `DualSubApp.kt` (cyclomatic complexity 25, limit 25); that file is not part of this change and the failure was not checked against the previous commit.
- Not verified: the UI on a device or emulator, the Light theme, small-screen scrolling, and the reset and restart behavior beyond the unit test.

## Release intent

Patch (default). The change touches app code, so `release:skip` does not apply.

## Implementation notes

- Files: `ui/SubtitleSettingsDialog.kt`, `ui/CaptionFormat.kt`, `ui/AppViewModel.kt`, `CaptionFormatTest.kt`, `SubtitleUiTest.kt`, `myCHANGELOG.md`.
- The row's own checkbox label still repeats `Role · Language` under the header, so once a panel is open, text matches in tests must use `onAllNodesWithText(...).onFirst()`.
- `SubtitleSettingsDialog` sets `languagesOpen` to false; tests that read the slot controls on the Settings page tap `settings_group_languages` first.
