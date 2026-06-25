# Murmurnote / 声记

Murmurnote is an Android voice note app for recording, transcribing, summarizing, and turning spoken thoughts into searchable notes, todos, and ideas.

声记是一款 Android 语音备忘录应用，用于录音、转写、总结，并把随口说出的内容整理成可搜索的备忘、待办和想法。

[中文](#中文) | [English](#english)

## 中文

### 功能

- 录音与导入：支持应用内录音，也支持通过系统分享/打开音频文件导入。
- 语音转文字：支持智谱 GLM-ASR-2512 云端转写，也支持本地 sherpa-onnx 模型。
- 本地 ASR：支持 SenseVoiceSmall int8 和 Qwen3-ASR 0.6B int8，模型按需下载，不内置在 APK 里。
- AI 整理：使用 LLM 从转写文本中提取摘要、待办、想法、备忘和决策。
- 多模型 LLM：支持 DeepSeek、OpenAI、Anthropic、Gemini 和 Ollama API。
- 内容管理：录音列表、详情页、播放、搜索、待办视图和想法视图。
- 快捷入口：提供桌面小组件用于快速录音。
- 调试与日志：内置日志采集、导出和 API 调试辅助能力。

### 下载

最新 APK 可以从 GitHub Releases 下载：

<https://github.com/wzwys9/Murmurnote/releases>

普通 Android 手机一般选择：

```text
Murmurnote-v1.0.0-arm64-v8a.apk
```

其他 ABI 包主要用于旧设备或模拟器：

```text
Murmurnote-v1.0.0-armeabi-v7a.apk
Murmurnote-v1.0.0-x86.apk
Murmurnote-v1.0.0-x86_64.apk
```

### 配置

API Key 不写在源码、`local.properties` 或构建脚本里。安装后在应用的设置页填写：

- 智谱 GLM API Key：用于 GLM-ASR 云端语音转文字。
- LLM API Key：用于摘要、待办和结构化信息提取。
- LLM Provider：可选择 DeepSeek、OpenAI、Anthropic、Gemini 或 Ollama。

本地 ASR 模型不随 APK 内置。首次启用本地识别时，应用会在设置页引导下载模型，并校验 SHA256。

## English

### Features

- Recording and import: record inside the app or import audio through Android share/open intents.
- Speech recognition: supports Zhipu GLM-ASR-2512 cloud transcription and local sherpa-onnx engines.
- Local ASR: supports SenseVoiceSmall int8 and Qwen3-ASR 0.6B int8. Models are downloaded on demand and are not bundled in the APK.
- AI extraction: turns transcripts into summaries, todos, ideas, notes, and decisions.
- LLM providers: supports DeepSeek, OpenAI, Anthropic, Gemini, and Ollama-compatible APIs.
- Note management: list, detail, playback, search, todo, and idea views.
- Quick access: includes a home-screen widget for fast recording.
- Diagnostics: includes log capture, export, and API debugging helpers.

### Download

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

### Configuration

API keys are not stored in source code, `local.properties`, or Gradle scripts. Configure them inside the app after installation:

- Zhipu GLM API Key: used for cloud ASR.
- LLM API Key: used for summaries and structured extraction.
- LLM Provider: DeepSeek, OpenAI, Anthropic, Gemini, or Ollama.

Local ASR models are not bundled in the APK. When local ASR is enabled for the first time, the app guides the user through downloading a model and verifies its SHA256 checksum.
