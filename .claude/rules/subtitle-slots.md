---
paths:
  - "app/src/main/java/**/ui/SubtitleSlot*.kt"
  - "app/src/main/java/**/ui/TripleSubtitle*.kt"
---
<!-- Generated from AGENTS.md by tools/claude_instructions.py. Do not edit; run `python3 tools/claude_instructions.py sync`. -->

# Subtitle slots: roles, order, size, alignment

## Subtitle slot conventions

- Three logical slots, named by role, never by language: **Original Subtitle** (`SubtitleSlot.ORIGINAL`), **Subtitle 1** (`SECOND`, the "Second Subtitle": the first translation) and **Subtitle 2** (`THIRD`, the "Third Subtitle": the second translation). Subtitle 1 is not the video's original track.

- Short labels (`subtitle_role_*`, `slotRoleLabel`) go in rows, fields and chips; the descriptive names (`settings_slot_*`) go in longer text. A label may add the slot's current language as a hint (`Subtitle 1 · English`), resolved from the settings and dropped when it is Auto or unresolved; never write a language into a role label. Never call a slot "Meaning (xx)" in the UI; "meaning" stays only where it means a dictionary meaning.

- Display naming and persistence: do not rename `SavedWord.meaning`/`meanings`/`personalNotes`, the JSON backup keys (they are also the storage keys), or language codes. Anki and TSV use the role names since 2026-10-27 ([spec](docs/specs/2026-10-27-anki-field-names-follow-subtitle-roles.md)): note type `TripleSub Replay v5` (v4 plus `Word audio` and `Sentence audio`, owner pick 2026-11-12, [spec](docs/specs/2026-11-12-triplesub-anki-v5-card-media.md); v4 is v3 plus a `Source Title` field, owner request 2026-10-29, [spec](docs/specs/2026-10-29-source-title-tracking-and-filtering.md); v4 and v3 stay as AnkiConnect fallbacks and are never changed) with fields `Subtitle 1`, `Subtitle 2`, `Text` (formerly `Meaning 1`, `Meaning 2`, `Desc`); TSV columns `subtitle1`, `subtitle1Language`, `subtitle2`, `text`, and import still reads the old headers. Changing a field list needs a new note type name, because AnkiDroid reuses a type only when name and fields match exactly.

- Slot identity and visual position are separate. Language, visibility, RTL, size, translation state and every meaning/export mapping stay keyed by the slot (`stableId`); the top-to-bottom order is only the `subtitle_order` preference (`SubtitleSlotLayout.kt`) and must never change them. Hidden slots are skipped without a gap and keep their place; normal, fullscreen and the subtitle list use the same `TripleSubtitleControls.order`. Without a saved order the older Above/Below translation position decides; choosing it clears the saved order.

- Size is a per-slot multiplier (`font_scale_<id>`, 0.7 to 1.6, default 1) on top of the global text size. Alignment follows direction: a line flush left when left-to-right and flush right when right-to-left (the RTL checkbox forces it, otherwise the text's own script), in every display mode; the overlay's placement does not change. Each slot also has its own alignment (`subtitle_alignment_<id>`: Auto, Left, Center, Right; default Auto, which is the direction rule above; the old single `subtitle_alignment` is only read as the default) in the Subtitle languages dialog and the Languages card of Settings; left and right are absolute, not tied to the interface language. On the Settings page, Languages and Reading (which includes Default view) are upstream's single-open More settings sections, all closed at first (owner decision 2026-11-07, [sync record](docs/specs/2026-10-30-low-divergence-fork-architecture.md#upstream-sync-record-e88009a-into-mydevelop-2026-11-07)); inside Languages, each subtitle slot (Original, Subtitle 1, Subtitle 2) is still its own `SettingsExpandableCard` panel, closed at first and independent of the others, and Search settings opens the slot's panel before it scrolls to a slot's language button (owner request 2026-10-28, [spec](docs/specs/2026-10-28-subtitle-slot-accordions-and-whole-sentence-default.md)).
