# Translation modes and AI Connection

[← User guide](README.md) · [Triple subtitles](triple-subtitles.md)

Open **Settings → Captions & translation** (wording may vary by release), then find **Translate subtitles with**.

## Translation modes

| Mode | What you see | Requirements |
| --- | --- | --- |
| **Google Translate** | Fast online translations; the default | Network connection |
| **AI translation (best quality)** | Google text first, then improved AI results when available | Configured eligible AI service |
| **AI only** | Waits for AI results, with the documented fallback behavior | Configured eligible AI service |
| **On this device only** | Local language-model translation; no subtitle text sent to a translation service | Required on-device language model |

The four modes are documented in the **[v2.8.5 release notes](../releases/v2.8.5.md)**. Options and provider support in later development builds may differ.

## Configure AI

1. Open the app's AI settings / **AI Connection**, where available.
2. Choose the provider and connect it using the authentication method the app actually offers.
3. Select a model if the provider supports it.
4. In **Captions & translation**, select the **Translation AI Service** (where that assignment exists).
5. Choose **AI translation (best quality)** or **AI only** if you want automatic AI translation.

A provider may require an API key, account authorization, plan eligibility or usage allowance. **ChatGPT Subscription** through a connected account is a distinct mechanism from a paid OpenAI API key and is a newer fork customization; do not assume every released APK supports it.

## Chat and translation are independent

Where the customized app provides **Chat AI Service** and **Translation AI Service** selectors, choosing one does not automatically change the other. The translation menu must use the translation assignment, not the chat assignment.

## What 'cached' means

The app may reuse a previous translation to reduce wait and service usage. Provider/source labels can identify cached results. The explicit **Re-translate with AI** action in newer development builds requests a fresh translation for the chosen scope or segment and should preserve the previous valid text if a request fails.

## Privacy and troubleshooting

Network translation sends subtitle text to the selected service; local translation does not send subtitle text to an online translation provider. Video playback still connects to YouTube. Avoid putting secrets in screenshots or issue reports.

For errors, verify the service, language pair, account state and network connection. See [Troubleshooting](troubleshooting.md).

[Next: AI Assistant →](ai-assistant.md)
