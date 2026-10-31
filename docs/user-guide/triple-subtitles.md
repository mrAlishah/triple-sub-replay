# Triple subtitles and playback

[← User guide](README.md) · [Getting started](getting-started.md)

TripleSub Replay supports **Original Subtitle**, **Subtitle 1** and **Subtitle 2**, which can display different languages together—for example, German / English / Persian.

## Select your three languages

1. Open a captioned video.
2. Tap the subtitle-language control (CC).
3. Open **Original Subtitle** and choose the available source caption language.
4. Choose the translation target for **Subtitle 1** (for example, English).
5. Enable **Subtitle 2** and choose its language (for example, Persian).
6. Close the dialog and wait for lines to become available.

Subtitle 2 is independently translated from the intended source captions. It is not supposed to translate a previously translated Subtitle 1 line.

## Adjust appearance

In **Languages** settings, expand a slot and change its language, position/order, size, alignment, visibility or right-to-left (RTL) behavior where supported. Use **Reading**, **Layout & format**, and **Colors & theme** to adjust the player and subtitle appearance.

For Persian and other RTL languages, check both alignment and RTL direction.

## Replay a sentence

Use the **Play** button beside the segment you want to replay. The app seeks to that segment's start and plays the video.

**Development-build note:** the customized card interaction assigns seek-and-play to the Play button, not to tapping the card body, translation text or empty space. Check your installed APK's version before expecting this newer interaction.

## Per-segment Re-translate with AI (development)

In newer customized builds a **small downward triangle above each Play button** opens a dedicated popup with **Re-translate with AI**.

- The action is for the selected segment only.
- It targets enabled translation slots, not the Original Subtitle.
- It requests a fresh AI translation even when a previous translation was cached, subject to available service configuration.
- It is a **different menu** from the word-selection popup containing **Copy / Translate / Pronounce / Open in Google Translate**.
- Opening either menu is not a request to replay the video.

This is documented as **development behavior**, not as a confirmed v2.8.5 feature.

## When a line is missing

Check whether the video offers a valid caption track, the slot is enabled and a language is selected. Online translation needs connectivity; optional AI services need valid configuration. See [Troubleshooting](troubleshooting.md).

[Next: Translation and AI →](ai-translation.md)
