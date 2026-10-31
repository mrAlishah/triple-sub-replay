# Check for updates against the TripleSub Replay releases

## Status

Implemented. Requested by the owner on 2026-10-31 after the project-links audit found that the updater still
asks the original repository.

## Context / problem

- `UPDATE_REPOSITORY` is `hoangkien1703/dual-sub-replay`. The latest-release API, `update-info.json`, the rolling
  `preview` release and the "Open GitHub" button all derive from it.
- A fork release build keeps the official package id (`com.kienhoang.dualsubreplay`), so `updateChannelFor`
  returns `Official` and the updater looks for the original's `DualSub-Replay.apk`. It could offer the original
  APK as a TripleSub Replay update (the earlier decision in the
  [triple-subtitles spec](2026-10-25-triple-subtitles.md) only covered the `.triple` debug build).
- Local `.triple` builds only open GitHub, and that button opens the original's releases page.
- The fork's releases (checked 2026-10-31: v2.8.5, v2.8.2, v2.8.0, v2.7.14) ship `TripleSub-Replay.apk` and
  `TripleSub-Replay.apk.sha256`, with no `update-info.json`. The original ships `DualSub-Replay.apk`.

## Goals

- Every updater URL (latest release API, `update-info.json`, preview API and pages, "Open GitHub") uses
  `mrAlishah/triple-sub-replay`.
- The official asset is `TripleSub-Replay.apk` (+ `.sha256`), as the fork publishes it.
- A missing `update-info.json` keeps falling back to the releases API (existing behavior).

## Non-goals

- No new update mechanism, host, signature rule or setting. No change to the preview asset naming
  (`DualSub-Replay-PR<N>-build<run>-preview.apk`, written by `pr-preview-auto.yml`).
- Remote switches and the Report a problem issue address keep using the original repository
  (`UPSTREAM_REPOSITORY`): the switches are the original maintainer's, and issues are not part of this change.
- The release tooling (`tools/automatic_release.py`, workflows) is not edited; see the open item below.

## Acceptance criteria

- [x] `UPDATE_REPOSITORY`, `UPDATE_RELEASES_PAGE_URL` and `UPDATE_INFO_URL` point at the fork; the releases page
  equals `BuildConfig.FORK_RELEASES_URL` (`ReleaseLinksTest`).
- [x] A fork release newer than the installed version is offered; one whose APK has another name (so also the
  original's `DualSub-Replay.apk`) is not (`AppUpdatesTest`).
- [x] Remote switches and the problem report still use `hoangkien1703/dual-sub-replay`
  (`ReleaseLinksTest`, `ProblemLogTest`).
- [x] `assembleDebug` passes.

## Release intent

`release:patch` (default): the change reaches the APK.

## Open items / risks

- UNVERIFIED on a device: installing a fork update needs the fork's APK to be signed with the key of the
  installed build; Android refuses it otherwise.
- `tools/automatic_release.py` still writes `DualSub-Replay.apk` into `update-info.json`. The fork's own
  releases evidently use another process; if it ever publishes `update-info.json` it must say
  `"apk": "TripleSub-Replay.apk"`, or the file is ignored and the API fallback is used.
- The fork has no `preview` release, so a preview build finds no update there.

## Implementation record and validation


Implemented as planned: `UPDATE_REPOSITORY` is the fork, `OFFICIAL_APK_NAME` is `TripleSub-Replay.apk`, and the new
`UPSTREAM_REPOSITORY` keeps `RemoteSwitches.kt` and `ProblemLog.kt` on the original repository. Tests updated:
`ReleaseLinksTest`, `AppUpdatesTest`, `UpdateRemindersTest` (fixtures).

| Check | Result |
| --- | --- |
| `testDebugUnitTest` for `ReleaseLinksTest`, `AppUpdatesTest`, `UpdateRemindersTest`, `ProblemLogTest` | Passed (29 tests) |
| `:app:assembleDebug` | Passed |
| Update offer and install of a real fork release on a phone | Unverified |
| Full unit suite, lint, instrumented tests | Not run |
