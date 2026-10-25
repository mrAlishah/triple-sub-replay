# Changelog (Triple Sub Replay fork)

All notable changes made in this fork. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Upstream releases are tracked in `CHANGELOG.md`; fork entries live only here.

## [Unreleased]

### Added

- **Tap words on the third line.** With Word learning and Tap to learn on, the third subtitle line works like the others: select a word or phrase, hear it, translate it (into the learning language), and save it as a word of the third language. No Persian dictionary or analyzer is added; part-of-speech colors do not apply to the third line.
- **Triple subtitles.** The Subtitle languages dialog now has three slots, Original, Second and Third, each with a checkbox and a language list; checked slots show on the video and in the panel in that order. The Third slot is off by default and defaults to Persian, which is laid out right-to-left. Its line is translated from the video's own captions and a failure of it never hides the other lines. See `docs/specs/2026-10-25-triple-subtitles.md`.

### Changed

- **Exports carry the third line.** The Anki note's Translation field (AnkiDroid and AnkiConnect) lists each saved sentence translation after the second line, one right-to-left-safe block each; the note type and its fields are unchanged. The Anki TSV export gets a trailing `sentenceTranslations` column (`fa: text` per line) that import reads by name, so older files still import.
- **Persian words stay whole.** A zero-width non-joiner between two letters no longer splits a word in tap-to-learn (for example "می‌خواهم"). Saved Persian words with Arabic or Persian spellings of yeh and kaf, or with vowel marks, count as the same word.
- **Saved words keep the third line.** Saving a word while the third subtitle is shown also stores that line's real translation of the sentence under its language code (for example Persian). Pending or failed translations and the word's own two languages are never stored. Saving the same word again keeps its review schedule and earlier translations and adds new languages. Practice and the saved-word view show the extra translations after the answer is revealed; backups include them and older backups still import.
- **RTL checkbox per subtitle slot.** Each slot in the Subtitle languages dialog has an RTL checkbox that shows that line right-to-left (flush right). Only the Third slot is on by default, for Persian; unchecked slots keep upstream's automatic direction.
- **Default second language.** The translation language now defaults to English instead of Vietnamese.
- **Debug build identity.** The debug app is called "Triple Sub Replay" and installs as `com.kienhoang.dualsubreplay.triple`, beside the original app, and is never offered upstream updates.
