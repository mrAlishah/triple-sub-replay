# Subtitle roles, display order, per-slot size, alignment and Practice Text (Triple Sub Replay fork)

## Status

Approved by the fork owner (2026-10-27) for local implementation and local commits. No push, PR, merge or release.

## Goals

1. Name the three slots by role everywhere in the UI: **Original Subtitle**, **Subtitle 1** (the *second* subtitle, `SubtitleSlot.SECOND`), **Subtitle 2** (the *third* subtitle, `SubtitleSlot.THIRD`). Where room allows, the role is followed by the slot's current language: `Subtitle 1 · English`. Auto or unresolved languages show the role alone.
2. Practice "Desc" label becomes "Text": a bounded-height multiline field that scrolls inside itself.
3. A line's alignment follows its direction in the video overlay (normal and fullscreen): LTR flush left, RTL flush right.
4. Independent size per slot (the app had only one global text size).
5. A persistent top-to-bottom order for the three slots, set in the Subtitle languages dialog.

## Non-goals

No data migration. `SavedWord.meaning`/`meanings`/`personalNotes` and the JSON backup keys are unchanged. The Anki fields and TSV columns were renamed afterwards, see [the follow-up spec](2026-10-27-anki-field-names-follow-subtitle-roles.md). No new translation provider.

## Design

- Identity and position are separate. `SubtitleSlot` (ORIGINAL, SECOND, THIRD) is the identity; its RTL, visibility, language, size and meaning mapping stay keyed by it. Order is a separate preference `subtitle_order` (`original,second,third`, a permutation); invalid or missing values fall back to the default. When absent, the old `translation_position` = above yields `second,original,third`, so nobody's layout changes on upgrade. Choosing the older Above/Below chip clears the stored order so the two never disagree.
- Order is independent of visibility and language: hidden slots are skipped (no gap) and keep their place.
- Per-slot size: `font_scale_original|second|third`, a multiplier (0.7 to 1.6, default 1.0) applied on top of the global text size, so existing users keep their sizes and Android font scaling still applies.
- State travels in the existing `TripleSubtitleControls` composition local (no upstream signature changes). Logic lives in `ui/SubtitleSlotLayout.kt`.
- Alignment: the overlay no longer centers text in fullscreen. A line written the other way from the screen fills the width and starts on its own side (`lineSide`, `TextDirection.Content`); an RTL checkbox still forces RTL. The overlay's own placement is unchanged.
- Dialog UI: each slot row gets Move up / Move down buttons with its position and a size slider.

## Validation

Unit tests: order parsing and all six permutations, hidden-slot gaps, default and legacy migration, size clamping and per-slot independence, label with language and fallback. Compose tests: the rows, and the Practice Text field. Instrumented tests were written but, unless stated in the report, not run here.

## Implementation notes

- Built as designed. Logic and the dialog controls live in `ui/SubtitleSlotLayout.kt`; `TripleSubtitleControls` carries `order`, `onMove`, `fontScales`, `onFontScaleChange`; `AppViewModel` loads, saves and resets them; the overlay (`LearningPlayerRoot`) and the subtitle list card (`SubtitleCard`) draw by `order`.
- Per-slot size was missing (only the global `font_scale` existed), so it was added; defaults of 1.0 keep every existing size.
- The Practice "Text" field was already a multiline field (`minLines = 3`); only its limit changed (12 to 6 lines, then it scrolls inside itself).
- `practice_meaning_label` was removed (no longer used). No Anki, JSON or TSV key changed.
- Fullscreen no longer centers text: the owner asked for left/right alignment by direction, which replaces the earlier centered fullscreen look.
- Unused after this change but kept to limit upstream divergence: the `translationPosition` parameters, `subtitleLineOrder`, `forEachDirected`, and the `centered` parameter of `SelectableSubtitleText`.

### Verification (this machine, JDK 25, no device)

Passed: `formatReport` plus `tools/check_format_ratchet.py` (run directly because Gradle cannot start `python` here), `complexityCheck`, `testDebugUnitTest` (all), `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`.
Not run: instrumented/Compose tests (no emulator or phone), including the new `SubtitleSlotLayoutUiTest`; no visual check of the overlay alignment, fullscreen layout or the dialog on a device or on the Light theme.

### Review follow-ups

- The Above/Below chip now shows where the original really sits relative to Subtitle 1 (derived from the order), and tapping the chip that is already shown does nothing, so it can no longer wipe a saved order.
- Known gap: the AI assistant has no `AiSetting` for the display order or the per-slot sizes; it still drives Above/Below through `setTranslationPosition`. Add them in a follow-up if wanted.
- Not covered by automated tests: `AppViewModel` persistence/reset of the order, `CompactSubtitleCard` ordering with a hidden slot, fullscreen alignment.

### Alignment option (owner request, same day)

`subtitle_alignment` (Auto, Left, Center, Right; default Auto = the direction rule) is a chip row under Translation position in Settings. It sets the lines' text alignment and where the lines sit in the subtitle box, in the normal and fullscreen overlay and for the third line; left and right are absolute (`AbsoluteAlignment`). It is not applied to the subtitle list cards. Verified by unit tests and the full gate; not checked on a device.

### Per-slot alignment and collapsible Settings groups (owner request, same day)

- Alignment is now per slot (`subtitle_alignment_original|second|third`, default Auto); the single chip row is gone. The chips sit in each slot's controls in the Subtitle languages dialog. A value saved under the first version's single `subtitle_alignment` key is read as every slot's default, and that key still resets with the other settings. Each overlay line is placed in the subtitle box by its own slot's alignment.
- On the Settings page, Languages, Reading and Default view are now `SettingsExpandableCard`s with a one-line summary, like the sections under More settings. Languages starts open, the other two closed; they open independently and remember their state across rotation. Instrumented tests that used Reading or Default view now open them first.
- The quick languages popup now scrolls, since each slot gained controls.
- Not run: instrumented tests and a visual check on a device.
