# Changelog (Triple Sub Replay fork)

All notable changes made in this fork. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Upstream releases are tracked in `CHANGELOG.md`; fork entries live only here.

## [Unreleased]

### Added

- **Triple subtitles.** Settings → Translation → Triple subtitles (off by default) shows a third line under the translation, translated from the video's own captions; the Third language defaults to Persian and can be any translation language. Persian lines are laid out right-to-left. A failure of the third line never hides the original or the translation. See `docs/specs/2026-10-25-triple-subtitles.md`.

### Changed

- **Debug build identity.** The debug app is called "Triple Sub Replay" and installs as `com.kienhoang.dualsubreplay.triple`, beside the original app, and is never offered upstream updates.
