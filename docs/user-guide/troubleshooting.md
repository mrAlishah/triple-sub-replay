# Troubleshooting

[← User guide](README.md) · [Fork releases](https://github.com/mrAlishah/triple-sub-replay/releases)

## Android refuses to install an update

An in-place update requires a compatible **application ID** and **signing certificate**. Different fork/upstream, debug/release or preview builds may not update one another. **Export your Practice backup before attempting any uninstall.** Download the fork APK from [TripleSub Replay Releases](https://github.com/mrAlishah/triple-sub-replay/releases), not the original project's releases.

## Subtitles are missing or late

Try a video with known YouTube captions, confirm caption availability, ensure the slot is enabled and the original language is correctly selected. Caption retrieval relies on YouTube behavior and may fail for particular videos.

## AI translation does not work

Check whether the Translation AI Service has been configured and authenticated, your account or API key is eligible, and the current mode requests AI translation. **On this device only** needs local models rather than a cloud connection. A cached label does not necessarily indicate a fresh request.

## Subtitle text looks misplaced

Check the order, font size, alignment and RTL options in **Subtitle languages** and **Reading** settings. Right-to-left scripts can need their own alignment/direction settings.

## A tap does not replay a subtitle

In newer development builds, replay belongs exclusively to the segment's **Play** button. Tapping the card's background is intentionally not a seek action. Older releases may behave differently.

## Anki import/send does not work

Check AnkiDroid installation, note-type compatibility and permission. Try a test word and verify its destination before performing a large import. See [Anki and backups](anki-backup.md).

## Report a problem

Use [TripleSub Replay Issues](https://github.com/mrAlishah/triple-sub-replay/issues) and include:

- The exact installed app version or release tag.
- Android version and a concise reproduction sequence.
- Whether you are on a published APK or a development build.
- A screenshot if useful, **without API keys, access tokens, account details, private chats or personal learning data**.

[← Back to User Guide](README.md)
