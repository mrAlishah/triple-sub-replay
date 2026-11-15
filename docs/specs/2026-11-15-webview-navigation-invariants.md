# WebView navigation and origin invariants

## Status

Draft. Records behavior that already exists and is enforced by tests; no code change. Opened because `AGENTS.md` and two tests state this contract but no spec or project document does (checked 2026-11-15).

## Context / problem

[Technical context](../project/tech-stack.md) names `SingleYouTubePage` as the one WebView, [YouTube tabs](2026-10-27-youtube-tabs.md) records the single WebView and per-tab profiles, and [background play](2026-10-13-background-play-and-earphone-controls.md) records the playback service. What decides where a main-frame navigation goes, and what page scripts must re-check, is written down only in `AGENTS.md` and in `WebNavigationPolicyTest` and `PlaybackArchitectureTest`.

## Goals

Make the navigation contract reviewable in one place.

## Non-goals

No behavior change, and no restatement of what the tabs and playback specs already say.

## User-visible behavior

None.

## Technical constraints / invariants

`classifyMainFrameUrl` (`ui/YouTubeBrowserScreen.kt`) returns one of four decisions for a main-frame URL:

| Decision | When |
| --- | --- |
| `BLOCK` | The URL does not parse, its scheme is not `https`, it carries user-info, or its port is neither default nor 443 |
| `GOOGLE_SIGN_IN` | The host is one of `trustedGoogleSignInHosts` (stays in the embedded page during sign-in) |
| `YOUTUBE_WEB` | The host is `youtube.com` or ends in `.youtube.com` (loads in the embedded page) |
| `OPEN_EXTERNAL` | Any other HTTPS host (opens in the browser) |

JS snapshot and replay scripts keep re-verifying the executing origin (`https:` and `*.youtube.com`); `PlaybackArchitectureTest` asserts the literal script text, so changing a script means changing that test on purpose. A second online player or WebView is never added.

## Proposed approach / plan

Documentation only: this file.

## Acceptance criteria

- [ ] The table matches `classifyMainFrameUrl` (checked against the source on 2026-11-15).
- [ ] `WebNavigationPolicyTest` (four tests: trusted URLs embed, other HTTPS origins go outside, deceptive user-info, non-HTTPS and active-content schemes are blocked, restored untrusted URLs return to YouTube home) and `PlaybackArchitectureTest` pass.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest --tests "*WebNavigationPolicyTest" --tests "*PlaybackArchitectureTest"` passes | Local or CI |
| Documentation/process | Links resolve; table re-checked against `classifyMainFrameUrl` | Review |

## Risks / edge cases

The table can go stale if `trustedGoogleSignInHosts` or the rules change; the tests, not this file, are authoritative.

## Release intent

`release:skip` (docs only).
