# Local ASR and deterministic correction checklist

## Current: 个性化自学习纠错（实现中）

- [x] A1：审阅现有手动修改、精确词本、Room、LLM 和统一音频流水线
- [x] A2：调研用户反馈个性化、中文音/形/语境纠错及过度纠错风险
- [x] A3：确定“代码给候选、LLM 只裁决、默认保留原文”的安全架构
- [x] A4：写入 `SELF_LEARNING_CORRECTION_SPEC.md`、隐私边界和验收标准
- [x] Gate：用户确认一次修改即可成为上下文候选，以及最多 240 code point 的云端上下文
- [ ] B1：失败测试与拼音/候选/响应验证纯领域实现
- [ ] B2：Room v8 profile/event 表、DAO、migration、schema 和迁移测试
- [ ] C1：受约束 LLM 学习评估与未来候选裁决
- [ ] C2：录音/导入统一流水线、自动修订审计和负反馈停用
- [ ] D1：实验室独立开关、首次隐私说明和学习管理 UI
- [ ] D2：完整测试、Lint、Release 与真机验证

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
