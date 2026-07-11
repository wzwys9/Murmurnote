# Murmurnote 本地 ASR 与确定性纠错执行清单

> 状态：仅分析和规划；本轮没有修改任何现有业务代码，也没有新增实现代码
> 分析日期：2026-07-10
> 分析目标与计划落点：`/home/wzwys/work/Murmurnote`，提交 `f16f2c4`，分析开始时工作树干净
> 底层验证参照：`/home/wzwys/work/Murmurnote_test`，提交 `75094ee`，并包含分析时已有的未提交改动
> 本文中的“拟新增”类和文件均不存在于生产项目，不能视为已实现。

## 0. 结论与前置说明

相邻的 `/home/wzwys/work/Murmurnote_test` 不是生产版 Murmurnote，而是 README 和规格明确声明的独立调试应用 **SenseVoice Lab**：

- application ID 为 `app.murmurnote.sensevoicelab`；
- 目标是测试 SenseVoiceSmall INT8 的原始准确率；
- 没有 Room、会议实体、云端网络客户端、LLM 调用或 `MeetingSummaryService`；
- `/home/wzwys/work/Murmurnote_test/docs/spec.md` 明确规定它不依赖也不修改真实 Murmurnote。

用户随后确认生产项目位于 `/home/wzwys/work/Murmurnote`，本轮已对该目录做只读扫描，没有修改它。生产项目实际包含 Room v5、SenseVoiceSmall、Qwen3-ASR 0.6B、`AudioPipeline`、`LlmClient` 和会议摘要/待办提取链路；但确实不存在名为 `MeetingSummaryService` 的类。本文同时记录 Lab 的已验证底层能力和生产项目的真实集成点，仍不得把建议类当成现有类。

### 已确认的产品决策

| 项目 | 决策 |
|---|---|
| 录音源 | 使用 Android `MediaRecorder.AudioSource.MIC` |
| 默认语言 | SenseVoice：中文系统界面使用 `zh`，英文系统界面使用 `en`，默认不使用 `auto`；Qwen 没有同名配置字段，保留模型 auto-language |
| ITN | SenseVoice 开启并保留自带 ITN/标点；Qwen 保留模型原生文字规范化与标点，不伪造 ITN 开关 |
| VAD 类型 | 使用 Silero VAD v5 神经网络，不在生产转写链路中使用能量 VAD |
| 语音概率阈值 | `0.5` |
| 最短语音 | `250 ms` |
| 最短静音 | `700 ms` |
| 前置 padding | `500 ms` |
| 单段上限 | `25,000 ms` |
| 硬切重叠 | `500 ms` |
| 默认会议 ASR | 仍以本地 `SenseVoiceSmall INT8 + sherpa-onnx` 为产品基线 |
| 现有 Qwen 兼容 | 主项目已有 Qwen3-ASR 0.6B INT8；共用 VAD、存储和确定性后处理，模型专属能力单独分支 |
| 纠错目标 | 忠实转写，只改高置信度错字；不润色、不改写、不删口头语/重复/自我修正 |
| LLM | 本地和云端 LLM 均不得参与 ASR 或错字纠正 |
| 云端总结 | 保留现有云端会议总结，但必须与本地 ASR/纠错单向解耦 |

### 仍需确认的产品决策

1. **后置 padding 需要确认**：当前 Lab 代码同时存在 `prePaddingMs` 和 `postPaddingMs`，UI 还把二者绑定为同一个值；已确认的需求只规定前置 `500 ms`，没有规定后置 padding。不能自行假定为 `500 ms`。
2. **其他系统语言需要确认**：中文和英文界面的 SenseVoice 默认值已确定；系统主语言既非中文也非英文时，应回退 `en`、`zh` 还是 `auto` 尚未确定。
3. **语言手动切换需要确认**：上述规则是 SenseVoice 的“首次默认值”，还是要完全隐藏语言选择入口，需求尚未明确。
4. **会议词汇生命周期需要确认**：动态词条是只在当前会议有效，还是可以由用户提升为全局词条。
5. **录音保留策略需要确认**：二次识别依赖原始音频；需要确定保留时长、容量上限、删除与用户告知规则。
6. **Qwen 热词上限需要确认**：Qwen3-ASR 有模型专用 hotwords，但会占用 `maxTotalLen` 的 prompt 上下文；最大词条数和总字符数需要用目标手机基准确定，不能照搬固定数值。

现有 `minSegmentMs=200 ms` 是 padding/硬切之后的最终片段过滤阈值，与 Silero 的“最短语音 `250 ms`”不是同一个参数。本需求没有要求改变前者，第一阶段应继续保留 `200 ms`，除非基线测试证明需要调整。

## 1. 当前代码结构和真实调用链

### 1.1 顶层结构

```text
/home/wzwys/work/Murmurnote_test
├── app/src/main/java/app/murmurnote/sensevoicelab
│   ├── MainActivity.kt
│   ├── audio/        录音、导入、PCM/WAV、切段、Silero VAD
│   ├── asr/          SenseVoice 配置、JNI 封装和原始结果
│   ├── experiment/   分段识别、原文拼接、CER 实验记录
│   ├── data/         单向 JSON 导出
│   ├── metric/       CER 与文本归一化
│   └── ui/           Compose UI、状态和流程编排
├── app/src/main/java/com/k2fsa/sherpa/onnx
│   └── vendored sherpa-onnx 1.12.39 Kotlin API
├── app/src/main/assets
│   ├── asr_models/   SenseVoiceSmall INT8、tokens、测试 WAV
│   └── vad_models/   Silero VAD v5
├── app/src/main/jniLibs/arm64-v8a
│   └── libsherpa-onnx-jni.so、libonnxruntime.so
├── app/src/test      JVM 单元测试
└── app/src/androidTest 真机/JNI/模型/录音测试
```

### 1.2 录音调用链

```text
MainActivity.startRecordingWithPermission()
  → LabViewModel.startRecording()
  → AudioCapture.start(CaptureSource)
  → AudioRecord.Builder
       source = CaptureSource.androidValue
       format = 16 kHz / mono / PCM16
  → 后台 captureLoop()
  → Pcm16WavWriter 写入 filesDir/experiments/*.wav
  → LabViewModel.finishRecording()
  → AudioCapture.stop()
  → PcmWavReader.read()
  → LabUiState.audio: PcmAudio
```

现有文件：

| 完整路径 | 当前职责 | 当前事实 |
|---|---|---|
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/MainActivity.kt` | 权限、录音/导入/导出 Activity Result、Compose 宿主 | `RECORD_AUDIO` 获批后调用 ViewModel；`onStop()` 会停止录音 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/AudioCapture.kt` | `AudioRecord` 和 WAV 录制 | 支持 MIC、VOICE_RECOGNITION、UNPROCESSED；16 kHz/mono/PCM16；默认最长 30 分钟 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/Pcm16WavWriter.kt` | 流式写 PCM16 WAV | 关闭时回写 RIFF 长度；异常退出可能留下不完整文件 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/PcmWavReader.kt` | 严格读取规范化 WAV | 仅接受 16 kHz、单声道、PCM16 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/PcmAudio.kt` | 内存 PCM 和信号统计 | 使用不可变引用语义保存 `FloatArray` 与音频元数据 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/ui/LabViewModel.kt` | 当前全部业务流程编排 | `AudioCapture(File(app.filesDir, "experiments"))`；成功录音文件不会由“清空历史”删除 |

当前 `LabUiState.captureSource` 已默认 `MIC`，但 UI 仍允许选择三个来源。生产 Murmurnote 应固定或默认使用 MIC；SenseVoice Lab 是否继续保留三路诊断入口，需要与“实验工具”和“生产配置”边界一起确认。

### 1.3 导入音频调用链

```text
MainActivity.OpenDocument
  → LabViewModel.importAudio(uri)
  → ContentUriAudioReader 查询并复制到 cacheDir/audio-imports
  → AudioImportWorkspace 管理临时文件
  → AudioFileDecoder
      ├── 规范 WAV：PcmWavReader 直接读取
      └── 其他格式：FfmpegAudioConverter → 16 kHz mono PCM16 WAV
  → PcmAudio（只驻留内存）
  → finally 清理复制源和转码临时文件
```

相关文件可以作为后续“导入会议录音后离线转写”的输入层复用，不属于纠错模块。

### 1.4 VAD 与切段调用链

```text
LabScreen 切段配置
  → SegmentSettings.toMode()
  → LabViewModel.runExperiments()
  → ExperimentRunner.run()
  → AndroidAudioSegmenter.plan()
      ├── Whole/Fixed/Energy：SegmentPlanner
      └── SileroVad：
            Vad(assetManager, VadModelConfig)
            → 每 512 samples 输入 Silero v5
            → SpeechSegment ranges
            → NeuralVadSegmentPlanner
            → padding / 合并 / 25 s 硬切 / overlap
  → List<AudioSegment>
```

关键文件和当前默认值：

| 完整路径 | 当前职责 | 与已确认配置的差异 |
|---|---|---|
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/ui/LabUiState.kt` | UI 的 `SegmentSettings` 及模式转换 | 当前默认 `choice=WHOLE`、前后 padding 均 `300 ms`、硬切重叠 `0`；需要改成生产链路默认 Silero、前置 `500 ms`、重叠 `500 ms`；后置 padding 需要确认 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/SegmentPlanner.kt` | 模式模型、Whole/Fixed/Energy 纯 Kotlin 分段 | `SegmentationMode.SileroVad` 已有阈值 `0.5`、最短语音 `250`、静音 `700`、上限 `25 s`、重叠 `500`，但前后 padding 默认仍为 `300 ms` |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/AndroidAudioSegmenter.kt` | 调用 Silero JNI、采集资源指标 | 已将阈值、最短语音、静音、上限传给 `VadModelConfig`；固定 CPU 单线程 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/NeuralVadSegmentPlanner.kt` | 对神经 VAD 输出做确定性 padding、合并、硬切和 overlap | 可直接复用；当前不负责跨段文本去重 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/com/k2fsa/sherpa/onnx/Vad.kt` | vendored sherpa VAD Kotlin binding | 不应为业务配置修改；通过上层构造参数使用 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/assets/vad_models/silero_vad_v5/silero_vad.onnx` | Silero VAD v5 模型 | 已固定大小和 SHA-256，不应替换 |

能量 VAD 目前是实验对照基线，`LabViewModel.runVadComparison()` 还会按“Energy → Silero”执行 A/B。正式 Murmurnote 的生产转写入口不得调用能量 VAD；是否从独立 Lab 中删除该诊断能力不是本需求的一部分，建议保留 Lab 基线但不移植到生产链路。

`AndroidAudioSegmenter` 为了测量冷启动和 PSS，当前每次实验都会新建并释放一次 Silero `Vad`。这适合 Lab，不一定适合生产长会议；移植时应在保持串行和确定性释放的前提下评估会话级复用，不能把实验用冷启动策略直接当作生产性能方案。

### 1.5 SenseVoice 调用链

```text
ExperimentRunner 对每个 AudioSegment 取 FloatArray
  → RecognitionEngine.recognize()
  → SenseVoiceEngine.recognize()
  → OfflineRecognizerConfig
       model = SenseVoiceSmall INT8
       language = RecognitionConfig.language
       useInverseTextNormalization = RecognitionConfig.useItn
       provider = cpu
       decodingMethod = greedy_search
  → OfflineRecognizer.createStream()
  → acceptWaveform()
  → decode()
  → getResult()
  → RawRecognitionResult
       text/tokens/timestamps/lang/emotion/event
```

现有文件：

| 完整路径 | 当前职责 | 规划结论 |
|---|---|---|
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/asr/RecognitionModels.kt` | `SenseVoiceLanguage`、`RecognitionConfig`、`RawRecognitionResult`、`RecognitionEngine` | `RecognitionConfig` 当前固定默认 `ZH + ITN ON`；需要新增基于系统 Locale 的默认值提供者，但 `RawRecognitionResult` 必须继续不可变 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/asr/SenseVoiceEngine.kt` | 单 recognizer、串行 JNI、配置变化时重建 | 可作为拟议 `AsrEngine` 的现有实现；MVP 继续关闭 HR/热词/外部 normalization，确保返回真正原始结果 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/com/k2fsa/sherpa/onnx/OfflineRecognizer.kt` | vendored sherpa 1.12.39 binding | 已暴露 `hr: HomophoneReplacerConfig`、hotword 字段与 stream API；不应直接修改 vendored API |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/com/k2fsa/sherpa/onnx/HomophoneReplacerConfig.kt` | HR 的 `lexicon` 和 `ruleFsts` 配置 | API 已存在，但当前 `SenseVoiceEngine` 没有启用 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` | sherpa JNI | 固定为 1.12.39，不应修改或替换 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/assets/asr_models/sense_voice_zh_en_ja_ko_yue/model.int8.onnx` | SenseVoiceSmall INT8 | 不应修改 |

当前结果不包含 token posterior 或字符置信度。因此本计划中的“高置信度纠错”不能伪装成模型概率，而应定义为：**规则来源可信、拼音候选唯一、变更范围受限、且能够被确定性验证与撤销**。

### 1.6 原始文本拼接、显示和存储链路

```text
ExperimentRunner
  → 每段 SegmentRecognition(result = RawRecognitionResult)
  → recognized.joinToString("") { result.text }
  → ExperimentRun.rawJoinedText
  → TextMetrics 计算原始 CER
  → LabUiState.runs（进程内，最多 100 条）
  ├── LabScreen 展示
  └── ExperimentJsonExporter 输出 JSON schema v2
```

当前不存在真正的转写数据库：

- `LabUiState`/`StateFlow` 是唯一的结果状态；进程死亡后丢失；
- 录音 WAV 会留在 `filesDir/experiments`，但没有数据库索引、恢复、删除或容量治理；
- JSON 只有导出，没有导入、恢复或 round-trip；
- 没有 Room、DAO、Repository 或 migration；
- 500 ms 硬切重叠会把重叠音频送入相邻两段，而当前直接拼接可能产生重复字。

### 1.7 会议总结链路

当前仓库中没有以下任何内容：

- `MeetingSummaryService`；
- Meeting/Transcript/Summary 数据库实体；
- Retrofit、OkHttp、Ktor 或其他网络客户端；
- `INTERNET` 权限；
- LLM 或云端摘要依赖。

以上结论只适用于 SenseVoice Lab。生产 Murmurnote 的实际链路如下节所列；当前仍不得为满足名称而虚构一个“现有 MeetingSummaryService”。

### 1.8 生产 Murmurnote 的真实调用链

生产项目根目录为 `/home/wzwys/work/Murmurnote`，本轮只读检查的提交为 `f16f2c4`。

#### 录音和实时切段

```text
RecordingController.start()
  → AudioRecorder.start()
  → AudioRecord(MediaRecorder.AudioSource.MIC, 16 kHz, mono, PCM16)
  → 同时写完整 WAV 和滚动 segment WAV
  → HomeViewModel 轮询已完成 rolling segments
  → LocalAsrEngine/CloudAsrEngine 实时转写
  → RecordingSegment 写入 Room
  → 可选 LlmClient.updateDraftSummary()
```

`/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/audio/AudioRecorder.kt` 已固定使用普通 `MIC`，这一项已经满足决定。但其滚动切段仍是能量判断：`-55 dBFS`、最短累计语音约 `1 s`、尾静音 `1.8 s`、段上限 `15 s`、硬切 overlap `600 ms`，与已确认的 Silero 参数不同。

#### 停止录音/导入后的转写

```text
RecordingController.stopAndSubmit() 或导入入口
  → TranscriptionService
  → AudioPipeline.process()
  → AudioConverter：mono 16 kHz WAV
  → AudioSplitter
       ffmpeg silencedetect
       + VadDetector（纯 RMS 能量 VAD）
       → 0..25 s、优先静音点、无 overlap 的 slice
  → AsrEngineProvider.current()
  → LocalAsrEngine 或 CloudAsrEngine
  → 每段 TranscriptSegment 立即写 Room
  → transcripts.joinToString("\n")
  → Recording.rawTranscript
  → 可选 LlmClient.extractItemsAuto()
  → summary/finalSummary + ExtractedItem
```

生产项目当前有两套能量/静音切分：录音中的 `AudioRecorder` 实时能量切段，以及停止/导入后的 `AudioSplitter + VadDetector + ffmpeg silencedetect`。若要求“使用神经网络 VAD，不使用能量 VAD”，两条路径都必须处理，不能只替换 `VadDetector` 而留下 `AudioRecorder.isSpeechFrame()`。

#### 两个本地 ASR 模型

`/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/asr/AsrModelUrls.kt` 定义：

| 模型 | 项目内 ID | 运行包 | 当前并发 |
|---|---|---|---|
| SenseVoiceSmall INT8 | `sense_voice_zh_en_ja_ko_yue` | 2024-07-17 | 允许 1–3 路 |
| Qwen3-ASR 0.6B INT8 | `qwen3_asr_0_6b` | `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` | 固定单路，模型约 940 MiB |

共同链路：

```text
AudioPipeline.transcribeAll()
  → AsrEngine.transcribe(wav)
  → LocalAsrEngine
  → WavReader.readMono16kPcm()
  → SherpaBridge.decode(samples)
  → OfflineRecognizer / greedy_search
  → AsrResult(text, durationMs)
```

`SherpaBridge` 按文件布局识别模型：

- SenseVoice：固定 `language="zh"`、`useInverseTextNormalization=true`；
- Qwen3-ASR：设置 conv frontend、encoder、decoder、tokenizer、`maxTotalLen=512`、`maxNewTokens=512`，但当前没有设置模型专用 `hotwords`；
- 两路最终都只反射读取 `result.text`，生产 `AsrResult` 没有保留 tokens、timestamps、lang、emotion 或 event。

Qwen3-ASR 没有 SenseVoice 的 `language/useInverseTextNormalization` 配置字段。当前版本由模型自行输出文字和标点，不能把“中文 Locale → zh、ITN ON”机械套到 Qwen config；语言/ITN 决策应作为 model capability 分支。SenseVoice 按系统 Locale 的默认语言仍需把目前硬编码的 `zh` 参数化。

#### Room 与用户编辑

生产项目已经使用 Room 2.7.0/KSP，数据库 `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/MurmurnoteDatabase.kt` 当前为 v5，已有显式 `1→2→3→4→5` migration，且没有 destructive migration。

现有数据：

- `Recording.rawTranscript` 保存整篇转写；
- `TranscriptSegment.text/startMs/endMs/sequence` 保存逐段文字和位置；
- `RecordingSegment.filePath/startMs/endMs/transcriptText` 保存实时滚动音频及其转写，适合后续二次识别。

但当前用户编辑路径：

```text
DetailViewModel
  → RecordingRepository.updateTranscriptSegmentText()
  → RecordingDao.updateTranscriptSegmentText()
  → 直接覆盖 TranscriptSegment.text
  → 重新拼接并覆盖 Recording.rawTranscript
```

这与“原始文本不可覆盖”冲突，是正式纠错功能实施前必须先修复的核心数据问题。应通过 migration 新增 `rawText/correctedText`（逐段）以及 `correctedTranscript/correctionRevision`（整篇），旧 `text/rawTranscript` 先回填为 raw 基线；后续编辑只更新 corrected 派生字段。

#### 云端总结的真实边界

生产项目没有 `MeetingSummaryService` 类，实际实现是：

- `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/remote/llm/LlmClient.kt`：`extractItemsAuto()`、`updateDraftSummary()`；
- `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/pipeline/AudioPipeline.kt`：完整转写后调用 LLM 并保存 summary/items；
- `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/ui/screen/home/HomeViewModel.kt`：实时段落触发 draft summary；
- `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/ui/screen/detail/DetailViewModel.kt`：只重跑 LLM 总结，不重跑 ASR。

这些文件就是本计划所说的“现有云端会议总结功能”。它们必须保留；只把输入改为 `TranscriptRepository` 提供的已定稿本地转写快照，不能让 LLM 输出反向生成纠错规则。

## 2. 建议的模块边界

```text
MIC AudioCapture
    ↓
Silero VAD / AudioSegmenter
    ↓
AsrEngine（只输出不可变 RawRecognitionResult）
    ↓
TranscriptAssembler（保留逐段原文，处理可证明的 overlap 对齐）
    ↓
CorrectionEngine（Room 规则 + 本地拼音 + 确定性候选）
    ↓
CorrectionValidator（拒绝不安全修改，生成可追踪记录）
    ↓
TranscriptRepository（同事务保存 raw、corrected、records、segments）
    ├── UI：原文/纠正文/差异/撤销
    ├── 可选 ReRecognitionEngine：未来从原音频重切并再次识别
    └── 现有云端总结消费者（LlmClient 调用链）：只读已定稿快照，不得反向改写本地转写
```

双模型配置不能用一组大量 nullable 字段拼装，否则很容易把 SenseVoice 的 ITN/language 误传给 Qwen，或把 Qwen hotwords 当成 Transducer hotwords。建议在 `AsrRecognitionConfig.kt` 中先定义不可混用的判别联合，并只保留一版公共 `AsrEngine/CorrectionEngine/TranscriptRepository` 契约：

```kotlin
sealed interface LocalModelOptions {
    data class SenseVoice(
        val language: String,        // zh / en / ...
        val useItn: Boolean,
    ) : LocalModelOptions

    data class Qwen3Asr(
        val hotwordSnapshot: HotwordSnapshot?,
    ) : LocalModelOptions
}

data class LocalAsrSessionConfig(
    val engineType: AsrEngineType,
    val modelId: String,
    val modelOptions: LocalModelOptions,
    val vadPresetVersion: String,
)

data class AsrProvenance(
    val engineType: AsrEngineType,
    val modelId: String,
    val configFingerprint: String,
    val configSnapshotJson: String,
)
```

边界规则：

- `LOCAL_SENSE_VOICE` 只接受 `LocalModelOptions.SenseVoice`，`LOCAL_QWEN3_ASR` 只接受 `Qwen3Asr`；在 `LocalAsrEngine` 创建会话时一次校验，错误配置立即失败，不在 decode 深处静默忽略；
- 现有 `AsrEngine.transcribe()` 先保持单一公共入口；session config 在 engine/provider 创建时冻结，整批分段一致复用；
- `AsrResult` 采用向后兼容的增量扩展，例如新增带默认值的 `provenance`，避免一次破坏 `CloudAsrEngine` 和现有测试；写入新 transcript 时，`TranscriptRepository` 再把 provenance 作为必填边界校验；
- 模型差异止于 ASR adapter/config 层。`CorrectionEngine`、`CorrectionValidator`、Room schema、撤销、摘要 snapshot 不按模型复制两套实现；
- 所有配置类型和 fingerprint 都写 contract tests，保证序列化稳定、字段顺序不影响 fingerprint、未知/旧配置有明确迁移或拒绝语义。

### 核心模块职责

| 模块 | 现状 | 建议职责 |
|---|---|---|
| `AsrEngine` | Lab 有 `RecognitionEngine`；生产 Murmurnote 已有真正的 `AsrEngine`、`LocalAsrEngine`、`CloudAsrEngine` | 生产项目直接复用现有接口；以向后兼容的可选字段扩展 `AsrResult` provenance，并在新持久化边界要求其存在；只返回原始模型结果，不做 HR、拼音、字符串替换或摘要 |
| `TranscriptAssembler` | 不存在；当前由 `ExperimentRunner.joinToString("")` 代替 | 保留逐段结果，构造拼接原文；对于 500 ms overlap，只允许删除可证明完全相同的 suffix/prefix，并以 `SEGMENT_OVERLAP_DEDUP` 单独记录，不混入错字纠正 |
| `CorrectionEngine` | 不存在 | 读取当前会议/全局词条和已确认历史规则，生成确定性候选；不得调用 LLM |
| `CorrectionValidator` | 不存在 | 实施硬约束、冲突消解、可逆性检查；失败时整段回退原文 |
| `PinyinCandidateProvider` | 不存在 | 隔离拼音库；支持显式读音、多音字和规范化；具体依赖、体积、许可证需要确认 |
| `TranscriptRepository` | 生产项目已有 `RecordingRepository`，但没有独立 transcript/correction repository | 从现有 Repository 拆出转写事务：保存原文、纠正文、分段与纠正记录，支持撤销和重建；RecordingRepository 继续管理会议/录音元数据 |
| `ReRecognitionEngine` | 不存在 | 后续按音频位置扩大/重切片段并再次调用同一 AsrEngine；每次尝试新增记录，不覆盖首次结果 |
| 云端总结消费者（需求中的 `MeetingSummaryService` 概念） | 两个仓库都没有这个类名；生产功能实际由 `LlmClient + AudioPipeline + HomeViewModel/DetailViewModel` 组成 | 保持现有功能为纠错链路的下游消费者；只能读取已定稿快照，不参与识别/纠错，不写回原始文本或规则，不必为改名而重构 |

## 3. 纠错策略和硬约束

### 3.1 MVP 的允许行为

MVP 只允许以下三类变更：

1. 用户明确确认过的精确 `错词 → 正词` 规则；
2. 当前会议词表中，拼音完全匹配且候选唯一的多汉字术语/人名替换；
3. 人工审核、离线预编译的低歧义静态术语规则。

MVP 不允许：

- 改写语序；
- 删除“嗯、啊”等口头语；
- 删除重复话语或说话者的自我修正；
- 总结、补全或推断模型没有识别出的内容；
- 自动修改标点、空格或 ITN 结果；
- 跨 VAD 分段进行不透明的大范围替换；
- 根据“读起来更通顺”进行修改；
- 使用 LLM 生成候选、判断候选或抽取用户修改规则。

### 3.2 确定性候选优先级

建议优先级从高到低：

1. 当前用户刚刚确认的手动修改；
2. 已启用的用户确认精确规则；
3. 当前会议专属词条的唯一拼音候选；
4. 全局词条的唯一拼音候选；
5. 审核过的静态 Homophone 术语规则。

相同范围出现多个候选时，不能猜测：应拒绝自动应用并在 UI 中提示候选。规则重叠时先按来源优先级，再按最长原文范围选择；仍然相同则拒绝。

### 3.3 `CorrectionValidator` 建议硬规则

- 自动拼音规则只处理汉字，不跨越标点、空白或分段边界；
- 自动拼音替换默认至少两个汉字，单字同音不自动改；
- 拼音及声调规范化后必须完全匹配；多音字必须有显式读音或唯一候选；
- 自动规则第一阶段只允许等音节数替换，且不得包含插入/删除；
- 不允许把非空原文替换为空；
- 不允许循环规则和反向冲突，例如同时存在 `A→B` 与 `B→A`；
- 不允许两个纠正记录覆盖相同 raw offset；
- 应用完成后，必须能仅凭 `rawText + ordered correction records` 重建同一个 `correctedText`；
- 任一校验失败时返回原文，并记录拒绝原因，不得输出部分损坏文本；
- 用户撤销只改变纠正记录状态并重新构造纠正文，绝不改写原文。

### 3.4 用户修改如何变成历史规则

不使用 LLM 的前提下，用户编辑应采用代码点安全的确定性 diff：

1. 保存编辑前的 `rawText`、当前 `correctedText` 和用户最终文本；
2. 只提取边界清楚、短范围、替换型的 diff；
3. 纯插入、纯删除、长句改写、多段变化只作为本次手动修改记录，不自动生成复用规则；
4. 向用户展示拟生成的 `observedText → replacementText`；
5. 只有用户再次确认后，才写入历史规则并启用；
6. 以后命中规则时仍生成独立 `CorrectionRecord`，便于逐次撤销和审计。

## 4. Homophone Replacer 与热词边界

### 4.1 当前热词限制

当前 SenseVoice 使用 CTC/greedy 路径。sherpa-onnx 的 contextual hotwords 仅支持 Transducer，并要求 `modified_beam_search`；因此当前路径不能直接使用 `hotwordsFile` 或 `createStream(hotwords)` 获得 Transducer 热词偏置。第一阶段不实现 CTC Prefix Beam Search，也不修改解码器。

官方说明：<https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html>

### 4.2 Homophone Replacer 可以做什么

Homophone Replacer 与 Transducer 热词是不同技术。它可以配合 SenseVoice greedy 输出，通过：

- 通用 `lexicon.txt` 把汉字转成带声调拼音；
- 离线预编译 `replace.fst` 匹配完整拼音短语并输出指定汉字。

当前锁定的 1.12.39 Kotlin binding 和 `libsherpa-onnx-jni.so` 已包含 HR 配置读取和实现；通过 `OfflineRecognizerConfig.hr` 接入不需要修改或重编 native library。但是当前 JNI 没有暴露“只对一段文本单独调用 HR”的 Kotlin API，所以要保留 canonical raw，仍需独立 recognizer/双路实验或改用 Kotlin 后处理。

限制：

- 只替换汉字；
- `replace.fst` 需要在设备外预先生成，运行时不能动态修改；
- 不适合直接承载每场会议临时人名和不断变化的用户规则；
- native HR 会改变返回的 `result.text`，但示例中的 tokens/timestamps 仍对应模型原始 token，容易造成文本与 token 证据不一致；
- 如果直接在唯一一次 ASR 中启用 HR，就无法保证保存到的是原始 `text`。
- 1.12.39 的多 FST 行为存在限制，计划中只允许一个经过审核的 `replace.fst`，不依赖逗号分隔多文件；
- lexicon/FST 缺失或损坏可能在 native 层造成致命失败，进入 JNI 前必须校验资产存在、大小和 SHA-256。

官方说明：<https://k2-fsa.github.io/sherpa/onnx/homophone-replacer/index.html>

因此建议：

1. MVP 的 canonical `SenseVoiceEngine` 继续保持 HR 关闭；
2. 静态 HR 作为后续独立 A/B 实验，不与 Room 动态规则混合；
3. 若最终采用 native HR，必须先保存一次 HR-off 原始结果，再在独立派生路径生成 HR 结果，或证明可以无损重建原始 text；
4. 不在 Android 设备上运行 Pynini 或动态生成 FST；
5. 规则资产要固定版本、许可证、大小和 SHA-256，并用真实错例做零回归验证。

### 4.3 Qwen3-ASR 的精确兼容性结论

生产 Murmurnote 使用的是 `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`，运行库固定在 sherpa-onnx `1.12.39`。

结论：

| 能力 | SenseVoice / 1.12.39 | Qwen3-ASR / 1.12.39 | 建议 |
|---|---|---|---|
| Silero VAD | 可共用 | 可共用 | VAD 位于 ASR 前，与模型解码无关 |
| Room + 拼音 + 确定性后处理 | 可共用 | 可共用 | 作为主纠错架构，按 `engineType/modelId` 记录来源 |
| native Homophone Replacer | 实现会调用 | **实现没有调用，配置 `hr` 也不生效** | 当前版本仅对 SenseVoice 做可选实验，不给 Qwen 假装启用 |
| 通用 `hotwordsFile + modified_beam_search` | 不支持 | 不支持 | 这是 Transducer 专用能力，两者都不要接 |
| Qwen 模型专用 `OfflineQwen3AsrModelConfig.hotwords` | 不适用 | 支持，greedy 下通过 prompt 生效 | 作为 Qwen 独立可选阶段，不与 Transducer hotwords 混称 |

v1.12.39 的 `OfflineRecognizerQwen3ASRImpl` 只生成 text/tokens，没有调用 `ApplyHomophoneReplacer()`。官方在 2026-07-07 发布的 sherpa-onnx v1.13.4 中才专门加入 “ApplyHomophoneReplacer in OfflineRecognizerQwen3ASRImpl”。所以若未来升级到 v1.13.4 或更高版本，Qwen 才可重新评估 native HR；升级涉及 Kotlin binding、全部 ABI `.so`、ONNX Runtime、反射字段和现有两模型回归，不应只为 HR 直接替换。

来源：

- Qwen3-ASR 模型及专用热词示例：<https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html>
- v1.12.39 Qwen recognizer 实现：<https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.39/sherpa-onnx/csrc/offline-recognizer-qwen3-asr-impl.cc>
- v1.13.4 修复说明：<https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.4>

Qwen 模型专用 hotwords 的注意点：

- 词条使用逗号分隔，写入 Qwen system-role prompt，不是 beam-search score；
- 当前 `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/asr/SherpaBridge.kt` 没有传 `hotwords`，所以现在尚未启用；
- hotwords 会占用 `maxTotalLen=512` 的上下文预算，词太多/太长会挤压音频 token 或生成空间；
- 词表在 recognizer 创建时进入配置。动态会议词汇应在一批分段开始前生成一次快照和 fingerprint，整场复用；词表变化时才释放并重建 bridge，不能每段重载约 940 MiB 模型；
- 官方绕口令示例显示目标术语有改善，但附近内容仍可能出现新错误，因此必须与无热词结果做目标集/clean set A/B；
- 先限制为用户明确提供的当前会议术语，并设置经基准确定的条数/总字符上限；不能把全部历史词典无筛选塞入 prompt。

## 5. 建议的数据结构

以下为目标结构，不是当前已有 Kotlin 类。

字段命名对应需求：原始文本统一为 `rawText`，纠正后文本统一为 `correctedText`；二者不得复用同一可覆盖字段。

### 5.1 正确词条 `TermEntry`

```kotlin
data class TermEntry(
    val id: String,
    val canonicalText: String,
    val normalizedPinyin: String?,
    val pronunciationVariants: List<String>,
    val scope: TermScope,            // MEETING 或 GLOBAL
    val scopeId: String?,            // meetingId；GLOBAL 时为空
    val source: TermSource,          // USER / IMPORTED_STATIC
    val enabled: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
```

要求：

- `canonicalText + scope + scopeId` 建唯一索引；
- 多音字、人名允许保存用户确认的显式读音；
- 词条删除建议 soft delete 或 tombstone，避免被历史学习流程悄悄恢复；
- 不把整份会议稿或任意长句当作词条。

### 5.2 历史纠错规则 `CorrectionRule`

```kotlin
data class CorrectionRule(
    val id: String,
    val observedText: String,
    val replacementText: String,
    val observedPinyin: String?,
    val matchMode: MatchMode,        // EXACT_TEXT / EXACT_PINYIN
    val scope: TermScope,
    val scopeId: String?,
    val source: RuleSource,          // USER_CONFIRMED / STATIC
    val confidenceTier: ConfidenceTier,
    val enabled: Boolean,
    val useCount: Int,
    val lastUsedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
```

建议约束：

- `(observedText, replacementText, scope, scopeId)` 唯一；
- 用户规则不能被自动规则覆盖；
- 反向冲突必须在写入时拒绝；
- 不使用一个虚构的 ASR 概率字段；`confidenceTier` 表示确定性证据等级。

### 5.3 转写主记录 `TranscriptRecord`

```kotlin
data class TranscriptRecord(
    val transcriptId: String,
    val meetingId: String?,
    val recordingId: String,
    val rawText: String,
    val correctedText: String,
    val correctionRevision: Long,
    val engineType: String,
    val modelId: String,
    val modelSha256: String,
    val language: String?,           // SenseVoice 使用；Qwen auto-language 时为空
    val useItn: Boolean?,            // SenseVoice 使用；Qwen 无同名开关时为空
    val configFingerprint: String,
    val configSnapshotJson: String,
    val rawProvenance: String,       // MODEL_RAW / LEGACY_PROVENANCE_UNKNOWN
    val createdAtEpochMillis: Long,
    val finalizedAtEpochMillis: Long?,
)
```

不变量：

- `rawText` 创建后不可更新；
- `correctedText` 是派生快照，必须能从原文和当前有效纠正记录重建；
- 每次撤销/应用规则递增 `correctionRevision`；
- 云端会议总结只读取某个已定稿 revision 的不可变快照。

### 5.4 分段、时间戳和音频位置 `TranscriptSegmentRecord`

```kotlin
data class TranscriptSegmentRecord(
    val segmentId: String,
    val transcriptId: String,
    val segmentIndex: Int,
    val recordingSegmentId: String?,
    val audioFileRelativePath: String?,
    val startSample: Long,
    val endSampleExclusive: Long,
    val sampleRateHz: Int,
    val startMs: Long,
    val endMs: Long,
    val cutReason: String,
    val overlapBeforeMs: Int,
    val rawText: String,
    val correctedText: String,
    val tokensJson: String?,
    val timestampsJson: String?,
    val lang: String?,
    val emotion: String?,
    val event: String?,
)
```

音频本体继续存 App 私有文件，Room 只保存 `recordingId`、相对路径、文件状态、格式和范围，不把 WAV/`FloatArray` 存成 BLOB。只有模型和 bridge 确实返回 token timestamps 时，它们才作为相对当前 segment 的可空证据保存；需要展示绝对时间时由 `segment.startMs + tokenOffset` 推导。当前生产 Qwen/bridge 没有字级时间戳，不能用均匀分配等方式伪造。

### 5.5 结构化纠正记录 `CorrectionRecord`

```kotlin
data class CorrectionRecord(
    val correctionId: String,
    val transcriptId: String,
    val segmentId: String?,
    val rawStartCodePoint: Int,
    val rawEndCodePointExclusive: Int,
    val originalText: String,
    val replacementText: String,
    val kind: CorrectionKind,
    val source: CorrectionSource,
    val sourceRuleId: String?,
    val confidenceTier: ConfidenceTier,
    val decisionReason: String,
    val algorithmVersion: String,
    val status: CorrectionStatus,    // APPLIED / REVERTED / REJECTED
    val createdAtEpochMillis: Long,
    val revertedAtEpochMillis: Long?,
)
```

`CorrectionSource` 至少区分：

- `USER_MANUAL_EDIT`；
- `USER_CONFIRMED_RULE`；
- `MEETING_TERM_PINYIN`；
- `GLOBAL_TERM_PINYIN`；
- `STATIC_HOMOPHONE_FST`；
- `SEGMENT_OVERLAP_DEDUP`。

分段重叠去重不是错字纠正，必须使用独立 source/kind，防止它被误当作“删除重复口头语”。

### 5.6 二次识别记录 `RecognitionAttempt`

```kotlin
data class RecognitionAttempt(
    val attemptId: String,
    val transcriptId: String,
    val sourceSegmentId: String?,
    val attemptIndex: Int,
    val reason: String,              // VAD_EDGE / POSSIBLE_OMISSION / USER_REQUEST
    val audioStartSample: Long,
    val audioEndSampleExclusive: Long,
    val engineType: String,
    val modelId: String,
    val configSnapshotJson: String,
    val rawText: String,
    val selected: Boolean,
    val createdAtEpochMillis: Long,
)
```

每次重识别都新增 attempt；选择新结果也不能删除首次 raw 结果。

## 6. 建议新增文件清单

### 6.1 生产 Murmurnote 中建议新增

以下是基于 `/home/wzwys/work/Murmurnote` 真实 package、Room 和 pipeline 给出的目标路径；本轮没有创建这些文件。

| 拟新增完整路径 | 职责 | 阶段 |
|---|---|---|
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/asr/AsrRecognitionConfig.kt` | 判别联合式 model options、session config 与 provenance：从类型上隔离 SenseVoice language/ITN 和 Qwen hotwords，并记录 VAD preset/version | 1 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/com/k2fsa/sherpa/onnx/Vad.kt` | 与现有 sherpa-onnx 1.12.39 JNI 匹配的官方 Kotlin VAD binding；生产项目当前缺少 | 1 |
| `/home/wzwys/work/Murmurnote/app/src/main/assets/vad_models/silero_vad_v5/silero_vad.onnx` | Silero v5 模型；构建/首次运行前校验固定 SHA-256，二进制是否纳入 Git 需按现有模型分发策略确认 | 1 |
| `/home/wzwys/work/Murmurnote/app/src/main/assets/vad_models/silero_vad_v5/LICENSE` | Silero 模型许可证与来源记录 | 1 |
| `/home/wzwys/work/Murmurnote/scripts/prepare-vad-model.sh` | 可重复获取并校验 Silero 资产；不得无校验下载 | 1 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/audio/NeuralVadDetector.kt` | 封装 sherpa `Vad`/Silero v5 会话，接收 16 kHz PCM 并输出 speech ranges | 1 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/audio/NeuralVadSegmentPlanner.kt` | 复用 Lab 已验证算法：padding、合并、250 ms/700 ms、25 s 硬切和 500 ms overlap | 1 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/audio/StreamingNeuralVadSession.kt` | 给实时录音滚动段使用的 Silero session、500 ms ring buffer 和边界事件；不得阻塞 AudioRecord 写盘 | 1b |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/transcript/TranscriptAssembler.kt` | 按 segment start/end 构造 raw/corrected 稿，处理有证据的 overlap 对齐 | 2 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/transcript/SummaryTranscriptSnapshot.kt` | 给现有云端总结链路的只读 DTO：transcript ID、revision、明确选定文本，不暴露 DAO/词典 | 3 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/correction/CorrectionModels.kt` | 候选、规则、来源、置信等级、修改记录和拒绝原因 | 3 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/correction/CorrectionEngine.kt` | 模型无关的本地确定性纠错接口 | 3 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/correction/DeterministicCorrectionEngine.kt` | 用户确认精确规则与非重叠应用 | 3 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/correction/CorrectionValidator.kt` | substitution-only、冲突、边界、可逆性和失败回 raw | 3 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/correction/PinyinCandidateProvider.kt` | 拼音实现隔离、显式读音和多音字契约 | 4 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/correction/LocalPinyinCorrectionEngine.kt` | 会议/全局词条的唯一拼音候选 | 4 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/correction/QwenHotwordPolicy.kt` | 对 Qwen 当前会议词条排序、限长、生成 CSV 和 fingerprint；不参与 SenseVoice | 4b |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/rerecognition/ReRecognitionCoordinator.kt` | 复用现有 AsrEngine，对 RecordingSegment 音频范围做重切/再次识别 | 6 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/entity/TermEntry.kt` | 正确词条 Room entity | 3-4 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/entity/CorrectionRule.kt` | 历史纠错规则 Room entity | 3 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/entity/CorrectionRecord.kt` | 每次应用/撤销的结构化记录 | 3 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/entity/RecognitionAttempt.kt` | 二次识别 attempt | 6 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/dao/CorrectionDao.kt` | 词条、规则、记录和冲突查询 | 3 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/repository/TranscriptRepository.kt` | 从 RecordingRepository 拆出的 raw/corrected/records 事务和 summary snapshot | 2-3 |

### 6.2 SenseVoice Lab 中可选验证文件

以下路径基于 SenseVoice Lab 包名，仅用于先行 A/B；生产功能应以上一表为准。

| 拟新增完整路径 | 职责 | 阶段 |
|---|---|---|
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/asr/RecognitionDefaults.kt` | 根据 Android Locale 生成初始 language/ITN/VAD 配置；不把 Android Context 塞进纯数据类 | 1 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/transcript/TranscriptModels.kt` | raw/corrected/segment/correction/attempt 的领域模型与不变量 | 2 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/transcript/TranscriptAssembler.kt` | 从逐段原始结果构造原始稿；可审计地处理硬切 overlap | 2 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/correction/CorrectionModels.kt` | 候选、规则、来源、置信等级、拒绝原因 | 3 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/correction/CorrectionEngine.kt` | 纯接口：`raw + terms + rules → candidates/result` | 3 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/correction/DeterministicCorrectionEngine.kt` | 精确历史规则、优先级和非重叠候选应用 | 3 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/correction/CorrectionValidator.kt` | 校验 substitution-only、边界、冲突、可逆性；失败回退 raw | 3 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/correction/PinyinCandidateProvider.kt` | 拼音能力接口及规范化契约；具体开源实现需要确认 | 4 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/correction/LocalPinyinCorrectionEngine.kt` | 会议/全局词条的唯一拼音候选生成 | 4 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/correction/HomophoneReplacerExperiment.kt` | 可选 HR-off/HR-on A/B 封装，不进入 canonical raw 路径 | 5 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/rerecognition/ReRecognitionEngine.kt` | 后续音频范围重切和二次识别接口 | 6 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/data/local/LocalAsrDatabase.kt` | RoomDatabase、版本和 migration 注册 | 2 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/data/local/LocalAsrEntities.kt` | 录音、转写、分段、纠正、词条、规则、attempt entities | 2 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/data/local/TranscriptDao.kt` | raw/corrected/segment/record 的事务读写 | 2 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/data/local/CorrectionDictionaryDao.kt` | 词条和历史规则 CRUD、scope、启停与冲突查询 | 3 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/data/TranscriptRepository.kt` | 文件引用与 Room 的一致性、保存、撤销、恢复、定稿快照 | 2-3 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/ui/CorrectionReviewState.kt` | 原文/纠正文/diff/候选/撤销的 UI state，不包含算法 | 3 |

### 建议新增测试文件

| 拟新增完整路径 | 验证范围 |
|---|---|
| `/home/wzwys/work/Murmurnote/app/src/test/java/app/murmurnote/android/audio/NeuralVadSegmentPlannerTest.kt` | 精确 VAD preset、padding、25 s 硬切、500 ms overlap 和边界不变量 |
| `/home/wzwys/work/Murmurnote/app/src/test/java/app/murmurnote/android/data/asr/AsrRecognitionConfigTest.kt` | SenseVoice Locale/ITN 与 Qwen auto-language/hotword capability 分支 |
| `/home/wzwys/work/Murmurnote/app/src/test/java/app/murmurnote/android/domain/transcript/TranscriptAssemblerTest.kt` | 双模型普通拼接、overlap 去重证据、无法证明时不删字 |
| `/home/wzwys/work/Murmurnote/app/src/test/java/app/murmurnote/android/domain/correction/DeterministicCorrectionEngineTest.kt` | 双模型 raw 输入、精确规则、优先级、冲突、原文不可变 |
| `/home/wzwys/work/Murmurnote/app/src/test/java/app/murmurnote/android/domain/correction/CorrectionValidatorTest.kt` | 禁止插入/删除/标点变化、可逆性、失败回 raw |
| `/home/wzwys/work/Murmurnote/app/src/test/java/app/murmurnote/android/domain/correction/QwenHotwordPolicyTest.kt` | 会议词条排序、CSV 转义、条数/字符预算、fingerprint 稳定性 |
| `/home/wzwys/work/Murmurnote/app/src/test/java/app/murmurnote/android/domain/pipeline/AudioPipelineCorrectionBoundaryTest.kt` | raw 先持久化、本地纠错后才生成摘要 snapshot，LLM 不能写回纠错数据 |
| `/home/wzwys/work/Murmurnote/app/src/androidTest/java/app/murmurnote/android/data/local/LocalAsrMigrationTest.kt` | 生产 Room v5→下一版迁移、旧 raw 数据保全、纠错表事务与撤销；目录当前不存在，实施时新增 |
| `/home/wzwys/work/Murmurnote/app/src/androidTest/java/app/murmurnote/android/data/asr/LocalAsrDualModelSmokeTest.kt` | arm64 真机分别运行 SenseVoice/Qwen，验证 raw/config/model provenance；目录当前不存在，实施时新增 |
| `/home/wzwys/work/Murmurnote_test/app/src/test/java/app/murmurnote/sensevoicelab/asr/RecognitionDefaultsTest.kt` | `zh-* → ZH`、`en-* → EN`、其他 Locale 的待确认策略、ITN ON |
| `/home/wzwys/work/Murmurnote_test/app/src/test/java/app/murmurnote/sensevoicelab/transcript/TranscriptAssemblerTest.kt` | 普通拼接、500 ms overlap、重复汉字、无法证明时不删字 |
| `/home/wzwys/work/Murmurnote_test/app/src/test/java/app/murmurnote/sensevoicelab/correction/DeterministicCorrectionEngineTest.kt` | 精确规则、优先级、冲突、范围重叠、原文不可变 |
| `/home/wzwys/work/Murmurnote_test/app/src/test/java/app/murmurnote/sensevoicelab/correction/CorrectionValidatorTest.kt` | 禁止插入/删除/标点变化、可逆性、失败回退 raw |
| `/home/wzwys/work/Murmurnote_test/app/src/test/java/app/murmurnote/sensevoicelab/correction/LocalPinyinCorrectionEngineTest.kt` | 同音术语、多音字、单字、多个候选、声调和中英混合 |
| `/home/wzwys/work/Murmurnote_test/app/src/androidTest/java/app/murmurnote/sensevoicelab/LocalAsrDatabaseTest.kt` | Room 约束、事务、撤销、进程重建后的读取 |
| `/home/wzwys/work/Murmurnote_test/app/src/androidTest/java/app/murmurnote/sensevoicelab/LocalCorrectionEndToEndTest.kt` | 内置音频 → Silero → SenseVoice → 本地纠错 → 保存；全程无网络 |
| `/home/wzwys/work/Murmurnote_test/app/src/androidTest/java/app/murmurnote/sensevoicelab/HomophoneReplacerSmokeTest.kt` | 可选静态 HR 的真实 JNI A/B、raw 保存和 token/text 对齐风险 |

## 7. 建议修改、复用或保持不动的现有文件

### 7.1 生产 Murmurnote 后续建议修改

| 完整路径 | 后续改动 | 原因 |
|---|---|---|
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/audio/AudioRecorder.kt` | 用 `StreamingNeuralVadSession` 替换 `isSpeechFrame()`、`-55 dBFS`、1.8 s pause 和 15 s/600 ms 规则；MIC 保持不动 | 当前实时转写仍由能量 VAD 决定边界 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/audio/VadDetector.kt` | 从生产依赖图移除或明确改名为仅测试基线；生产使用新 `NeuralVadDetector` | 当前类是纯 RMS 能量 VAD，与决定冲突 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/audio/AudioSplitter.kt` | 用 Silero speech ranges + `NeuralVadSegmentPlanner` 生成切片；保留 FFmpeg 只做物理切片 | 当前合并 ffmpeg silencedetect 和能量 VAD，且没有 500 ms overlap/padding |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/asr/AsrEngine.kt` | 保持现有接口名和 `transcribe` 入口；用带默认值的可选 provenance 增量扩展 `AsrResult`，明确 `text` 为 raw | 两本地模型和 CloudAsrEngine 已共享该契约，不应新建同义接口或一次破坏全部调用者 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/asr/LocalAsrEngine.kt` | 接收会话级识别配置/词汇快照；bridge cache key 包含 model ID 和 config fingerprint；Qwen 保持单路 | 当前只按 model ID 缓存，不知道语言、ITN 或热词变化 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/asr/SherpaBridge.kt` | SenseVoice 参数化 language/ITN；Qwen 可选传模型专用 hotwords；返回 raw text；MVP 不接 HR | 当前 SenseVoice 硬编码 zh/ITN ON，Qwen hotwords 为空，且只返回 String |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/pipeline/AudioPipeline.kt` | 在逐段 raw 入库/整篇 raw 拼接后调用 assembler→correction→validator→repository，再把已定稿 snapshot 交给 LlmClient | 这是停止/导入后的核心插入点，必须保证 LLM 在本地纠错之后且只单向消费 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/entity/Recording.kt` | 保留 `rawTranscript`；新增 `correctedTranscript`、`correctionRevision`、ASR/VAD config snapshot | 当前用户编辑会覆盖名为 raw 的字段 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/entity/TranscriptSegment.kt` | 将模型输出永久保存在 `rawText`，派生结果放 `correctedText`；保留 start/end/sequence | 当前单一 `text` 同时承担 raw 和用户编辑结果 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/entity/RecordingSegment.kt` | 保留 filePath/start/end；增加切段原因、overlap/config fingerprint（若 migration 允许） | 现有音频片段是二次识别的重要基础，但缺少切段来源 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/dao/RecordingDao.kt` | 删除“编辑时覆盖 raw”语义；新增 raw/corrected 分离查询和事务入口 | `updateTranscriptSegmentText`/`markTranscriptEdited` 当前直接覆盖原始证据 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/repository/RecordingRepository.kt` | 把 transcript/correction 事务拆给新 `TranscriptRepository`；继续管理录音元数据 | 避免 Repository 同时承担文件、原文、编辑、规则和总结 revision |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/MurmurnoteDatabase.kt` | 从 v5 升级，新增字段/词条/规则/记录/attempt 表和显式 migration；保留 schema export | 生产已有 Room，不能另建第二个 LocalAsrDatabase |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/di/DatabaseModule.kt` | 注册新 migration 和 `CorrectionDao` | 当前明确注册 1→5 migrations，不能遗漏新版本 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/ui/screen/home/HomeViewModel.kt` | 实时段先走同一本地 correction/validator，再把 corrected snapshot 增量交给 `updateDraftSummary` | 当前 raw 段一识别完就直接触发云端 draft summary |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/ui/screen/detail/DetailViewModel.kt` | 编辑 corrected 字段、展示 diff/撤销；重新总结读取已定稿 corrected revision | 当前编辑覆盖 raw，重新总结直接读取 `rawTranscript` |

### 7.2 SenseVoice Lab 后续建议修改

| 完整路径 | 后续改动 | 原因 |
|---|---|---|
| `/home/wzwys/work/Murmurnote_test/gradle/libs.versions.toml` | 后续添加 Room、KSP 及选定拼音库版本 | 当前没有数据库或拼音依赖；依赖和许可证先确认 |
| `/home/wzwys/work/Murmurnote_test/app/build.gradle.kts` | 后续启用 KSP/Room，添加 schema 导出和 androidTest migration 依赖 | Room 不能只靠新增 Kotlin 类 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/ui/LabUiState.kt` | 若在 Lab 验证：初始切段选择改为 Silero，应用确认后的参数；增加纠错 review state | 当前 UI 实际默认 Whole，且前后 padding 绑定 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/ui/LabViewModel.kt` | 拆出 pipeline/repository；按系统 Locale 创建默认配置；ASR 后依次调用 assembler、correction、repository | 当前 ViewModel 同时持有录音、导入、ASR、历史、导出等全部职责 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/ui/LabScreen.kt` | 若在 Lab 验证：显示原文、纠正文、结构化修改、撤销；生产模式不暴露能量 VAD | 当前只显示 `rawJoinedText`，且提供能量 VAD 与三种录音源 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/experiment/ExperimentRunner.kt` | 保留逐段 raw；把直接 `joinToString` 抽到 `TranscriptAssembler`；纠错在原始 CER 之后作为派生结果 | 这是当前最明确的纠错插入点 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/data/ExperimentJsonExporter.kt` | 若继续作为诊断导出：schema 升级并新增 correctedText、correctionRecords、algorithmVersion；保留 raw 字段 | 当前 schema v2 仅输出 raw；不要让 JSON 代替 Room |
| `/home/wzwys/work/Murmurnote_test/app/src/main/res/xml/data_extraction_rules.xml` | 引入 Room 后继续明确排除本地数据库备份；若产品需要同步，另立加密/同意设计 | 当前已防御性排除 database，原则可复用 |

### 7.3 可直接复用或小范围适配

| 完整路径 | 复用内容 |
|---|---|
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/asr/AsrEngine.kt` | 生产项目已有双模型统一接口；扩充 result/config provenance 即可，不新建第二套 ASR 抽象 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/asr/LocalAsrEngine.kt` | 已有 SenseVoice/Qwen 模型选择、bridge 池和 Qwen 单并发约束；保留模型生命周期策略 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/asr/SherpaBridge.kt` | 已有两种 sherpa config 与反射兼容层；在现有分支参数化能力，不把 Qwen 假装成 SenseVoice |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/domain/pipeline/AudioPipeline.kt` | 现有 convert→split→ASR→Room→summary 编排骨架；本地纠错插在 raw 持久化和 summary 之间 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/MurmurnoteDatabase.kt` | 已有 Room v5、schema export 和显式 1→5 migration；向下一版迁移，不另建数据库 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/local/entity/RecordingSegment.kt` | 已保存分段音频路径和起止时间，可作为 overlap 审计及二次识别的音频锚点 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/remote/llm/LlmClient.kt` | 保留现有 `extractItemsAuto/updateDraftSummary` 云端总结行为；只把输入改成有 revision 的定稿快照 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/AudioCapture.kt` | MIC、16 kHz mono PCM16、资源释放和时长上限；生产版需补录音索引/恢复/删除治理 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/AndroidAudioSegmenter.kt` | 已真机验证的 Silero v5 JNI 调用和参数映射；向生产项目做有来源的移植，不直接依赖 Lab package |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/NeuralVadSegmentPlanner.kt` | padding、合并、硬切和 overlap 的纯逻辑；优先清理成可复用纯 Kotlin 逻辑及测试 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/asr/RecognitionModels.kt` | 不可变 `RawRecognitionResult` 和现有 `RecognitionEngine` 边界 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/asr/SenseVoiceEngine.kt` | 单 recognizer、配置缓存、JNI 生命周期、原始结果映射 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/metric/TextMetrics.kt` | 对 raw/corrected 分别计算 exact 和 normalized CER 的测试工具 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/AudioFileDecoder.kt` | 导入会议音频的本地规范化入口 |

### 7.4 第一阶段不应修改

| 完整路径 | 原因 |
|---|---|
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/remote/llm/LlmClient.kt` | 保留云端总结/待办业务算法；Phase 1-2 只建立上游 snapshot 边界，不能让它参与纠错 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/remote/llm/LlmProvider.kt` | 云端 provider 选择与本地 ASR/纠错无关 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/asr/CloudAsrEngine.kt` | 本需求的会议基线是本地 ASR；不得为双本地模型兼容而重写现有云端 ASR，实现只需继续满足公共 `AsrEngine` 契约 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/com/k2fsa/sherpa/onnx/OfflineRecognizer.kt` | 生产项目 vendored 1.12.39 binding；MVP 通过已有 config，禁止局部改 binding 制造 ABI 不匹配 |
| `/home/wzwys/work/Murmurnote/app/src/main/jniLibs/arm64-v8a/` | 目录内 `jni/c-api/cxx-api/onnxruntime` 四件套保持成套；Qwen HR 升级必须另立完整升级阶段 |
| `/home/wzwys/work/Murmurnote/app/src/main/jniLibs/armeabi-v7a/` | 同上；禁止只替换一个 `.so` |
| `/home/wzwys/work/Murmurnote/app/src/main/jniLibs/x86/` | 同上；禁止只替换一个 `.so` |
| `/home/wzwys/work/Murmurnote/app/src/main/jniLibs/x86_64/` | 同上；禁止只替换一个 `.so` |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/asr/AsrModelUrls.kt` | 保持现有 SenseVoice/Qwen 下载规格与模型 ID；纠错不要求更换模型包 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/data/asr/AsrModelManager.kt` | 保持 `filesDir/asr_models/{model.id}` 的安装、校验与清理职责；不要把词典规则混进模型下载生命周期 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/com/k2fsa/sherpa/onnx/OfflineRecognizer.kt` | vendored 1.12.39 API；通过 config 使用，不在业务需求中修改上游 binding |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/com/k2fsa/sherpa/onnx/HomophoneReplacerConfig.kt` | API 已足够表达 HR；MVP 不启用 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/com/k2fsa/sherpa/onnx/Vad.kt` | vendored VAD binding |
| `/home/wzwys/work/Murmurnote_test/app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` | 已固定版本/hash，避免 JNI/API 不匹配 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/jniLibs/arm64-v8a/libonnxruntime.so` | 已验证运行库，不属于纠错改动 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/assets/asr_models/sense_voice_zh_en_ja_ko_yue/model.int8.onnx` | 保持同一 ASR 基线 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/assets/asr_models/sense_voice_zh_en_ja_ko_yue/tokens.txt` | 保持与模型匹配 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/assets/vad_models/silero_vad_v5/silero_vad.onnx` | 已选定神经 VAD，不更换模型 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/PcmWavReader.kt` | 与纠错无关，且有严格安全校验 |
| `/home/wzwys/work/Murmurnote_test/app/src/main/java/app/murmurnote/sensevoicelab/audio/FfmpegAudioConverter.kt` | 与文本纠错无关 |
| `/home/wzwys/work/Murmurnote/app/src/main/java/app/murmurnote/android/ui/screen/home/HomeViewModel.kt` 与 `DetailViewModel.kt` | Phase 1-2 不重写现有摘要体验；到 Phase 3 只调整 snapshot/revision 输入和纠错 UI |

## 8. 本地纠错与云端会议总结的边界

真实 Murmurnote 中应强制以下依赖方向：

```text
local audio → local ASR → immutable raw transcript
                           → deterministic local correction
                           → finalized transcript snapshot
                           → optional cloud summary（现有 LlmClient 调用链）
```

禁止方向：

```text
LlmClient / summary UI ─X→ CorrectionEngine
LlmClient / summary UI ─X→ rawText/correctedText 更新
LLM response           ─X→ correction rules / personal dictionary
cloud failure          ─X→ local transcript rollback or deletion
```

建议接口边界：

```kotlin
data class SummaryTranscriptSnapshot(
    val transcriptId: String,
    val correctionRevision: Long,
    val text: String,
)
```

- `TranscriptRepository` 在用户定稿后生成 snapshot；
- 生产项目不存在名为 `MeetingSummaryService` 的类；该名称只代表概念边界，实际消费者是 `LlmClient.extractItemsAuto/updateDraftSummary` 以及 `AudioPipeline/HomeViewModel/DetailViewModel`；
- 上述实际消费者只接收 snapshot，不接收 `CorrectionEngine`、DAO、原始音频或完整个人词典；
- 云端总结的网络同意、密钥、超时、重试和摘要存储继续由现有总结模块负责；
- 本地纠错模块自身不依赖 `INTERNET`、网络 client 或云端 provider；
- 总结失败不影响 raw/corrected 转写的保存和撤销；
- 若用户撤销纠错并产生新 revision，旧摘要标记为基于旧 revision，而不是悄悄覆盖原始转写。

当前 SenseVoice Lab 没有网络权限，Room/本地纠错阶段不应顺带添加 `INTERNET`。真实 Murmurnote 已有的网络权限和总结客户端保持在它原有模块内。

## 9. 分阶段实施计划

### Phase 0：冻结生产基线并关闭剩余决策

目标：以 `/home/wzwys/work/Murmurnote` 为实施目标，以当前 Lab 为 Silero/SenseVoice 验证参考；此阶段仍不写业务实现。

工作：

- 固定生产项目当前 Room v5 schema、SenseVoice 与 Qwen3-ASR 原始输出、分段边界、时延和摘要输入基线；
- 建立同时覆盖两个本地模型的离线 golden corpus，包括普通话、英文、夹杂英文、专有名词、重复、自我修正和 VAD 边缘；
- 确认尚未决定的后置 padding、非中英文 Locale fallback、语言手动覆盖、会议词条作用域、词表保留和录音保留策略；
- 记录 Lab 当前未提交的 Silero 实现来源与测试结果，后续只挑选所需逻辑移植，不能覆盖用户改动；
- 确认现有数据库中被用户编辑过的 `rawTranscript/TranscriptSegment.text` 无法还原原始模型输出时，迁移标记为 `LEGACY_PROVENANCE_UNKNOWN`，不得伪造 raw。

验收条件：

- 生产根目录、真实调用链和 Room 起始版本均已确认；
- 两模型 golden corpus 可重复运行，结果带 `engineType/modelId/config`；
- 所有仍标为“需要确认”的产品决策得到明确结论；
- 能证明本地纠错基线没有 LLM 或网络调用。

测试：双模型基线重复运行、Room v5 fixture 可读、现有摘要输入快照对照、依赖图/网络调用静态检查。

风险：旧版本已把用户编辑写回名为 raw 的字段，这部分历史证据可能不可逆丢失；只能如实标记，不能通过猜测恢复。

### Phase 1：统一 MIC、模型能力配置和神经 VAD

目标：先统一输入与切段，不加入文本纠错。

工作：

- 保留 `AudioRecorder.kt` 已使用的 `MediaRecorder.AudioSource.MIC`，增加回归测试，不再提供人声增强/系统噪声处理作为隐含前提；
- 从 Lab 的 sherpa-onnx 1.12.39 `Vad.kt`、Silero v5 资产校验和纯 Kotlin planner 中移植最小必需部分到生产项目；生产项目当前没有 VAD binding 或 Silero asset，必须同时补 binding、模型、license、hash 校验和四 ABI 兼容验证；
- 实时链路用 `StreamingNeuralVadSession` 替换 `AudioRecorder.isSpeechFrame()` 的 RMS 能量判断；停止/导入链路用 Silero ranges 替换 `AudioSplitter + VadDetector + ffmpeg silencedetect` 的语音边界判断，FFmpeg 只负责实际裁剪；
- 两条生产链路使用同一版本化 preset：threshold `0.5`、min speech `250 ms`、min silence `700 ms`、pre-padding `500 ms`、max segment `25 s`、hard-cut overlap `500 ms`；post-padding 按 Phase 0 结论；
- SenseVoice：`zh-* → zh`、`en-* → en`，默认 ITN ON；Qwen3-ASR：保留模型自动语言和模型原生标点/规范化，不向不存在的 language/ITN 字段硬塞 SenseVoice 配置；
- 保存 model-aware config snapshot；Phase 1 的 Qwen hotwords 为空，避免同时改变 VAD 和模型提示词。

验收条件：

- MIC 真机 smoke 仍报告 MIC、16 kHz、mono、PCM16；
- 实时录音和停止后完整转写都能证明使用 Silero，生产调用图不再由 `VadDetector`、RMS 阈值或 ffmpeg silencedetect 决定语音边界；
- 两条链路的同一段 PCM 得到符合 preset 的一致 speech ranges/切段，硬切段之间精确重叠 500 ms；
- SenseVoice 中文/英文 Locale 与 ITN 测试通过；Qwen 自动语言、原生标点和无 hotwords 基线通过；
- 四个现有 ABI 均通过 VAD/ASR native 装载检查，至少 arm64 真机跑完整 smoke。

测试：纯 planner 单测、Locale/capability 单测、MIC/streaming Silero/离线 Silero 真机 smoke、SenseVoice/Qwen 双模型回归、长语音压力与 AudioRecord 实时性测试。

风险：神经 VAD 不能阻塞录音写盘；500 ms overlap 会产生重复识别；系统 Locale 不等于实际讲话语言；post-padding 未确认会改变尾字保护。

### Phase 2：在现有 Room v5 上分离原文与派生文本

目标：先可靠保留首次 raw、模型/配置和音频位置，再允许任何纠错。

工作：

- 不新建第二个数据库；为 `MurmurnoteDatabase` 设计 v5→下一版本的显式 migration，继续导出 schema，禁止 destructive migration；
- 将旧 `Recording.rawTranscript` 和 `TranscriptSegment.text` 迁入 raw 基线，新建 corrected/revision/provenance 字段；已有用户编辑标记的数据若无法还原，保留现值并标记 legacy unknown；
- 新转写先原子保存逐段 `rawText`、start/end、sequence、`RecordingSegment.filePath` 音频锚点、cut reason、overlap、model ID 和 config fingerprint，再构造整篇 raw；
- 不假设 Qwen/SenseVoice 都有 token 时间戳：当前生产 `AsrResult` 只有 text/duration，MVP 以分段级时间范围为权威；未来若 bridge 暴露 tokens/timestamps，再作为可空证据保存；
- `TranscriptAssembler` 只对确有 500 ms 音频重叠且 suffix/prefix 完全一致的边界做可审计去重；无法证明时保留重复；
- 将现有“用户编辑覆盖 raw”的 DAO/Repository 路径改为只更新 corrected revision；run、segments、raw 和 config 在事务中提交。

验收条件：

- v5 数据迁移后会议、摘要、待办、音频和原有文本均未丢失；legacy provenance 状态可查询；
- 新产生的 raw 没有业务 update 路径，进程重建后 raw、segments、音频锚点和 config 可恢复；
- SenseVoice 与 Qwen 结果均能通过相同 repository 保存，并保留准确 model provenance；
- 数据库或文件故障不会产生“整篇有记录但分段/配置缺失”的半状态；
- 旧云端总结功能在本阶段行为保持不变。

测试：v5 migration fixture、DAO/transaction、旧编辑数据迁移、双模型 repository contract、文件/DB 半失败、process recreation、overlap assembler。

风险：旧 raw 已被覆盖时无法恢复；migration 与用户现有数据的组合多；Qwen 没有 token 时间戳，不能把分段时间冒充字级时间。

### Phase 3：可落地的最小纠错版本（MVP）

目标：完成一条模型无关的安全纵向路径：**用户确认精确规则 → 本地应用 → 保存 raw/corrected/records → 可撤销 → 摘要单向消费定稿快照**。

MVP 范围：

- `CorrectionEngine` 只应用用户确认的 `EXACT_TEXT` 短规则；输入可来自 SenseVoice 或 Qwen，但规则命中与结果记录必须带 source model/config；
- `CorrectionValidator` 执行非空、边界、冲突、非重叠、禁止插入/删除和可逆性检查；
- 保存 `rawText`、`correctedText`、correction revision 和每条 `CorrectionRecord`；UI 并排展示、标注来源并支持单条撤销；
- 用户编辑只生成候选规则，二次确认后才进入历史规则；复杂编辑仅保存本次人工 revision，不自动泛化；
- 不加入拼音自动候选、Qwen hotwords、native HR 或二次识别；
- `AudioPipeline` 在 raw 入库后运行本地纠错，再让现有 `LlmClient.extractItemsAuto` 读取明确 revision；`HomeViewModel.updateDraftSummary` 也只能消费版本化 snapshot；LLM 结果不得回写词典/规则/raw/corrected。

验收条件：

- 删除全部 active correction records 后可逐字恢复 raw；
- 纠错、DAO 或云端总结失败时 raw 仍可见且不会被回滚；
- 不删除口头语、重复、自我修正，不改语序或标点；
- 两模型的 raw/模型证据不因纠错启用而改变；
- correction 模块依赖图没有 LLM/network 包，summary 模块没有 correction DAO 写依赖；
- clean set 零新增自动错误，目标精确规则集有可复现改善。

测试：精确规则、冲突/重叠、Unicode code point、撤销/事务回滚、双模型相同 contract、summary revision、网络失败与原文不可变。

主要风险：机械替换误改同形词；因此 MVP 只接受用户明确确认且可限定 scope 的短规则。

### Phase 4：动态词汇、拼音候选与可选 Qwen hotwords

目标：先建立两个模型共用的确定性后处理，再单独评估 Qwen 的模型专用提示词能力。

共同后处理工作：

- 选择并审查 Android 拼音实现的许可证、APK 体积、多音字、繁简体和离线表现；
- 当前会议词条优先于全局词条，只自动应用“完整拼音匹配 + 候选唯一 + 至少两汉字 + 无冲突”；
- 多候选、单字、多音字不确定时只提示；记录 term/rule ID、source model、decision reason 和算法版本；
- 关闭/删除词条后从 raw 重算 corrected，不对上次 corrected 继续叠改。

Phase 4b（Qwen 专用、默认关闭）：

- 从当前会议中挑选用户明确提供的术语，通过 `OfflineQwen3AsrModelConfig.hotwords` 生成一次会话级 CSV snapshot 和 fingerprint；
- 为条数、总字符数和转义建立硬上限；上限由目标手机 A/B 决定，因为 hotwords 会占用 `maxTotalLen=512` prompt 预算；
- 将 config fingerprint 纳入 `LocalAsrEngine/SherpaBridge` cache key，整批分段复用 recognizer，禁止每段重载约 940 MiB 模型；
- 同时跑无 hotwords clean set 和目标术语集；若附近文本新增错误或总体指标退化，保持关闭；
- SenseVoice 不走此分支，不能把 Qwen hotwords 与 Transducer `hotwordsFile` 混用。

验收条件：

- 两模型的拼音后处理均满足唯一候选和 clean-set 零回归；
- Qwen hotwords 的每次运行都能还原词表 fingerprint/config，raw 明确表示“该配置下模型原始输出”；
- 热词变化只在批次边界触发 recognizer 重建，不发生每段模型 reload；
- Qwen 目标词指标改善且 clean set 无新增错误，才允许用户显式开启。

测试：拼音 provider/validator 单测、双模型 correction contract、目标术语/冲突/中英混合 golden；Qwen hotwords OFF/ON 的输出、prompt 预算、cache key、模型重建次数、时延和峰值内存 A/B。

风险：多音字和同音候选；拼音库许可证/体积；Qwen prompt 预算被过多热词挤占；热词可能改善目标词却扰动邻近内容。

### Phase 5：可选静态 Homophone Replacer A/B

目标：只评估静态、低歧义术语，不默认进入生产。

工作：

- 在当前 sherpa-onnx 1.12.39 上只给 SenseVoice 做 HR-off/raw 与 HR-on/derived 双路 A/B，固定 lexicon、单个审核过的 `replace.fst`、license、版本、大小和 SHA-256；
- Qwen 1.12.39 明确跳过 native HR，因为实现不会调用 replacer，不能把“配置成功”当成“功能生效”；
- 若未来确需 Qwen native HR，另立 sherpa-onnx v1.13.4+ 升级任务，成套更新 Kotlin binding、四 ABI sherpa/ONNX Runtime 兼容组件并回归 SenseVoice、Qwen、Silero；Qwen 模型包本身不需因此更换；
- 比较 text 与原 token 证据、CER、延迟、内存和冷启动；若不能完整保留 raw 或 clean set 回归，继续使用 Kotlin 后处理。

验收条件：

- canonical raw 永不被 HR 覆盖，每条变化有记录或可复现 diff；
- SenseVoice 真实 JNI 测试能证明 HR 生效；Qwen 1.12.39 测试明确为 unsupported/skip，而不是假通过；
- 不把 Room 动态规则运行时编译成 FST；
- 任何 runtime 升级都通过四 ABI 装载和双 ASR + VAD 端到端回归。

测试：SenseVoice HR-off/HR-on 真实 JNI A/B、raw/derived diff、缺失或损坏资产 fail-fast、clean set；Qwen 1.12.39 unsupported contract，若升级则追加四 ABI 和双模型/VAD 回归。

风险：HR text 与 token 证据不一致；双路识别增加时延；FST 无法承载动态词汇；局部升级 native 组件会产生 ABI 崩溃。

### Phase 6：VAD 边缘与漏字的二次识别

目标：处理文本后处理无法解决的漏字、切断和空段问题。

工作：

- `ReRecognitionEngine` 按 `RecordingSegment` 音频范围扩大边界、合并相邻段或重切；
- 默认复用首次 attempt 的 model ID 和完整 config；用户切换 SenseVoice/Qwen 时作为新的显式 attempt 保存；
- 每次 attempt 保存音频范围、触发原因、模型/config、raw 结果和用户选择，不覆盖首次 raw；
- 自动触发条件先仅记录/提示，成熟后再决定是否启用。

验收条件：

- 给定相同 recording/range/model/config 可重复得到同一来源清晰的 attempt；
- 首次 raw、后续 raw 和选择记录均可追踪；
- 删除原始音频前明确提示二次识别将不可用；
- 文本纠错模块不补写或猜测漏失内容。

测试：固定音频范围的确定性重跑、跨 VAD 边缘扩窗、相邻段合并、双模型 attempt provenance、原音频缺失、attempt 选择与撤销。

风险：额外耗时、电量和存储；不同模型结果的选择策略；音频保留的隐私治理。

## 10. 项目级测试矩阵和质量门槛

### 10.1 必测语料

以下每类语料至少分别运行 SenseVoice、Qwen3-ASR；Qwen hotwords 阶段再增加 hotwords OFF/ON 配对，不能只看一个模型的平均结果。

- 清晰普通话，无专有名词；
- 人名、公司名、技术词、缩写；
- 中文夹英文；
- 数字、日期、金额和单位（验证 ITN 保持）；
- “嗯、啊”、重复内容和说话者自我修正；
- 同拼音不同词、多音字和单字；
- 25 秒连续讲话产生硬切与 500 ms overlap；
- 语音刚好落在 VAD 段首/段尾；
- 纯静音、背景噪声和无语音；
- 英文系统界面与中文系统界面。

### 10.2 指标

每次发布按 `modelId + modelVersion + VAD preset + language/ITN capability + hotword fingerprint` 分组报告，不能把两模型或不同提示词配置混为一个均值：

- `raw exact CER`、`raw normalized CER`；
- `corrected exact CER`、`corrected normalized CER`；
- 目标术语纠正成功率；
- 自动纠正 precision 和用户撤销率；
- clean set 新增错误数，门槛为 `0`；
- VAD 分段耗时、ASR decode RTF、纠错耗时；
- Room 写入/恢复耗时和数据库体积；
- 因 overlap 去重、精确规则、拼音、Qwen hotwords、HR 产生的变更数量，按 source 分开统计；
- Qwen hotwords OFF/ON 的目标词命中率、clean-set 扰动数、recognizer 重建次数、峰值内存和 prompt 字符预算。

### 10.3 回归门槛

- raw text 及模型实际提供的 tokens/timestamps 证据不因后处理纠错启用而改变；当前 Qwen/生产 bridge 没有字级时间戳时，只要求分段音频范围不变且不可伪造字级时间；
- 关闭纠错后结果逐字等于 raw；
- 任一异常都回退 raw，而不是返回空文本或半成品；
- clean set 不允许出现新增自动纠错错误；
- 无网络时录音、VAD、ASR、纠错、保存和撤销全部可用；
- 云端总结失败不影响本地转写；
- JVM tests、Android lint、debug build、Room migration androidTest、MIC/Silero/SenseVoice/Qwen 真机 smoke 全部通过；
- sherpa-onnx 或 native 组件若升级，arm64-v8a、armeabi-v7a、x86、x86_64 四 ABI 均完成装载检查，arm64 完成双 ASR + VAD 端到端回归。

当前 README 中记录的测试数量/APK hash 已落后于工作树中现有构建产物，实施前应重新运行质量门并更新事实，不能把旧 README 数字当作当前验证结果。

## 11. 风险总表

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 旧代码已把用户编辑覆盖到 `rawTranscript/TranscriptSegment.text` | 高 | migration 不伪造原始输出；无法恢复的历史行标记 `LEGACY_PROVENANCE_UNKNOWN` |
| 实时与停止后链路仍各自使用能量/静音检测 | 高 | 同一 Silero preset 和 planner；分别做 streaming/offline 端到端测试；生产依赖图移除能量 VAD |
| 生产项目当前缺少 `Vad.kt` 与 Silero asset | 高 | 从同版本 Lab 验证实现移植；固定 license/hash；四 ABI 装载与真机 smoke |
| 纠错覆盖原始结果 | 高 | raw 字段不可更新；派生 corrected + records；事务和重建测试 |
| 机械同音替换造成语义错误 | 高 | 用户确认优先、唯一候选、两字以上、冲突即拒绝、clean set 零回归 |
| 500 ms overlap 产生重复字 | 高 | 单独 `TranscriptAssembler`；只做可证明 suffix/prefix 去重并记录 source |
| 把说话重复误删为 overlap | 高 | 只有音频范围确实重叠且文本边界完全匹配才去重；否则保留 |
| Qwen 1.12.39 配置 HR 却实际不生效 | 高 | 明确 capability=unsupported；当前仅 SenseVoice A/B；Qwen 要等 v1.13.4+ 成套升级后重验 |
| HR text 与 tokens/timestamps 不一致 | 高 | MVP 关闭 HR；独立双路 A/B；无法保留 raw 就不采用 |
| 为 Qwen HR 只替换某个 binding/`.so` | 高 | 禁止局部升级；Kotlin binding、四 ABI native 组件和 ONNX Runtime 兼容性成套验证 |
| Qwen hotwords 占用 prompt 或扰动邻近文本 | 高 | 当前会议小词表、硬预算、会话级 fingerprint、OFF/ON 目标集与 clean set A/B |
| 拼音多音字和同音候选 | 高 | 显式读音、候选唯一、冲突时只提示 |
| Room v5 migration 破坏既有会议/摘要/待办 | 高 | 用真实 v5 schema fixture 设计下一版 migration；禁止 destructive migration；升级前后数据对照 |
| 录音文件无限增长/孤儿文件 | 高 | RecordingRecord、容量和保留策略、恢复扫描、tombstone 删除 |
| 二次识别所需音频已删除 | 中 | 删除前提示；recording 状态与 attempt 能力关联 |
| ViewModel 继续承担全部职责 | 中 | 拆出 assembler、engines、repository，UI 只编排状态 |
| Qwen 词表变化导致频繁重载约 940 MiB 模型 | 高 | 会话开始冻结词表；fingerprint 进入 cache key；批内复用，禁止逐段重建 |
| 云端总结反向污染纠错 | 高 | 单向 snapshot 接口；无 DAO/CorrectionEngine 依赖；revision 追踪 |
| 无 ASR posterior 却声称“概率置信度” | 中 | 使用可解释 `confidenceTier + decisionReason`，不伪造模型分数 |

## 12. 按执行顺序排列的任务清单

### Phase 0：生产基线和剩余决策

- [ ] 以 `/home/wzwys/work/Murmurnote` 为正式实现仓库，复核本计划列出的录音、双 ASR、Room v5、转写和 `LlmClient` 摘要调用链。
- [ ] 确认 post-padding、非中英文 Locale fallback、语言手动覆盖、词条 scope、词表保留和录音保留策略。
- [ ] 导出当前 Room v5 schema 和一份匿名化 migration fixture，覆盖未编辑/已编辑转写、摘要、待办和分段音频记录。
- [ ] 分别为 SenseVoice 与 Qwen3-ASR 冻结逐段 raw、分段范围、model/config、CER、时延和现有摘要输入基线；不存在的 token/字级时间戳字段不得伪造。
- [ ] 建立双模型 golden corpus 和独立 clean set，并保存可重复运行方法。
- [ ] 记录 `/home/wzwys/work/Murmurnote_test` 中拟复用 Silero 文件的来源、版本和未提交改动归属，移植时不得覆盖用户工作。

### Phase 1：固定输入和 VAD 基线

- [ ] 为生产 `AudioRecorder.kt` 已使用的 MIC 增加 16 kHz/mono/PCM16 真机回归测试，不引入录音前端增强假设。
- [ ] 将与 sherpa-onnx 1.12.39 匹配的 `Vad.kt`、Silero v5 ONNX、LICENSE、固定 SHA-256 和可重复准备脚本加入生产项目。
- [ ] 在四个现有 ABI 上验证 VAD binding 与 native 库兼容，并在 arm64 真机完成模型加载/释放 smoke。
- [ ] 实现唯一版本化 VAD preset：`0.5 / 250 ms / 700 ms / pre 500 ms / 25 s / overlap 500 ms` 加已确认 post-padding。
- [ ] 实现纯 Kotlin `NeuralVadSegmentPlanner` 及 padding、合并、硬切、overlap 边界单测。
- [ ] 用 `StreamingNeuralVadSession` 替换 `AudioRecorder.isSpeechFrame()` 能量判断，确保 VAD 不阻塞 AudioRecord 写盘。
- [ ] 用 Silero ranges 替换 `AudioSplitter/VadDetector/ffmpeg silencedetect` 的语音边界决策，FFmpeg 仅保留物理裁剪职责。
- [ ] 确认生产实时与停止后转写调用图均没有能量 VAD 参与最终语音边界。
- [ ] 实现 model-aware `AsrRecognitionConfig`：SenseVoice 按中文/英文 Locale 配 language 且 ITN ON；Qwen 保持 auto-language、模型原生标点且 hotwords 为空。
- [ ] 运行 Locale/capability、MIC、streaming/offline Silero、SenseVoice/Qwen 双模型回归并冻结新基线。

### Phase 2：原始转写和持久化

- [ ] 基于真实 Room v5 schema 设计并审查到下一版本的显式 migration，继续 schema export，禁止 destructive migration。
- [ ] 为 `Recording` 与 `TranscriptSegment` 增加 raw/corrected/revision/provenance；将旧字段迁作基线，无法还原的已编辑行标记 `LEGACY_PROVENANCE_UNKNOWN`。
- [ ] 为 `RecordingSegment` 增加或关联 cut reason、overlap、VAD preset 和 ASR config fingerprint，同时保留现有 filePath/start/end 音频锚点。
- [ ] 扩充 `AsrResult` 的 model/config provenance；tokens/timestamps 仅在模型和 bridge 实际提供时作为可空证据保存。
- [ ] 实现 `TranscriptAssembler.kt` 的普通拼接，不先做任何推测性去重。
- [ ] 为 500 ms overlap 实现“可证明的 exact suffix/prefix”对齐和独立审计记录；不确定时保留全部文本。
- [ ] 实现 `TranscriptRepository`，在同一事务保存 segments、首次 raw、整篇 raw、音频范围和 model/config；raw 不提供业务更新 API。
- [ ] 改造 `RecordingDao/RecordingRepository`，让后续用户编辑只生成 corrected revision，不再覆盖 raw。
- [ ] 验证 v5 migration、旧编辑数据、双模型 repository contract、进程重建、数据库故障和文件/DB 半失败场景。

### Phase 3：精确规则 MVP

- [ ] 定义 `CorrectionModels.kt`、source、confidence tier、decision reason 和 status。
- [ ] 实现 `CorrectionEngine` 接口与只支持用户确认 `EXACT_TEXT` 的 `DeterministicCorrectionEngine`。
- [ ] 实现 `CorrectionValidator` 的边界、冲突、非重叠、禁止空替换和可逆性验证。
- [ ] 创建词条/历史规则 DAO，加入 scope、启停、唯一索引和反向冲突检查。
- [ ] 在一个事务中保存 corrected snapshot 与结构化 `CorrectionRecord`，raw 字段不可更新。
- [ ] 实现单条撤销和“从 raw + active records 重建 corrected”的测试。
- [ ] 实现用户编辑的代码点安全 diff；复杂 diff 只保存本次手动编辑，不自动生成规则。
- [ ] 增加规则二次确认 UI，未经确认的候选不得启用。
- [ ] UI 并排显示 raw/corrected、修改来源和撤销入口。
- [ ] 在 `AudioPipeline` 中把纠错放到 raw 持久化之后、`LlmClient.extractItemsAuto` 之前，摘要只读取带 revision 的 `SummaryTranscriptSnapshot`。
- [ ] 让 `HomeViewModel.updateDraftSummary` 与 `DetailViewModel` 重新总结读取明确 revision；不得向 Correction DAO、raw 或 corrected 回写 LLM 结果。
- [ ] 用依赖测试证明纠错模块不依赖网络/LLM，云端总结失败不会回滚本地转写。
- [ ] 分别用 SenseVoice/Qwen 通过 clean set 零回归、精确规则命中、撤销、事务失败回 raw 和断网本地端到端测试。

### Phase 4：本地拼音与动态会议词汇

- [ ] 评估拼音库的许可证、APK 体积、离线性、多音字、繁简体和 Android 兼容性，并记录选择依据。
- [ ] 实现 `PinyinCandidateProvider` 和拼音规范化测试。
- [ ] 支持会议词条/全局词条及显式 pronunciation variants。
- [ ] 实现“完整拼音匹配 + 候选唯一 + 至少两汉字 + 无冲突”自动规则。
- [ ] 多候选、单字和多音字不确定时只提示用户，不自动改。
- [ ] 记录 term ID、算法版本、decision reason、useCount 和撤销状态。
- [ ] 对 SenseVoice/Qwen 的专有名词、普通文本、中英混合、多音字和同拼音冲突语料分别跑 raw/corrected CER 对照。

### Phase 4b：Qwen 模型专用 hotwords（可选，默认关闭）

- [ ] 实现 `QwenHotwordPolicy`，仅接受当前会议中用户明确提供的词条，生成有稳定排序和转义规则的 CSV snapshot/fingerprint。
- [ ] 根据目标手机基准确定条数与总字符硬上限，记录其占用 `maxTotalLen=512` prompt 预算的影响。
- [ ] 在 `SherpaBridge` 的 Qwen config 中传专用 hotwords；SenseVoice 和 Transducer `hotwordsFile` 路径保持不变/禁用。
- [ ] 将 hotword fingerprint 纳入 bridge cache key，在会议/批次边界重建并整批复用，禁止每段重载 Qwen 模型。
- [ ] 对 hotwords OFF/ON 跑目标词集、clean set、附近文本扰动、时延和峰值内存 A/B；未同时满足改善与零新增错误时保持关闭。

### Phase 5：可选 Homophone Replacer

- [ ] 准备并审核通用 lexicon、静态规则源和离线生成 `replace.fst` 的可复现脚本。
- [ ] 固定 HR 资产许可证、版本、大小和 SHA-256，不接受运行时动态 FST。
- [ ] 在 sherpa-onnx 1.12.39 上仅为 SenseVoice 实现 HR-off raw 与 HR-on 派生结果的独立 A/B，不覆盖 canonical raw。
- [ ] 将 Qwen 1.12.39 标记为 native HR unsupported/skip，增加防止“配置了但未生效”的 capability 测试。
- [ ] 验证 HR text 与 tokens/timestamps 的对应关系并记录限制。
- [ ] 若决定让 Qwen 使用 native HR，另开 sherpa-onnx v1.13.4+ 升级任务，成套升级 Kotlin binding、四 ABI native 组件并回归 SenseVoice/Qwen/Silero；不更换 Qwen 模型包。
- [ ] 只有在 clean set 零回归、raw 完整保留且时延可接受时才决定是否启用；否则保留模型无关的 Kotlin 确定性后处理。

### Phase 6：二次识别

- [ ] 定义 `ReRecognitionEngine` 和 `RecognitionAttempt`，保存 model ID/config，不修改首次 raw。
- [ ] 实现按 start/end sample 重新取音频、扩大边界、合并相邻段和重新切分。
- [ ] 将 VAD edge、疑似漏字和用户手动请求作为明确 reason 保存。
- [ ] 默认复用首次 attempt 的 ASR 模型/config；切换 SenseVoice/Qwen 时保存为新的显式 attempt。
- [ ] 实现 attempt 选择/撤销以及原始音频已删除时的明确错误。
- [ ] 验证二次识别时延、电量、存储和结果可重复性。

### 最终质量门

- [ ] 运行全部 JVM tests、Android lint、debug build 和 Room migration tests。
- [ ] 在 arm64 真机运行 MIC、streaming/offline Silero、SenseVoice ITN、Qwen auto-language、Room 恢复和双模型本地纠错端到端测试。
- [ ] 按 model/config/source 分别报告 raw/corrected CER、术语命中率、自动纠错 precision、撤销率、时延和 clean-set 新增错误数。
- [ ] 验证断网时本地全链路可用，云端总结失败不会影响本地转写。
- [ ] 验证最终变更没有覆盖原始 text 或模型实际提供的 tokens/timestamps，也没有引入 ASR/纠错 LLM 依赖。
- [ ] 若 native runtime 有升级，验证四 ABI 装载以及 arm64 的 SenseVoice/Qwen/Silero 完整回归。
- [ ] 人工审核计划中的所有“需要确认”项均已关闭，并在开始实现前再次获得确认。

---

本计划的最小可落地版本截止到 **Phase 3**：MIC + Silero VAD + model-aware 双 ASR 配置（SenseVoice Locale/ITN；Qwen auto-language/原生标点）+ raw/corrected 分离持久化 + 用户确认的精确本地规则 + 结构化修改记录 + 撤销 + 云端摘要单向消费 revision。拼音候选、Qwen hotwords、native Homophone Replacer 和二次识别均在安全基线稳定后逐步加入。
