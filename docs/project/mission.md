# Project mission

DualSub Replay helps Android users learn languages through YouTube: read original
and translated subtitles together, tap a sentence to replay it, and save words and
context for later practice. See the [product guide](../../README.md) for features.

## Product principles

- Keep YouTube playback, captions, and replay reliable. The learning controls build
  on the existing YouTube page; this is a language-learning app, not a project to
  replace YouTube's general browsing/player experience.
- Keep study data local and on-device translation always available with downloadable
  ML Kit models. No project-operated backend, application account, or user-provided API
  key is required. Avoid adding unnecessary service dependencies. The owner approved one
  exception on 2026-10-05: the app translates with Google Translate (online) by
  default for better quality, with a setting to translate on the device instead, and it
  offers on-device again when Google fails.
  On 2026-10-08 the owner approved a second, optional exception: the AI
  assistant uses a key the user brings from the AI service of their choice. It is on by
  default but sends nothing until a key is added, can be turned off completely, and every
  other feature keeps working without it. The project still runs no server and holds no
  shared key.
  On 2026-10-29 the owner approved a third, optional exception ([spec](../specs/2026-10-29-chatgpt-subscription-translation.md)):
  Settings → Translation can translate subtitles with the user's own ChatGPT plan through OpenAI's official
  Sign in with ChatGPT flow. It is off by default, starts only when the user taps Continue with ChatGPT,
  sends only subtitle text to OpenAI, never reads ChatGPT conversations, and plan allowance and eligibility
  vary by account. The project still runs no server and holds no client secret or shared key.
- Be precise about privacy: YouTube browsing/captions and model downloads use the
  network; speech engines may use online voices; AI assistant questions go to the
  service whose key the user added. On-device translation does not
  make the entire app offline or prevent Android backup of local study data.
- Preserve existing users' vocabulary, review progress, settings, and migrations.
  Keep production signing continuity so official releases can update installed apps.
- Prioritize readable subtitles, accessible controls, useful error recovery, and
  mobile usability in portrait, landscape, and fullscreen.
- Prefer focused improvements over architecture complexity. Preserve the
  [technical invariants](tech-stack.md#architecture-invariants) when extending learning features.

These principles summarize the current README, [roadmap](../../ROADMAP.md), and
implementation; they do not promise new features. ROADMAP.md remains the sole
directional roadmap. Record a specific change in a [feature spec](../specs/README.md)
and update this document only when an authorized decision changes lasting intent.
