# NullX11

[![Build NullX11](https://github.com/Inkflow-Gab/nullx11/actions/workflows/build.yml/badge.svg)](https://github.com/Inkflow-Gab/nullx11/actions/workflows/build.yml)

**Floating AI translator for chat apps.** A draggable bubble opens a 3-tab panel **over any app** — QQ, WeChat, Discord, browsers, games. Free · no ads · no premium · no tracking.

## What it does

| Tab | What you get |
|---|---|
| **Translate** | **Screen mode:** captures the screen and places translation cards *exactly where each text block is*. Tap a card to remove it and restore the original. **Text mode:** paste/type a message and translate it, with a Copy button. |
| **Chat** | A real multi-turn AI chat with history, context, slang/gaming translation help. |
| **Settings** | Provider, **model of your choice**, API key, base URL and target language. All stored only on-device. |

## Setup (60 seconds)

1. Install the APK from [Releases](https://github.com/Inkflow-Gab/nullx11/releases).
2. Open NullX11 → **Open floating window** → allow “Display over other apps”.
3. **Allow screen capture** once (system dialog) — needed for Screen translate.
4. Tap the bubble → **Settings tab** → pick a provider, paste your API key, pick your model, SAVE.

## Free providers that work

| Provider | Where to get a free key | Example models |
|---|---|---|
| **Gemini (AI Studio)** | [aistudio.google.com](https://aistudio.google.com) → Get API key | `gemini-2.5-flash`, `gemini-2.5-flash-lite` |
| **OpenRouter** (`:free` models) | [openrouter.ai](https://openrouter.ai) → Keys | `google/gemini-2.0-flash-exp:free` |
| **Groq** (free tier) | [console.groq.com](https://console.groq.com) → API Keys | `meta-llama/llama-3.3-70b-versatile` |
| **Custom** | Any OpenAI-compatible endpoint | base URL + key + model |

Screen translation (text-in-image box detection) works **best with Gemini** — it was designed around Gemini's bounding-box convention `[ymin, xmin, ymax, xmax]` normalized 0–1000. Other vision-capable models may return boxes; card placement precision depends entirely on the model you pick.

## Privacy

- API keys are saved only in the app's private storage on your device.
- Screenshots and texts are sent **directly** to the provider YOU configured, over HTTPS. Nothing else sees them.
- Checks signature `AGPLv3-style free` — see badge: **completely free, no ads, no premium tier**.

## How screen mode works (tech)

- `MediaProjection` screen capture (user-consented, no root, no Shizuku) → JPEG (max 1280px) → vision model asked to return JSON `{items:[{box,orig,lang,tr}]}` → floating `TYPE_APPLICATION_OVERLAY` cards placed over `box` positions → tap card = restore original text, long-press = show original, **✕ ALL** clears everything.

## Building

Push to `main` (or a `v*` tag) — GitHub Actions builds a signed release APK automatically (keystore comes from repo secrets, never committed). Local build:

```bash
./gradlew assembleRelease
```

---
NullX11 — made for people who chat across languages.
