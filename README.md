# Murmurnote

[简体中文](README_zh_CN.md)

Murmurnote is an Android voice note app for recording, transcribing, summarizing, and turning spoken thoughts into searchable notes, todos, and ideas.

## What's new in v1.0.2

- Complete Chinese and English UI localization, including services, notifications, widgets, onboarding, diagnostics, and error messages.
- An in-app language picker under Settings with System, Chinese, and English options. Compose applies the new locale without recreating the Activity, so switching languages no longer flashes the whole screen.
- A custom correction dictionary with two modes: context-aware decisions constrained to a user-defined replacement, or deterministic always-replace rules for unambiguous names and terms.
- Optional personalized correction learning driven only by edits the user explicitly saves. Learned rules remain reviewable, can be disabled or deleted, and never overwrite the original model transcript or historical revisions.
- More reliable long recordings through streaming Silero VAD, bounded adaptive hard-cut refinement, background-safe recording, and frozen ASR configuration for each processing session.
- Stronger safety boundaries for model packages, audio imports, network payloads, diagnostic logs, backups, database migrations, and release signing.

## Features

- Recording and import: record inside the app or import audio through Android share/open intents.
- Speech recognition: supports Zhipu GLM-ASR-2512 cloud transcription and local sherpa-onnx engines.
- Local ASR: supports SenseVoiceSmall int8 and Qwen3-ASR 0.6B int8. Models are downloaded on demand and are not bundled in the APK.
- Transcript correction: manage custom context-aware or always-replace rules, with optional guarded learning from explicit transcript edits.
- AI extraction: turns transcripts into summaries, todos, ideas, notes, and decisions.
- LLM providers: supports DeepSeek, OpenAI, Anthropic, Gemini, and Ollama-compatible APIs.
- Note management: list, detail, playback, search, todo, and idea views.
- Languages: switch between Chinese, English, or the system language from Settings.
- Quick access: includes a home-screen widget for fast recording.
- Diagnostics: includes privacy-aware log capture, export, and API debugging helpers.

## Download

Download signed APKs from GitHub Releases:

<https://github.com/wzwys9/Murmurnote/releases>

For most Android phones, use:

```text
Murmurnote-v1.0.2-arm64-v8a.apk
```

Other ABI builds are mainly for older devices or emulators:

```text
Murmurnote-v1.0.2-armeabi-v7a.apk
Murmurnote-v1.0.2-x86.apk
Murmurnote-v1.0.2-x86_64.apk
```

## Configuration

API keys are not stored in source code, `local.properties`, or Gradle scripts. Configure them inside the app after installation:

- Zhipu GLM API Key: used for cloud ASR.
- LLM API Key: used for summaries and structured extraction.
- LLM Provider: DeepSeek, OpenAI, Anthropic, Gemini, or Ollama.

Local ASR models are not bundled in the APK. When local ASR is enabled for the first time, the app guides the user through downloading a model and verifies its SHA256 checksum.

The custom correction dictionary and personalized learning are disabled by default. Context-aware correction uses the configured LLM only to choose between a known replacement and keeping the original text. Personalized learning sends bounded nearby text only after explicit disclosure; it does not send audio, titles, summaries, or a complete recording.
