---
paths:
  - "app/src/main/res/values/strings*.xml"
  - "app/src/main/res/xml/locales_config.xml"
  - "app/src/main/java/**/ui/theme/*.kt"
  - "app/src/main/java/**/ui/Settings*.kt"
  - "app/src/main/java/**/ui/SubtitleSettingsDialog.kt"
  - "app/src/main/java/**/ui/DialogClose.kt"
  - "app/src/main/java/**/ui/AppLanguage.kt"
  - "app/src/main/java/**/data/SettingsTransfer.kt"
---
<!-- Generated from AGENTS.md by tools/claude_instructions.py. Do not edit; run `python3 tools/claude_instructions.py sync`. -->

# Strings, theme, dialogs, settings page, backup and sharing

## Architecture rules

- **Strings** ([details](../../docs/project/tech-stack.md#interface-languages)): user-visible text lives in `res/values*/strings_<area>.xml` with every translated copy; never a UI string literal in Kotlin.

- **Settings** ([details](../../docs/project/tech-stack.md#settings-backup-and-sharing)): every new setting goes in `SETTING_SPECS` (`data/SettingsTransfer.kt`), never SHARE for anything that uses an API key, and every switch goes in `SearchableSetting`.

## UI conventions

- Every screen, panel, dialog, sheet and overlay uses the app theme: `DualSubTheme` (`ui/theme/Theme.kt`): dark teal by default, or the App colors the user picked (Black, Navy, Slate, Plum, Forest, or Light), with the user's accent. The overlay on the video stays dark on every theme; subtitle and part-of-speech colors use darker shades on light cards (owner decision 2026-10-16). UI drawn outside `DualSubApp`, such as overlays that `LearningPlayerRoot` places beside it, wraps itself in `DualSubTheme`; otherwise Material's light purple defaults show (`AiAssistantPanelUiTest` checks the assistant panel's color).

- Take colors from `MaterialTheme.colorScheme` roles, never from Material's defaults or new hex literals. If a component needs a role that `dualSubColorScheme` leaves at Material's purple default (for example `tertiary`), set that role in `Theme.kt` instead of coloring the component directly. The amber/red problem tints in `OnlineTranslationNotice.kt` and `AiAssistantUi.kt` (darker on the light theme) are the only shared status colors. Check a UI change on the Light theme too.

- Every dialog has an X at the top right (owner request 2026-10-30, [spec](../../docs/specs/2026-10-30-dialog-close-button.md)): use `DialogTitle` (or `CloseIconButton` in a full-screen header) from `ui/DialogClose.kt`. The X only closes, like Back or Cancel; a lone Close/Cancel/Back button goes, real choices stay. No X on the Security update dialog or on confirmations the person must answer (delete, clear, reset), nor on TV-layout dialogs, where text buttons suit the remote. A dialog with typed text asks before dropping changed text (`DiscardChangesDialog`).

- The settings page (owner pick 2026-11-11, [spec](../../docs/specs/2026-11-11-settings-groups-and-current-values.md)) lists its sections under four groups, Subtitles, Study, Video and App, from daily use to rare; `MoreSettingsSection` (`ui/SubtitleSettingsDialog.kt`) holds the order, group and icon, and its keys stay stable because page memory, search and test tags use them. A new section joins a group; never add a catch-all heading such as "More settings". The line under a closed section's name is the current value of the setting people open it for (`settingsSectionSummary` in `ui/SettingsSummaries.kt`), not a list of what is inside. "Subtitle text" holds what the words look like and "Screen layout" holds where things sit. Every switch on the page, the AI assistant's included, is listed in `SearchableSetting` (`ui/SettingsSearch.kt`) so Search settings finds it; `SettingsSearchTest` fails when one is missing.

- Before handing off a UI change, look at it in the screenshots the Compose tests save (`saveUiEvidence`) or on a device, and check that it matches the screens around it.

## Full text: docs/project/tech-stack.md, section 'Interface languages'

### Interface languages

- User-visible text lives in `app/src/main/res/values/strings_<area>.xml` (English, the default) with the same file in every translated `values-*` folder; never add a UI string literal in Kotlin. The offered interface languages are `APP_LANGUAGES` in `ui/AppLanguage.kt` and `res/xml/locales_config.xml`; with no saved choice the app follows the device. `AppLanguageTest` and lint `MissingTranslation` fail when a language lacks a string or a placeholder differs. Diagnostic exception text in `data/` and `translation/` stays English. See the [interface languages spec](../../docs/specs/2026-10-02-app-interface-languages.md).

## Full text: docs/project/tech-stack.md, section 'Settings backup and sharing'

### Settings backup and sharing

- Backup & sharing (owner pick 2026-11-10, [spec](../../docs/specs/2026-11-11-backup-and-setup-sharing.md)): every key in `dual_sub_preferences` and `ai_assistant` has a scope and type in `SETTING_SPECS` (`data/SettingsTransfer.kt`): SHARE (look and study, travels in shared setup links), BACKUP (personal, Anki apps, every AI choice) or NEVER (the AI custom address, full access, sign-ins, phone state). Add each new setting there, read it through a `*_PREFERENCE` constant, and keep anything that uses an API key out of SHARE; `BackupSharingTest` fails otherwise. Values from a backup file or link are type- and range-checked; unknown keys are skipped.
