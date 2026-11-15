---
paths:
  - "app/src/main/java/**/playback/*.kt"
  - "app/src/main/java/**/ui/YouTube*.kt"
  - "app/src/main/java/**/ui/BrowserTabs.kt"
  - "app/src/main/java/**/ui/*WebScripts.kt"
  - "app/src/test/**/PlaybackArchitectureTest.kt"
  - "app/src/test/**/WebNavigationPolicyTest.kt"
---
<!-- Generated from AGENTS.md by tools/claude_instructions.py. Do not edit; run `python3 tools/claude_instructions.py sync`. -->

# WebView, playback and navigation

## Architecture rules

- **Player** ([details](../../docs/project/tech-stack.md#player-and-youtube-page)): exactly one WebView (`SingleYouTubePage`); never add a second online player or WebView, or offline download/Media3 playback. Tabs load one at a time into that page; a tab's page uses its `tabCookieManager`, never `CookieManager.getInstance()`. Background play and earphone controls only send scripts to the page video.

- **Page scripts** ([details](../../docs/project/tech-stack.md#player-and-youtube-page)): main-frame navigation goes through `classifyMainFrameUrl`; scripts keep re-verifying the executing origin (`https:` + `*.youtube.com`), and `PlaybackArchitectureTest` asserts their literal text.

## Full text: docs/project/tech-stack.md, section 'Player and YouTube page'

### Player and YouTube page

- Exactly one WebView exists (`SingleYouTubePage` in `ui/YouTubeBrowserScreen.kt`). YouTube tabs (owner request 2026-10-27, [spec](../../docs/specs/2026-10-27-youtube-tabs.md)) are saved addresses (`ui/BrowserTabs.kt`) loaded one at a time into that page, so only the open tab plays; with every tab closed the page leaves composition and an empty page offers a new tab. A tab of another Google account (Settings → YouTube tabs → Use different Google accounts, off by default) replaces the WebView, because a WebView profile must be set before the WebView loads anything (`ui/YouTubeTabAccounts.kt`); use the tab's `tabCookieManager`, never `CookieManager.getInstance()`, in the page. Online replay seeks the native YouTube page video via a JS polling bridge. Replay and online vocabulary examples seek the same page video. Offline video downloading and local Media3 playback are removed; never add a second online player/WebView.
- Screen-off playback and earphone controls (owner decision 2026-10-13): `playback/WebVideoPlaybackService.kt` is a `mediaPlayback` foreground service owning a media session and notification that only send play/pause/seek scripts to the page video; it is not a second player. The YouTube page is always told it is visible (`WEB_KEEP_PLAYING_WHEN_HIDDEN_SCRIPT`), so videos keep playing with the screen off for every account. See the [spec](../../docs/specs/2026-10-13-background-play-and-earphone-controls.md). While a loop, Repeat, a play mode other than Normal or Skip silence steers a playing video (`StudyOffScreen.steering`), the page poll and the playback request keep running off screen (about once a second), so they keep working with Home or the screen off ([spec](../../docs/specs/2026-11-13-card-sound-places-and-background-loop.md)).
- Main-frame navigation goes through `classifyMainFrameUrl` → `YOUTUBE_WEB` (embed) / `GOOGLE_SIGN_IN` (embed during sign-in flow) / `OPEN_EXTERNAL` (browser) / `BLOCK`. JS snapshot/replay scripts must keep re-verifying the executing origin (`https:` + `*.youtube.com`); including asynchronous callbacks, and `PlaybackArchitectureTest` asserts the literal script text. Sign-in pages are a distinct navigation case, not permission to run playback scripts on arbitrary origins.
