![Murmurnote 将语音整理成结构化笔记](docs/assets/murmurnote-banner.png)

# Murmurnote / 声记

[English](README.md)

声记是一款 Android 语音备忘录应用，用于录音、转写、总结，并把随口说出的内容整理成可搜索的备忘、待办和想法。

## v1.0.2 最新增强

- 完成中英文双语界面，覆盖主界面、设置、引导页、通知、服务、桌面小组件、诊断信息和错误提示。
- 设置中新增“界面语言”，可选择跟随系统、中文或英文。语言变化由 Compose 直接更新，不再重建整个 Activity，因此切换时不会整屏闪烁。
- 新增“自定义纠错词典”，每个词条可选择“结合上下文”或“始终替换”：前者只允许 LLM 在用户指定的替换与保留原文之间判断，后者适合无歧义的专名和固定术语。
- 新增可选的个性化自学习纠错，只从用户明确保存的转写修改中学习。已学规则可以查看、停用和删除，并且不会覆盖模型原文或历史修订。
- 通过流式 Silero VAD、自适应强制切段边界、后台录音保护和单次任务 ASR 配置冻结，提高长录音处理的稳定性。
- 加固模型包、音频导入、网络响应、诊断日志、备份、数据库迁移和 Release 签名等安全边界。

## 功能

- 录音与导入：支持应用内录音，也支持通过系统分享/打开音频文件导入。
- 语音转文字：支持智谱 GLM-ASR-2512 云端转写，也支持本地 sherpa-onnx 模型。
- 本地 ASR：支持 SenseVoiceSmall int8 和 Qwen3-ASR 0.6B int8，模型按需下载，不内置在 APK 里。
- 转写纠错：支持结合上下文或始终替换的自定义词条，也可选择从用户明确修改中进行受约束的个性化学习。
- AI 整理：使用 LLM 从转写文本中提取摘要、待办、想法、备忘和决策。
- 多模型 LLM：支持 DeepSeek、OpenAI、Anthropic、Gemini 和 Ollama API。
- 内容管理：录音列表、详情页、播放、搜索、待办视图和想法视图。
- 界面语言：可在设置中选择中文、英文或跟随系统。
- 快捷入口：提供桌面小组件用于快速录音。
- 调试与日志：内置注重隐私的日志采集、导出和 API 调试辅助能力。

## 下载

最新 APK 可以从 GitHub Releases 下载：

<https://github.com/wzwys9/Murmurnote/releases>

普通 Android 手机一般选择：

```text
Murmurnote-v1.0.2-arm64-v8a.apk
```

其他 ABI 包主要用于旧设备或模拟器：

```text
Murmurnote-v1.0.2-armeabi-v7a.apk
Murmurnote-v1.0.2-x86.apk
Murmurnote-v1.0.2-x86_64.apk
```

## 配置

API Key 不写在源码、`local.properties` 或构建脚本里。安装后在应用的设置页填写：

- 智谱 GLM API Key：用于 GLM-ASR 云端语音转文字。
- LLM API Key：用于摘要、待办和结构化信息提取。
- LLM Provider：可选择 DeepSeek、OpenAI、Anthropic、Gemini 或 Ollama。

本地 ASR 模型不随 APK 内置。首次启用本地识别时，应用会在设置页引导下载模型，并校验 SHA256。

自定义纠错词典和个性化学习默认关闭。“结合上下文”的纠错只允许当前 LLM 在已知替换与保留原文之间选择；个性化学习会在明确告知后仅发送有限的邻近文本，不会发送音频、标题、总结或整段录音。
