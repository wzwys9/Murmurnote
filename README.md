# Murmurnote

[简体中文](README_zh_CN.md)

Murmurnote is an Android voice note app for recording, transcribing, summarizing, and turning spoken thoughts into searchable notes, todos, and ideas.

## Features

- Recording and import: record inside the app or import audio through Android share/open intents.
- Speech recognition: supports Zhipu GLM-ASR-2512 cloud transcription and local sherpa-onnx engines.
- Local ASR: supports SenseVoiceSmall int8 and Qwen3-ASR 0.6B int8. Models are downloaded on demand and are not bundled in the APK.
- AI extraction: turns transcripts into summaries, todos, ideas, notes, and decisions.
- LLM providers: supports DeepSeek, OpenAI, Anthropic, Gemini, and Ollama-compatible APIs.
- Note management: list, detail, playback, search, todo, and idea views.
- Quick access: includes a home-screen widget for fast recording.
- Diagnostics: includes log capture, export, and API debugging helpers.

## Download

Download signed APKs from GitHub Releases:

<https://github.com/wzwys9/Murmurnote/releases>

For most Android phones, use:

```text
Murmurnote-v1.0.0-arm64-v8a.apk
```

Other ABI builds are mainly for older devices or emulators:

```text
Murmurnote-v1.0.0-armeabi-v7a.apk
Murmurnote-v1.0.0-x86.apk
Murmurnote-v1.0.0-x86_64.apk
```

## Configuration

API keys are not stored in source code, `local.properties`, or Gradle scripts. Configure them inside the app after installation:

- Zhipu GLM API Key: used for cloud ASR.
- LLM API Key: used for summaries and structured extraction.
- LLM Provider: DeepSeek, OpenAI, Anthropic, Gemini, or Ollama.

Local ASR models are not bundled in the APK. When local ASR is enabled for the first time, the app guides the user through downloading a model and verifies its SHA256 checksum.
