# Local ASR and deterministic correction checklist

## Current: 高置信死代码审计与清理

- [x] A1：盘点 Kotlin/Compose、资源、Manifest 和依赖候选
- [x] A2：排除框架间接入口并核对测试与历史用途
- [x] B1：分批删除高置信死代码，每批完成编译/测试
- [x] B2：完整 JVM、Android 测试编译、Kotlin 编译、Lint 与最终复核

## Current: 自定义纠错词典与统一上下文裁决

- [x] A1：规则来源、应用模式、用户优先与跨来源冲突失败测试
- [x] A2：Room v8→v9 无损迁移、schema 与来源隔离 DAO
- [x] B1：用户规则事务支持上下文/始终替换及模式修改
- [x] B2：用户规则写入时停用冲突学习规则，学习采集反向避让
- [x] B3：两类上下文规则共享候选快照和单批 LLM 裁决
- [x] B4：自定义词典开关与 API 可用性解耦，上下文失败安全 KEEP
- [x] C1：重命名全部用户文案并更新详情页动作
- [x] C2：添加、展示和修改应用方式，补齐风险/隐私/无障碍说明
- [x] C3：完整测试、Android 测试编译、Kotlin 编译、Lint 与最终复核（不打 APK）

## Current: 个性化自学习纠错（代码、APK 与真机验收完成）

- [x] A1：审阅现有手动修改、精确词本、Room、LLM 和统一音频流水线
- [x] A2：调研用户反馈个性化、中文音/形/语境纠错及过度纠错风险
- [x] A3：确定“代码给候选、LLM 只裁决、默认保留原文”的安全架构
- [x] A4：写入 `SELF_LEARNING_CORRECTION_SPEC.md`、隐私边界和验收标准
- [x] Gate：用户确认一次修改即可成为上下文候选，以及最多 240 code point 的云端上下文
- [x] B1：失败测试与拼音/候选/响应验证纯领域实现
- [x] B2：Room v8 profile/event 表、DAO、migration、schema 和迁移测试
- [x] C1：受约束 LLM 学习评估与未来候选裁决
- [x] C2：录音/导入统一流水线、自动修订审计和负反馈停用
- [x] D1：实验室独立开关、首次隐私说明和学习管理 UI
- [x] D2：JVM 254 tests、Android 真机 49 tests、Lint 0 issues、四 ABI Debug/Release
      构建及 Android 16 录音/熄屏/离线转写/实验室 UI 冒烟已通过

## Current: 安全与架构加固

- [x] A1：归档安全策略失败测试与实现
- [x] A2：模型下载、条目数量和解压字节上限
- [x] A3：外部音频有界复制、后台 I/O 与单并发导入
- [x] B1：录音规则与全局词本 API 完全分离
- [x] B2：总开关关闭时全局规则不参与录音级判断
- [x] B3：拒绝 Unicode 不可见格式及双向控制字符
- [x] C1：FFmpeg 协程取消传播到原生会话
- [x] C2：LLM 输入、JSON 响应体与每次请求时限
- [x] D1：真实 DataStore 状态转换集成测试
- [x] D2：Release 移除 debug 签名回退
- [x] D3：完整测试、Lint、Release 构建和真机验证

## Current: 稳妥词本 MVP

- [x] A1：词条输入策略的失败测试与实现
- [x] B1：全局规则查询、创建、重复/冲突处理
- [x] B2：全局规则启停、删除和作用域保护
- [x] B3：默认关闭的总开关与定稿链路门控
- [x] B4：未配置当前 LLM API 时强制关闭并阻断持久化开启
- [x] C1：设置页实验室入口、实验室页与独立词本管理页面
- [x] C2：空态、错误态、删除确认和无障碍标签
- [x] C3：实验室与词本页展示 LLM API 缺失禁用状态
- [x] D1：完整测试、Lint、Release 构建和真机检查

- [x] Task 1: Pure ASR/VAD/correction contracts and tests
- [ ] Task 2: Lossless Room v5→v6 migration
- [ ] Task 3: Transactional transcript repository and revisions
- [ ] Checkpoint: unit tests + debug build + migration fixture
- [ ] Task 4: Frozen ASR sessions and local-first onboarding/defaults
- [ ] Task 5a: Offline Silero VAD and slicing
- [ ] Task 5b: Live Silero VAD without blocking capture
- [x] Task 5c: Bounded secondary Silero probe before forced cuts (recorded + imported canonical path)
- [x] Task 5d: Screen-off microphone foreground service and bounded wake lock
- [x] Task 5e: Live amplitude-driven recording animation
- [x] Task 5f: Background/task-removal recording continuity, UI restoration, and Lint hygiene
- [ ] Maintenance: Review pinned AGP/Kotlin/AndroidX upgrades as a separate compatibility change
- [ ] Task 6: Pipeline correction and revision-aware summary boundary
- [ ] Task 7: Corrected/raw review, manual edit, undo, and explicit rule UI
- [ ] Task 8: Audio retention, deletion, backup, and diagnostic privacy
- [ ] Final tests, lint, builds, device-gate report, and adversarial review
