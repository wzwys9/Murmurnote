# Implementation Plan: Local ASR and deterministic correction

## Current Slice: 个性化自学习纠错（代码、APK 与真机验收完成，2026-07-11～12）

本轮以 `SELF_LEARNING_CORRECTION_SPEC.md` 为准。先完成研究、隐私边界和规格确认；用户确认
前不修改运行时代码。实现阶段遵循“失败测试 → Room v8 学习存储 → 受约束 LLM → 统一流水线
→ 实验室管理 UI → 完整验证”。代码与无设备验证于 7 月 11 日完成；7 月 12 日经用户授权，
补齐四 ABI APK、Android 16 真机 instrumentation 和端到端体验门槛。

### Task A: 用户反馈与候选契约

- 只从用户明确保存的单区间替换学习；保存修改与网络学习解耦。
- 使用 Android ICU 生成规范化拼音，拼音/形似只作候选信号，不能直接替换。
- 定义默认 KEEP、候选/上下文上限、冲突和负反馈停用规则。

### Task B: Room v8 个性化知识

- 复用 `correction_rules` 并增加 `CONTEXTUAL_LLM` 模式。
- 新增 profile/event 表、DAO、无损 v7→v8 migration、schema 和迁移测试。
- 上下文随来源录音删除，聚合词对由用户在管理页独立控制。

### Task C: 受约束 LLM 与流水线

- LLM 只返回代码提供的候选 ID 和固定决定；本地严格校验后才应用。
- 网络失败不影响 raw/ASR；应用产生独立修订和审计。
- 录音与导入音频共用同一后处理路径；用户反向修改立即形成负反馈。

### Task D: 实验室体验与验证

- 新增默认关闭且受 API 门槛保护的独立开关、首次隐私说明和学习管理页。
- 先写领域/Repository/迁移/流水线/UI 状态测试，再跑完整 JVM、Lint、Release 和真机门槛。

### Verification result

- `:app:testDebugUnitTest`：254 tests，0 failures / errors / skipped。
- `:app:compileDebugAndroidTestKotlin`：通过；Room v7→v8、DataStore、Repository 和 Android ICU
  instrumentation 测试已编译。
- Android 16 arm64 真机 instrumentation：49 tests 全部通过。真机首次运行暴露出
  `MigrationTestHelper` 原始连接未执行 Room `onConfigure` 的夹具差异；测试显式启用外键后，
  v7→v8 删除录音级联清理上下文事件的断言通过。
- `:app:lintDebug`：通过，0 issues。
- `:app:assembleDebug` 与 `:app:assembleRelease`：四 ABI 构建通过。Debug v2 签名校验通过；
  未提供发布凭据时 Release 保持 unsigned，没有回退到 debug 签名。
- 小米 `23116PN5BC`（Android 16）冒烟通过：首次引导与权限、录音振幅、暂停/恢复、后台与
  熄屏持续录音、前台服务与 wake lock 释放、SenseVoice 下载安装、失败录音重试、20 段
  离线转写落库，以及实验室/API 门槛/学习管理空态。

### Approval checkpoint

- [x] 阅读现有纠错、LLM、Room、设置和流水线边界。
- [x] 调研中文纠错、ASR 个性化、拼音/字形融合和过度纠错的一手资料。
- [x] 写出规格、威胁模型、资源上限和验收标准。
- [x] 用户确认 `SELF_LEARNING_CORRECTION_SPEC.md`。
- [x] 确认后进入 TDD 实现；确认前不修改运行时代码。

## Current Slice: 安全与架构加固（2026-07-11）

本轮修复全量代码审计中已确认的阻塞项，不改变用户数据语义。
按“先失败测试、再最小修复、最后真机验证”推进，并按用户后续要求拆分为独立 Git 提交。

### Task A: 不可信文件与归档边界

- 模型 tar.bz2 解压拒绝绝对路径、目录穿越、符号/硬链接和特殊条目。
- 限制下载字节、归档条目数量、单条目大小和总解压大小；失败时只留下可清理的暂存区。
- SHA256 不一致时删除下载文件并拒绝安装，不保留任何“忽略校验”旁路。
- 外部音频导入统一在 IO 调度器执行，并限制实际读取字节及并发导入数量。

### Task B: 词本仓库边界

- 录音级规则 API 只查询和修改当前录音规则，不能创建、复用或切换全局规则。
- 全局词本只允许通过带策略校验的专用 API 修改；总开关关闭时不影响录音级流程。
- 拒绝词条中的 Unicode 控制、格式化和双向文本控制字符。

### Task C: 原生任务与远端响应资源上限

- FFmpeg 调用统一为可取消挂起接口，协程取消时同步取消对应原生会话。
- LLM JSON 请求设置每次调用时限，限制响应体和可发送转录文本规模，避免尾块无限合并。

### Task D: 配置与发布保护

- 用真实 DataStore 集成测试覆盖“无 API 不可开启、清 Key/换未配置服务自动关闭”。
- Release 缺少发布证书时不再静默回退到 debug 签名。
- 完成 JVM、Android instrumentation、Lint、Release 构建和真机安装验证。

## Current Slice: 稳妥词本 MVP（2026-07-11）

本轮实现以 `SAFE_LEXICON_SPEC.md` 为准，只管理用户明确创建的全局 `EXACT_TEXT` 规则，
复用现有 Room 表、Repository、修订和审计机制，不启用模型热词、拼音或历史重写。
总开关默认关闭；关闭时全局词条不进入定稿规则查询，不改变既有处理链路。
当前 LLM 供应商还必须配置 API Key，否则开关不可用且运行时强制按关闭处理。

### Task A: 词条安全策略

- 用纯 Kotlin 策略规范化并校验 2–32 个 Unicode code point 的精确映射。
- 拒绝空值、相同映射、控制字符；先写失败测试。

### Task B: 全局规则事务 API

- 增加全局规则查询、创建、启停和删除。
- 重复映射幂等复用；冲突和反向映射明确失败；不允许词本操作录音级规则。

### Task C: 设置页管理体验

- 增加“实验室功能”目录；实验室页承载默认关闭的词本总开关和管理入口。
- 词本管理页提供空态、添加表单、启停操作和删除确认。
- 页面明确说明只影响未来最终转录，不修改 raw 或历史内容。

### Checkpoint

- 相关测试逐片通过；完整 JVM 测试、Lint、Release 构建和 arm64 真机检查通过。
- 已按用户后续要求分笔提交 Git。

## Overview

Implement the confirmed Phase 0–3 slice: SenseVoice-first local ASR, versioned Silero VAD, immutable model transcripts, derived corrected revisions, user-confirmed exact rules, revision-aware summaries, and privacy-safe retention/diagnostics. Phase 4+ pinyin, Qwen hotwords, native homophone replacement, and re-recognition remain disabled until measured separately.

## Architecture Decisions

- New installs default to local SenseVoice; completed legacy installs without an explicit engine preference remain on cloud GLM. Cloud ASR remains selectable and implements the same provenance and transcript contracts.
- A recognition attempt freezes engine, model, language/ITN, VAD preset, and optional vocabulary before decoding. Live text is preview data; a final transcript is promoted only from a complete, fingerprint-matching attempt.
- Model output is immutable `rawText`; all automatic and manual changes create a new corrected revision. User-facing display/search/export/summary use the latest corrected revision, while JSON/audit surfaces retain raw.
- One `Recording` is one meeting/session in the MVP. Rules default to recording scope and require explicit promotion to global scope. Rules never rewrite historical recordings silently.
- Cloud summaries consume an explicit transcript revision. Later edits mark summaries stale; only an explicit user action regenerates them.
- Transcript/correction records persist. Source audio expires after 30 days unless pinned; derived temporary files are cleaned after successful processing. No transcript bodies are retained in production diagnostics or system backup.

## Task 1: Define pure contracts and deterministic logic

**Description:** Add versioned ASR/VAD provenance contracts, exact replacement diff/validation, and a pure neural-VAD segment planner before touching persistence or JNI code.

**Acceptance criteria:**
- [x] SenseVoice and Qwen options cannot be mixed by type.
- [x] Config fingerprints are stable for identical snapshots and change for behavior-affecting fields.
- [x] Exact correction logic never mutates raw input, rejects overlap/conflicts, and preserves arbitrary manual edits as non-reusable revisions.
- [x] VAD planner implements 500 ms pre/post padding, 25 s maximum, 500 ms hard-cut overlap, and 200 ms final-segment filter.

**Verification:**
- [x] New JVM tests fail before implementation and pass afterward with `./gradlew :app:testDebugUnitTest`.

**Dependencies:** None

**Files likely touched:** `data/asr/AsrRecognitionConfig.kt`, `domain/correction/*`, `audio/NeuralVadSegmentPlanner.kt`, matching JVM tests.

**Estimated scope:** Medium

## Task 2: Add a lossless Room v5 to v6 migration

**Description:** Add raw/corrected/revision/provenance fields and correction audit tables without destructive fallback or duplicate transcript sources.

**Acceptance criteria:**
- [ ] Existing unedited and edited rows survive v5 to v6 without byte loss.
- [ ] Legacy edited rows are marked `LEGACY_PROVENANCE_UNKNOWN`; no historical raw output is fabricated.
- [ ] Transcript segments have a unique `(recordingId, sequence)` constraint.
- [ ] Missing migrations fail closed instead of deleting user data.

**Verification:**
- [ ] Room schema export is generated.
- [ ] Migration instrumentation test covers edited and unedited fixtures.
- [ ] Debug build succeeds.

**Dependencies:** Task 1 contracts

**Files likely touched:** Room entities/DAOs/database/DI, `app/schemas`, `app/src/androidTest`.

**Estimated scope:** Medium

## Task 3: Introduce TranscriptRepository and correction revisions

**Description:** Move transcript writes and edits behind transactions that expose no business API for updating raw text.

**Acceptance criteria:**
- [ ] Model segments are inserted idempotently and never overwritten by manual edits.
- [ ] Manual edits update corrected fields, increment revision, write a revision snapshot and audit record in one Room transaction.
- [ ] Exact confirmed rules apply only to new/current processing and can be reverted from raw.

**Verification:**
- [ ] Repository/engine JVM or Room integration tests pass.
- [ ] Existing transcript-edit behavior remains available with arbitrary corrected text.

**Dependencies:** Task 2

**Files likely touched:** `TranscriptRepository.kt`, transcript/correction DAOs, pipeline and detail ViewModel call sites.

**Estimated scope:** Medium

## Checkpoint: Storage and correction foundation

- [ ] Unit tests pass.
- [ ] Debug APK compiles.
- [ ] A v5 fixture upgrades without destructive recreation.
- [ ] Raw text has no user-edit update path.

## Task 4: Freeze ASR sessions and make local-first defaults explicit

**Description:** Snapshot engine/model/language settings per attempt, parameterize SenseVoice language/ITN, preserve Qwen auto-language, and migrate onboarding defaults safely.

**Acceptance criteria:**
- [ ] New installs select local SenseVoice; legacy completed installs retain cloud unless explicitly changed.
- [ ] Missing local models produce an explicit action instead of silent cloud fallback.
- [ ] Non-zh/en locales use auto; the user can choose system/auto/manual language.
- [ ] Cloud and both local models persist provenance.

**Verification:**
- [ ] Locale/default/fingerprint unit tests pass.
- [ ] Existing engine-type tests remain green.

**Dependencies:** Task 1

**Files likely touched:** preferences, ASR provider/engine/bridge, onboarding/settings.

**Estimated scope:** Medium

## Task 5: Replace production energy segmentation with Silero v5

**Description:** Vendor the matching 1.12.39 VAD binding and 2.3 MiB model, then integrate a bounded-memory offline detector and a non-blocking live session using the shared planner.

**Acceptance criteria:**
- [ ] Production speech boundaries no longer depend on RMS or ffmpeg silencedetect.
- [ ] VAD failure is explicit and never silently falls back to energy VAD.
- [ ] MIC capture remains 16 kHz mono PCM16 and is never blocked by neural inference.
- [ ] No automatic text de-duplication occurs without timestamp evidence.

**Verification:**
- [ ] Planner tests and debug build pass.
- [ ] Four ABI native libraries package successfully; arm64 smoke remains a manual/device gate.

**Dependencies:** Task 1

**Files likely touched:** vendored `Vad.kt`, VAD asset/license, audio detector/planner/recorder/splitter.

**Estimated scope:** Large, split into offline and live increments

## Task 5c: Refine forced cuts with a bounded secondary Silero probe

**Description:** Keep the normal Silero preset unchanged. Only when a canonical segment would hit
the 25-second hard limit, analyze a bounded window around a five-second lookback with a stricter
speech threshold and shorter silence duration, then move the cut to a reliable pause when one is
available. The last 200 ms before the hard limit is deliberately guarded, so the selectable range
is 4.8 seconds (`T-5.0s` through `T-0.2s`). This shared offline splitter is used by both stopped
recordings and imported audio; live preview remains provisional.

**Acceptance criteria:**
- [x] Probe only impending hard cuts, using a 5-second lookback, a 200 ms end guard, at most 500 ms
      warm-up, and 500 ms look-ahead; ordinary naturally split segments pay no second-pass cost.
- [x] Read no more than 6 seconds of source PCM for a full probe. The final neural-VAD frame may
      contain at most 511 zero-padding samples, but it never consumes source audio past the window.
- [x] Probe preset uses threshold `0.65` and minimum silence `200 ms`; candidates must be bounded
      by detected speech, be at least 200 ms, and are ranked by duration then latest position.
- [x] For a selected cut `T`, the prior slice ends at `T` and the next begins at `T-500 ms`, so
      source audio has no gap. If no candidate is valid, the existing 25-second hard cut remains.
- [x] Probe parameters participate in cache/provenance identity so old slices are never reused.

**Verification:**
- [x] JVM tests cover candidate selection, fallback, iterative hard-cut deadlines, PCM window
      seeking, and the no-gap/500 ms overlap invariant.
- [x] Full unit tests and lint pass; no APK is assembled until explicitly requested.

**Dependencies:** Task 5 offline Silero VAD and deterministic segment planner

**Files likely touched:** hard-cut probe policy/tests, PCM WAV reader/tests, Silero detector,
segment planner/tests, audio splitter, segment cache and ASR provenance call site.

**Estimated scope:** Medium

## Task 5d: Keep microphone capture alive with the screen off

**Description:** Start a user-visible microphone foreground service directly from the visible
record-button action and hold a bounded partial wake lock while `AudioRecord` is active. Keep the
service through pause, then release it on stop, cancel, startup rollback, or capture failure.

**Acceptance criteria:**
- [x] Manifest declares `FOREGROUND_SERVICE_MICROPHONE`; the non-exported service declares the
      `microphone` foreground type and promotes itself with the matching service type.
- [x] The service starts while the activity is visible and `RECORD_AUDIO` is granted, before any
      asynchronous startup wait that could outlive the visible user action.
- [x] A partial wake lock has a finite timeout and is released with the foreground notification on
      every normal and exceptional exit; pause intentionally keeps the recording session alive.
- [x] Cancellation during startup cannot leave an orphaned recorder, foreground service, or draft.

**Verification:**
- [x] JVM tests cover idempotent/retryable service leases and the manifest contract.
- [x] Full unit tests and lint pass; no APK is assembled until explicitly requested.
- [ ] Lock-screen capture remains a device gate after APK packaging is explicitly requested.

**Dependencies:** Existing `AudioRecorder` and recording-controller flow

**Files likely touched:** recording foreground service/session, manifest, notification channel,
recording controller, home ViewModel, matching JVM tests.

**Estimated scope:** Medium

## Task 5e: Visualize live microphone amplitude

**Description:** Reuse the recorder's existing 100 ms amplitude sample to animate a compact,
seven-bar level indicator inside the recording button without adding another microphone reader.

**Acceptance criteria:**
- [x] Quiet input maps to a stable floor, louder input maps monotonically to the full animation
      range, and out-of-range values are clamped.
- [x] The indicator eases between samples, returns to rest while paused, and keeps the stop action
      visually and semantically clear.
- [x] Drawing adds no dependency and no work to the lossless audio capture thread.

**Verification:**
- [x] JVM tests cover amplitude normalization boundaries; compilation and lint pass.
- [ ] Motion and sensitivity remain a manual device check after APK packaging is requested.

**Dependencies:** Task 5d recording lifecycle and existing amplitude ticker

**Files likely touched:** home Compose screen and matching JVM test.

**Estimated scope:** Small

## Task 5f: Preserve recording across background UI lifecycle and restore lint signal

**Description:** Keep the microphone foreground service alive when the task is backgrounded or
removed, restore an in-process active recording into a newly created Home ViewModel, and remove
or precisely classify legacy Lint noise so new lifecycle/security warnings remain visible.

**Acceptance criteria:**
- [x] The recording service explicitly uses `stopWithTask=false`; screen lock, Home, app switching,
      and standard task removal do not request capture shutdown while the process remains alive.
- [x] Re-entering the app in the same process restores elapsed time, pause state, amplitude, and
      stop/cancel controls without starting a duplicate recorder or live-preview consumer.
- [x] Recording, transcription, and model-download foreground notifications use distinct IDs.
- [x] Security/API/resource warnings are fixed; only deliberate version pins, generated source,
      prompt placeholders, and unused legacy icon fallback heuristics are narrowly excluded.

**Verification:**
- [x] JVM tests cover the manifest task-removal contract, UI restoration, notification ID
      uniqueness, and timezone-safe deadline formatting.
- [x] Full JVM tests pass and `lintDebug` reports zero unresolved issues.
- [ ] Lock-screen, Home/background, recent-task removal, and notification-return flows remain a
      manual device gate after APK packaging is explicitly requested.

**Dependencies:** Tasks 5d and 5e

**Files likely touched:** recording controller/service, Home ViewModel, manifest, notification IDs,
Lint configuration, Android resources, compatibility branches, and matching JVM tests.

**Estimated scope:** Medium

## Task 6: Integrate final transcript and revision-aware summaries

**Description:** Make the pipeline persist raw first, derive corrected text locally, then provide an explicit revision snapshot to optional cloud summary consumers.

**Acceptance criteria:**
- [ ] ASR/correction succeeds without network.
- [ ] Summary failure cannot roll back or delete raw/corrected transcript data.
- [ ] Later edits mark the prior summary stale and never trigger an implicit network request.
- [ ] Resume/retry validates segment provenance before reuse.

**Verification:**
- [ ] Pipeline boundary tests pass.
- [ ] Unit tests, lint, and debug build pass.

**Dependencies:** Tasks 3–5

**Files likely touched:** pipeline, summary client call sites, home/detail ViewModels.

**Estimated scope:** Medium

## Task 7: Deliver corrected-text review and rule UI

**Description:** Show corrected text as the normal view with raw/diff access, retain free manual editing, and let a simple replacement be explicitly remembered for this recording or globally.

**Acceptance criteria:**
- [ ] Corrected text drives display/search/MD/TXT/summary; JSON includes raw, corrected, and revision.
- [ ] Raw is visibly read-only and a user can inspect changes and undo them.
- [ ] No edit becomes a reusable rule without an explicit confirmation action.
- [ ] Loading/error/empty states and content descriptions follow existing Compose patterns.

**Verification:**
- [ ] ViewModel tests where practical and debug build pass.
- [ ] Manual Compose/device check is documented.

**Dependencies:** Tasks 3 and 6

**Files likely touched:** detail/search/settings/onboarding Compose and ViewModels.

**Estimated scope:** Medium

## Task 8: Enforce retention, backup, and diagnostic privacy

**Description:** Clean derived files, expire only unpinned audio after 30 days, make deletion remove associated files, exclude sensitive data from backup, and stop capturing transcript/prompt bodies in production logs.

**Acceptance criteria:**
- [ ] Record rows and transcript revisions do not expire with audio.
- [ ] Manual recording deletion removes known recordings/imports/segments/pipeline files safely.
- [ ] Backup/transfer rules exclude database, audio, logs, dictionaries, and models.
- [ ] Release diagnostics retain metadata only; body capture is explicit debug opt-in.

**Verification:**
- [ ] Retention/file-policy unit tests pass where possible.
- [ ] Lint and debug/release compilation pass.

**Dependencies:** Task 2

**Files likely touched:** repository/worker or startup cleanup, backup XML, logging interceptor/client.

**Estimated scope:** Medium

## Final Checkpoint

- [ ] `./gradlew :app:testDebugUnitTest`
- [ ] `./gradlew :app:lintDebug`
- [ ] `./gradlew :app:assembleDebug`
- [ ] Room migration instrumentation test is present; device-only gates are explicitly reported.
- [ ] Fresh-context adversarial review finds no unresolved correctness/data-loss issue.
- [ ] User is offered an optional cross-model second opinion before final reconciliation.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Destructive or lossy v5 migration | Critical | Remove destructive fallback, fixture migration test, preserve legacy bytes with explicit unknown provenance |
| JNI/VAD work blocks capture | High | Bounded queue/session and keep full WAV writer independent from inference |
| Live/final config drift | High | Freeze attempt config and validate fingerprints before cache reuse |
| Automatic correction changes meaning | High | Exact confirmed rules only, collision rejection, raw immutable, undoable revisions |
| Audio and transcript leakage | High | Explicit cloud opt-in, backup exclusions, metadata-only production logs |

## Open Questions

All product questions were resolved by the user on 2026-07-10 with “follow the recommended defaults.” Device-only performance thresholds for Qwen hotwords remain a later measured Phase 4b decision.
