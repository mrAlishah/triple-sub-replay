# Android TV layout (beta)

## Status

Implemented (owner request 2026-10-29/30: "Full TV mode", with every recommendation from the
discussion, an Android TV setting marked "(beta)" that can never turn on on a phone). Merge waits
for the owner's check on the Android TV emulator and, if possible, a tester with a real TV.

## Context / problem

A Reddit user asked for the app on Android TV. The phone APK already installs on a TV (minSdk 26,
no ABI filter), but:

- it has no icon on the TV home screen (no `LEANBACK_LAUNCHER` entry or banner);
- a remote cannot use it: the app had no key handling, and picking words is touch only;
- browsing m.youtube.com with arrow keys does not work (it is a touch site);
- in landscape the player switches to the floating subtitle box, which a remote cannot reach.

The owner has no Android TV, so checks run on the Android TV emulator.

## Goals

- One APK for phones and TVs. On a TV the TV layout turns on by itself; Settings → Android TV
  (beta) → "TV layout (beta)" turns it off. On phones and tablets the switch is greyed out and
  off, with a note that it needs an Android TV, and nothing below changes for them.
- The app has an icon and a banner on the TV home screen; a touch screen is not required.
- **TV home**: a remote-friendly start page instead of the YouTube page: search YouTube (the
  app's own YouTube search, no key), results as a grid of big cards, the open tabs as "Continue
  watching", and buttons for the YouTube page, Saved words, Progress and Settings.
- **Remote on the video**: OK plays/pauses, ◀/▶ go to the previous/next line, ▲ replays the
  current line, ▼ moves to the subtitle panel, Back returns to TV home. Play/pause, rewind,
  fast-forward and previous/next media keys work everywhere in the TV layout.
- **Words without dragging**: OK on a subtitle line opens the line with a big "Replay line" button
  and each word as a big button; picking a word opens the usual word card.
- The video and the subtitle panel sit side by side; panel text is 1.25× larger and focused items
  get a clear ring.
- YouTube pages other than a video (home, channels, sign-in) move focus between links and buttons
  with the arrow keys; Back goes back in the page, then to TV home.

## Non-goals

- No Google Play / Leanback library, no separate TV APK, no voice search, no YouTube TV
  (youtube.com/tv) site.
- No change for touch devices: every new behavior is behind the TV layout.
- The AI assistant panel is not redesigned for remotes (it still works with the on-screen
  keyboard).

## User-visible behavior

- Phone: Settings → Android TV (beta) shows "TV layout (beta)", off and greyed out, and "Only on
  Android TV. Install this same app on an Android TV or Google TV to try it."
- TV, first start: language setup and guide as on phones, then TV home.
- TV home → search "japanese podcast" → pick a card → the video opens with the panel beside it and
  the video has focus. ▶ jumps to the next line, ▼ then OK on a line → Replay line / words → pick
  a word → word card. Back → TV home; Back on TV home with a video open returns to it, otherwise
  leaves the app as Home does.

## Technical constraints / invariants

- Still exactly one WebView (`SingleYouTubePage`): TV home covers it while shown and pauses the
  video; opening a result uses `acceptSharedText` like a shared link.
- Keys reach `MainActivity.dispatchKeyEvent` first; the TV layout's handler (`TvRemoteKeys`) acts
  only while the TV layout is on, and never on dialogs (they are their own windows).
- The page script for arrow-key focus re-verifies the executing origin (`https:` + `*.youtube.com`)
  like the other page scripts.
- User-visible text in `strings_tv.xml` in all 8 interface languages.

## Proposed approach / plan

1. `ui/TvLayout.kt`: TV detection (`UiModeManager` television mode, `FEATURE_LEANBACK`, Fire TV
   feature), the `tv_layout` preference (resettable), `LocalTvLayout`, the settings section, the
   focus ring.
2. Manifest: `LEANBACK_LAUNCHER`, `android:banner`, `touchscreen` and `leanback` not required.
3. `ui/TvRemote.kt`: pure key → action mapping (`tvRemoteAction`), line targets
   (`tvLineStartMs`), and the activity-level dispatcher.
4. `ui/TvHome.kt`: search (YouTube's web search request already used by the assistant), grid,
   continue-watching row, buttons.
5. `ui/TvLineWords.kt`: the line dialog with Replay line and word buttons.
6. `ui/TvPageScripts.kt`: arrow-key focus script for non-video YouTube pages; a mobile user agent
   only if the TV's WebView sends none.
7. Player: in the TV layout, no automatic landscape overlay; side-by-side panel; larger panel text;
   panel lines open the line dialog.
8. AI assistant: may read and change the TV layout setting (waits for a tap: it changes the
   interface).

## Acceptance criteria

- [ ] Phone (CI emulator): the TV switch is disabled and off; the player, panel and settings
      behave as before (existing UI tests pass).
- [ ] Unit tests cover TV detection rules, the stored setting, key mapping, line targets and the
      page script's origin check.
- [ ] Android TV emulator: the app shows on the TV home screen with its banner, TV home opens,
      search shows results, a result plays with the panel beside it, remote keys work as above,
      the line dialog and word card work with the D-pad.
- [ ] Turning "TV layout (beta)" off on the TV gives the normal app back.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` incl. `TvLayoutTest` | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug` | Cloud container, PR CI |
| Managed-device/emulator | PR CI phone emulator suites unchanged | PR CI |
| Android TV emulator | Install the debug APK, walk the scenario above with D-pad keys | Owner's laptop (Remote Control) |
| Physical TV | Reddit tester with the preview APK | Optional, after merge decision |
| Live YouTube | Search and playback on the TV emulator | Owner's laptop |

## Risks / edge cases

- Some TV boxes report television mode wrongly: the switch lets the user turn the layout off.
- YouTube can change its search reply; the grid then shows "No videos found" and the YouTube page
  button still works.
- Old TV WebViews may not play m.youtube.com well; nothing the app can fix.

## Release intent

`release:minor` recommended (a new device type), but the standing default for APK changes is
patch; the owner picks before merge.

## Implementation result

- Detection and setting: `ui/TvLayout.kt` (`isTelevisionDevice`, `readTvLayout`, `LocalTvLayout`,
  More settings → Android TV (beta)); assistant setting `tv_layout`; reset all settings covers it.
- Launcher: `LEANBACK_LAUNCHER`, `drawable-xhdpi/tv_banner.png`, touchscreen and leanback not required.
- Remote: `MainActivity.dispatchKeyEvent` → `TvRemoteKeys` (`ui/TvRemote.kt`), actions run in
  `ui/TvExperience.kt`. ▼ to the panel pauses the video and focus follows the playing line.
- TV home: `ui/TvHome.kt` over the one WebView (never replacing it), search through
  `data/YouTubeVideoSearch.kt`; its state survives watching a video.
- Line words: `ui/TvLineWords.kt`. Page focus, unmute and mobile user agent: `ui/TvPageScripts.kt`
  (`mediaPlaybackRequiresUserGesture` is off only in the TV layout).
- Remote fixes from the emulator walkthrough: the side menu button is hidden in the TV layout (the
  remote could open the drawer but not leave it), sliders and text fields let ▲/▼ leave
  (`tvArrowsLeave`), language pickers start on the list, stronger focus shading (`tvFocusShading`),
  and the guide's picture shrinks to fit a TV screen. Setup screens start with their main button
  focused and ringed; text size moves 5% a press on a TV; Continue watching lists only videos and
  "YouTube page" reuses a tab already on YouTube's pages.
- Known gap: with the side menu hidden, Check for updates and the interface-language button are not
  reachable in the TV layout; turning the TV layout off brings them back.

## Validation result

- Cloud container: `formatReport` + format ratchet, `complexityCheck`, `testDebugUnitTest`
  (incl. `TvLayoutTest`), `lintDebug` pass. PR CI green.
- Android TV emulator (owner's laptop, android-34 TV image, remote keys only, 2026-10-30): banner in
  the Apps row, TV home search and results kept after a video, ▼ pauses and focuses the playing line,
  OK opens the line words, word card, ◀/▶/▲ line jumps, media keys, sound on start, sliders and
  language pickers leave with ▲/▼, setting toggle. No crashes or ANRs (`logcat -b crash` empty).
- Not fixed: arrow focus on YouTube's own pages follows a nearest-element guess and can jump (for
  example ▶ from a list item to the filter chips); the video shows no focus marker while it holds
  the remote (it is the normal watching state). No test on a real TV yet.
- Screenshots: `docs/images/android-tv/` (debug build, so the launcher label says "Preview").

## Remote-first usability fixes (2026-11-13, branch `fix_android_tv`)

Owner report: the menu is gone in the TV layout, the remote gets stuck in Settings, and mouse clicks
sometimes need several tries.

### Root causes found

- Menu: `AppNavigation` hides the hamburger in the TV layout and TV home listed only Practice,
  Progress and Settings, so Check for updates, app language, Report a problem, licences and links
  had no remote path.
- Stuck remote: Material sliders turn ▲/▼ into value changes. Only three sliders called
  `tvArrowsLeave()`; the per-subtitle size slider (`SubtitleSlotLayout`) and the default text-size
  slider (`TabSettingsEditor`) did not. `tvArrowsLeave()` also consumed ▲/▼ even when focus could
  not move.
- Focus marker: expandable section headers, switches and the update row showed no ring.
- Mouse multi-click: NOT reproduced or fixed (no device here). No code in the TV path consumes
  pointer events or steals focus on press; the likely cause is Android's non-touch-mode rule that a
  first click only focuses a view, which needs a TV check before any change.

### Changes

- `tvArrowsLeave()` consumes ▲/▼ only when focus moved (`tvArrowDirection` is the tested key map).
- `tvArrowsLeave()` added to the two missing sliders.
- `tvFocusRing` on section headers, switches and Check for updates.
- TV home gets a Menu button (`tv_menu`) opening a dialog with app language, Check for updates,
  Report a problem, licences and links (the drawer footer), closed by Back.

### Manual TV checks (not provable offline)

- Menu opens from TV home, every item opens, Back returns to TV home with focus kept.
- In Settings, ▲/▼ pass every slider (including per-subtitle size and Tab settings), switches and
  section headers, and the focused item scrolls into view.
- Text fields: ▲/▼ leave a field (the on-screen keyboard, when open, keeps its own keys; Back closes it). See the 2026-11-13 corrections.
- Mouse: one click activates a control once, on TV home, Settings and the menu dialog.

### Follow-up evidence (2026-11-13, Compose 1.12.1 sources read; no device)

- Mouse multi-click: still NOT reproduced, so no code change. Read in the Compose sources:
  `clickable` never requests focus on press, so a click on a Compose control cannot be "focus only";
  `AndroidComposeView` is `isFocusableInTouchMode`; the mouse tap-to-clear-focus runs after the event
  and only for `AutoClearFocusBehavior.CursorBased` (the app does not set it). The app has no
  pointer handlers of its own on the TV path. The remaining suspects are the YouTube WebView (a
  touch page; the first click may only focus the view) and the TV's own pointer driver. Next step is
  a TV check with the mouse on TV home, the menu dialog, Settings and the WebView separately.
- Multi-line fields (CORRECTED below: single-line fields trap too): every multi-line field was a trap. `tvArrowsLeave()` now covers the AI instructions and memory fields,
  the assistant's message box, the saved-word notes and the Dictation answer box. (The claim that single-line
  fields let ▲/▼ through was wrong; see below.)
- Scrolling: `FocusableNode` calls `bringIntoView()` when it gains focus, so the Settings column
  scrolls to the focused control (manual check kept).
- Focus restoration: nothing in the app or Compose clears focus when a dialog window opens or
  closes, so the button that opened the TV menu keeps focus after it closes. The TV menu dialog now
  starts on its first item (`tvStartFocus`).
- Guard: `TvFocusGuardTest` fails when a slider (other than SettingsPeek's display-only copy) or a
  multi-line `OutlinedTextField` under `ui/` lacks `tvArrowsLeave()`. It first found the notes and
  Dictation fields.

## Owner TV test, round 2 (2026-11-13, branch `fix_android_tv`)

The owner tried the build on a real Android TV and confirmed three failures.

### Correction to the 2026-11-13 follow-up

I read `TextFieldKeyEventHandler` wrongly. Its `singleLine` checks are for Enter and Tab; `KeyCommand.UP`
and `DOWN` (`moveCursorUpByLine` / `DownByLine`) are consumed in every text field, single-line too
(`KeyMapping`: Up/Down map to them with no modifier). So no text field let ▲/▼ through. That is the
Search settings trap.

### Root causes

1. Search settings: the field consumes ▲/▼ for its cursor and had no `tvArrowsLeave()`. Same for every other
   text field in the app (about 25), which are all fixed now. A visible on-screen keyboard gets the keys
   before the app and keeps its own navigation; Back closes it, after which ▲/▼ reach the app again.
2. Hamburger: `AppNavigation` hid it in the TV layout, and `TvLayer` was a sibling over the whole Scaffold, so TV home
   also covered the top bar and would have covered the drawer's trigger. The drawer also stayed composed and
   focusable while closed, which was the first drawer focus trap.
3. Font Size and Portrait Panel Position sliders: both already had `tvArrowsLeave()`, and `moveFocus(Up/Down)`
   was the only move tried. NOT reproduced offline why the spatial search finds no candidate from these two
   sliders (the Compose 2D search and the sliders' modifier chains read correctly). The fix does not depend on
   it: if the spatial move fails, the focus order's Previous/Next is tried (`tvLeaveAttempts`). MANUAL TEST
   REQUIRED to confirm both sliders now leave.

### Changes

- `tvArrowsLeave(sequentialFallback = true)`: tries Up then Previous (Down then Next); KeyDown takes the press
  only if focus moved, and the matching KeyUp is taken only then. When nothing moves (top or bottom of the page)
  the press is not taken, so the field or slider keeps its own arrow: a documented boundary, not a swallow.
  Previous/Next wrap at the page ends, so the Search field (the first control) passes `false`.
- `tvArrowsLeave()` on every `OutlinedTextField` / `TextField` under `ui/` (`TvFocusGuardTest` now requires it).
- Hamburger shown in every layout (`AppNavigation`); `TvLayer` moved into the Scaffold body so the top bar and its
  hamburger stay above TV home; ▲ may leave TV home for the top bar, any other exit stays cancelled
  (`TvLayer`); the page and panels under TV home are not focus targets (`pageFocusable`).
- Drawer: focus enters it when it opens and returns to the hamburger when it closes; while it is closed the
  remote cannot reach it, and while it is open the remote cannot reach the screen behind the scrim (TV layout only).
  Back closes the drawer: TV home's Back handler is off while the drawer is open (`backEnabled`).
- The earlier TV home Menu button and dialog are gone: the drawer is the one menu (tabs, Practice, Progress,
  Settings, app language, Check for updates, Report a problem, licences, links).

### Tests

- JVM: `TvLayoutTest` (`tvLeaveAttempts`, `tvArrowDirection`), `TvFocusGuardTest` (modifier present on every slider
  and text field). They cannot prove runtime focus movement.
- Device (written, compiled, NOT run): `TvRemoteNavigationUiTest` presses ▼/▲ on a slider and a text field and checks
  the neighbours get focus, ◀/▶ still change the slider, and the drawer opens and Back returns focus to the hamburger.

### Acceptance (all MANUAL TEST REQUIRED on a TV)

1. Search settings ▼ reaches the next control; ▲ at the top does nothing and the field keeps focus.
2. Hamburger visible top-left in the TV layout, including over TV home; OK and one mouse click open the drawer.
3. Drawer: ▲/▼ walk every item and the footer; Back closes it and focus is back on the hamburger.
4. Font Size and Portrait Panel Position: ▲/▼ leave, ◀/▶ change the value.
5. The focused control is scrolled into view; focus after a dialog or Settings closes is usable.
6. Phones and tablets look and behave as before (the `canFocus` guards apply only in the TV layout).

## Owner TV test, round 3: Search settings (2026-11-13)

All earlier fixes were accepted on the TV except Search settings.

### Why the earlier fix failed

`tvArrowsLeave()` only changes what ▲/▼ do once the field already has focus. The field was the problem:
a text field that takes focus opens the TV keyboard by itself, the keyboard resizes the window and the
page re-lays out (`searchFocused` also inserted the Often changed list), and the remote then bounced between
Search and the Per-tab Settings accordion above it. No key handler can fix a control that is edited the
moment it is focused.

### Changes (TV layout only; phones and tablets keep the text field)

- On a TV, Search settings is a button that looks like the field (`TvSettingsSearchButton`, tag
  `settings_search`). Passing over it opens nothing. OK or a click opens the same search dialog TV home uses
  (`TvSearchDialog`, now with a title, label and `allowBlank`); the keyboard opens only there. Back closes the
  keyboard first, then the dialog; the button, not a text field, then holds focus, so nothing reopens it.
  Submitting an empty query clears the search. Results show under the accordion as before and are plain rows.
- Order on every device (`SubtitleSettingsDialog`): Search settings, Per-tab Settings accordion, then the
  groups. The Default-tab editor still takes the page below the accordion (`return@Column`); Search stays above it
  and the groups wait. Its results only show with the groups.
- Settings → Android TV (beta) → Show Search Settings on TV (`tv_settings_search_visible`, on by default, only
  read in the TV layout). Off: no search control exists (no hidden focus target) and the query is dropped.
  It is a backup setting, part of `TV_LAYOUT_SETTING_KEYS` (so Reset all settings restores on) and in Search
  settings (`SearchableSetting.TV_SEARCH_VISIBLE`); text in all 9 languages.
- Not done on purpose: the Often changed list no longer appears on a TV (it came with the field's focus), and
  nothing moves focus when the switch is turned off while Search holds focus: that can only happen from the
  assistant, because the switch sits lower on the page. Compose clears focus when the control leaves.

### Tests

- JVM: `TvLayoutTest` (`settingsSearchShown`, the key, reset and Search registration).
- Device (written and compiled, NOT run): `TvRemoteNavigationUiTest` checks that focusing Search opens no text
  field, ▼ passes over it, OK opens the dialog, a submitted query is kept, and a hidden Search leaves no node and
  no query.

### MANUAL TEST REQUIRED on the TV

1. Settings opens with Search first; ▼ goes to Per-tab Settings, ▲ comes back; no keyboard shows.
2. OK on Search opens the keyboard; typing and Search show results; ▼ walks the results.
3. Back closes the keyboard, Back again the dialog; the keyboard does not return and focus is on Search.
4. ▼ beyond Per-tab Settings walks the other groups with no loop.
5. Switch off Show Search Settings on TV: the page starts at Per-tab Settings; switch on: Search is back first.
6. Restart the app: the switch keeps its state. Reset all settings turns it on.
7. A mouse click on Search opens the dialog once. Hamburger, drawer and sliders still work.
