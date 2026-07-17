# Spec: 个性化自学习纠错（实验）

> 状态：用户已于 2026-07-11 确认；代码实现与静态验证已完成，APK/真机体验按用户后续指令执行。
> 用户自定义规则的后续统一与优先级以 `CUSTOM_CORRECTION_DICTIONARY_SPEC.md` 为准。

## Objective

在现有“稳妥词本”的确定性精确替换之外，增加一套真正由用户修改驱动的个性化纠错：

- 只从用户明确保存的转写修改中学习，不从 LLM 总结、模型猜测或未确认文本中学习。
- 用户保存一次符合条件的修改后，系统自动记为学习样本，不再要求手动点“加入词本”。
- 拼音相似度只用于判断 ASR 混淆的合理性和候选排序；不直接触发替换。
- 后续转写命中已学候选时，LLM 只能结合有限上下文决定“应用/保持原文”，不能生成任意新文本。
- 用户对自动纠错再次修改时，视为负反馈，立即停用造成误改的个性化规则并重新学习。
- 功能位于实验室，独立开关默认关闭；没有当前 LLM API 时不可开启。
- 原始模型输出、历史修订和既有稳妥词本语义保持不变。

录音和导入音频共用同一条最终转写流水线，因此两者都执行相同的个性化纠错逻辑。

## Research Basis

采用“用户反馈 + 候选召回 + 上下文验证”的组合，而不是让 LLM 重写整段文本：

- Google 的个性化发音研究证明，可以从用户实时纠正中隐式学习个性化发音，并在联系人姓名识别上降低错误率；UserLibri 也说明用户自己的文本数据能改善个性化 ASR。[Learning Personalized Pronunciations](https://research.google/pubs/learning-personalized-pronunciations-for-contact-names-recognition/)、[UserLibri](https://research.google/pubs/userlibri-a-dataset-for-asr-personalization-with-only-text/)
- 中文纠错研究普遍把音似、形似作为候选知识，再由语言上下文判断。SpellGCN、ChineseBERT 和 EMNLP 2024 的最小失真方法都支持这一方向。[SpellGCN](https://aclanthology.org/2020.acl-main.81/)、[ChineseBERT](https://aclanthology.org/2021.acl-long.161/)、[Minimal-Distortion CSC](https://aclanthology.org/2024.emnlp-main.966/)
- 过度纠错是已知主要风险。CRASpell 明确通过偏向复制原文来降低误改；中文 ASR-EC 2025 的评测还显示，单纯提示通用 LLM 并不是可靠的 ASR 纠错方案。因此本方案默认保持原文，并把模型限制为候选裁判。[CRASpell](https://aclanthology.org/2022.findings-acl.237/)、[ASR-EC Benchmark](https://aclanthology.org/2025.emnlp-industry.110/)
- Android 10 / API 29 起提供系统 ICU `Transliterator`；本项目 `minSdk 29`，可以在不新增第三方拼音依赖的情况下使用系统注册的 Han-Latin 转写，并在运行时检查可用 ID。[Android Transliterator](https://developer.android.com/reference/android/icu/text/Transliterator)、[ICU transforms](https://unicode-org.github.io/icu/userguide/transforms/general/)

这些研究支持的不是“见到同音字就换”，而是：拼音/字形负责缩小候选，用户历史提供个性化知识，当前语境负责最后决策。

## Product Behavior

### 1. 实验室开关

新增“个性化自学习纠错（实验）”卡片，与现有“稳妥词本”分开：

- 默认关闭；关闭时不采集新学习样本、不调用纠错 LLM、不应用个性化规则。
- 当前 LLM 供应商没有 API Key 时置灰，并强制保持关闭。
- 第一次开启前展示一次明确说明：会把用户修改的词对，以及修改位置附近最多 240 个 Unicode code point，发送给当前配置的 LLM 服务商；不会发送音频、标题、总结或整段录音。
- 清空 API Key 或切换到未配置的供应商后立即关闭；已学数据保留在本机。

现有“稳妥词本”继续作为用户手动维护的 `EXACT_TEXT` 规则。它不依赖上下文，优先级高于个性化规则，也不被本功能自动增删。

### 2. 从用户修改中学习

用户保存转写段修改后：

1. 转写修改先在本地事务中保存；学习失败不得回滚用户修改。
2. 只有一个连续替换区间、原词和目标词都非空、各不超过 32 code point，且不含控制/格式化/双向控制字符时，才成为学习样本。
3. 在同一事务中持久化一个待评估样本，包含词对、左右局部上下文和修订号；网络评估在事务外执行。
4. LLM 不可用时样本保持“待评估”，以后进入详情页或开始新转写时，最多重试少量待评估样本。
5. LLM 只能返回固定样本 ID、枚举结论和置信等级，不能返回新词。经过本地校验后，样本进入“已学会”“需更多样本”或“已拒绝”。

一个用户明确修改就可以生成“已学会”的上下文候选，但不会形成无条件全局替换；它在每次未来命中时仍必须通过当前上下文的 LLM 判断。

### 3. 拼音与形似信号

本版使用系统 ICU Han-Latin 产生规范化、去声调的小写拼音，分类为：

- `EXACT_PINYIN`：音节序列相同；
- `NEAR_PINYIN`：音节数量相同且规范化音节只有很小编辑距离；
- `NOT_PHONETIC`：不满足音近条件；
- `UNAVAILABLE`：设备没有所需 ICU transform 或文本无法可靠转换。

拼音信号只参与学习评估和候选排序。第一版只在未来文本中匹配用户实际改过的原词，不根据拼音在全文枚举新的同音词并直接替换。这样可以覆盖“生记 → 声记”这类已经由用户确认的 ASR 混淆，同时避免把“升级”等合法同音表达当成候选。

形似关系交给 LLM 作为辅助分类，但不能单独激活规则。原因是本功能处理的是音频 ASR 输出，主要误差来源是发音、专名和上下文；字形混淆更常见于键盘、手写或 OCR。若以后要对任意手输文本纠错，再单独评估带许可证的 Unihan/IDS 字形数据，不在这一版静默引入不透明混淆表。

### 4. 后续转写中的上下文判断

完成原始 ASR 和现有确定性规则后，个性化纠错按以下步骤运行：

1. 本地只查找已学规则的原词精确命中，并生成带稳定 ID、规则 ID、区间和有限上下文的候选。
2. 每段最多 6 个、每次录音最多 24 个候选；每个候选最多携带左右各 80 code point，请求还受现有 LLM 请求/响应总字节和超时限制。
3. 一批候选发送给当前 LLM。模型只可对每个 ID 返回 `APPLY` 或 `KEEP`、固定原因码和置信等级。
4. 本地仅接受请求中存在的 ID；原词、目标词、区间必须与快照完全一致，区间不可重叠，录音修订号不可变化。
5. 只有 `APPLY + HIGH` 才应用；缺项、未知 ID、冲突、低置信、解析失败、超时或网络失败全部保持原文。
6. 应用结果创建新的自动纠错修订并写审计记录，之后 AI 总结读取该明确修订。原始模型文本永不改写。

LLM 的系统提示与 AI 总结的自定义提示完全分离；转写正文作为 JSON 数据字段传入。即使正文包含提示注入文字，响应也只能选择代码预先给出的候选 ID，不能产生任意编辑。

### 5. 负反馈与遗忘

- 如果用户修改的区间与上一条个性化自动纠错重叠，系统把它视为负反馈。
- 精确撤销或改成第三种写法时，造成误改的规则立即变为“已停用”，以后不再参与候选生成。
- 新词对仍可作为新的待评估样本，但冲突规则不会同时启用。
- 管理页允许查看状态、样本次数、音近类型和最近判断，支持停用、重新启用、删除单条以及清空全部。
- 删除录音时删除属于该录音的局部学习上下文；聚合后的词对仍作为独立个人设置保留，除非用户在学习管理页删除。

## Data Model and Migration

数据库从 v7 升到 v8，采用显式、无损的 Room migration；不允许 destructive fallback。Android 官方建议保存 schema 并用 `MigrationTestHelper` 验证迁移和数据。[Room migration guidance](https://developer.android.com/training/data-storage/room/migrating-db-versions)

复用现有 `correction_rules`：

- 新增领域枚举 `CorrectionMatchMode.CONTEXTUAL_LLM`；该模式仍保存用户确认过的 `observedText → replacementText`，作用域固定为全局。
- `isEnabled` 表示用户是否允许该规则参与上下文候选。
- 现有 `EXACT_TEXT` DAO 查询继续显式过滤，不会误加载上下文规则。
- 现有 `correction_records.sourceRuleId` 可继续关联并审计个性化规则。

新增两张表：

- `correction_learning_profiles`：以 `ruleId` 为主键，保存状态、正/负样本数、规范化拼音、音近类型、最近 LLM 结论及时间；删除规则时级联删除。
- `correction_learning_events`：保存一次用户修改的有界上下文、来源录音/修订、评估状态和固定枚举结果；删除来源录音时级联删除，避免残留上下文。

词对和上下文不写入生产日志；日志只记录候选数量、状态码、耗时和匿名错误类型。现有备份排除规则继续覆盖数据库。

## Module Boundaries

- `domain/correction`：学习资格、拼音关系、候选生成、冲突/重叠处理、LLM 响应验证，保持纯 Kotlin 可测试。
- `data/local`：v8 实体、DAO、schema 和 migration。
- `data/repository`：原子保存样本、生成带修订快照的候选、应用已验证计划、负反馈事务。
- `data/remote/llm`：独立的学习评估和候选裁判 API；复用供应商连接层，但不复用总结 prompt。
- `domain/pipeline`：在原始转写已经安全落库后调用可选个性化纠错；失败只降级为原文。
- `ui/screen/settings`：实验室开关、隐私确认和学习管理页。
- `ui/screen/detail`：保存修改后的学习状态提示，不阻塞编辑保存。

## Security and Failure Boundaries

- LLM 响应按不可信输入处理：固定 schema、枚举 allowlist、ID allowlist、数量/长度上限、区间和修订二次校验。
- 默认选择 `KEEP`；模型没有明确给出高置信应用决定时不修改。
- 网络请求永不放进 Room 事务；进程被杀、取消或超时不会留下半应用修订。
- 同一原词对应多个启用目标、反向映射、循环映射和重叠候选都拒绝自动应用。
- 每次请求和待处理队列有硬上限，避免超长录音或恶意文本造成资源耗尽和高额 API 消耗。
- API 未配置、功能关闭、ICU 不可用、LLM 解析失败或供应商不支持 JSON 模式时，行为都是安全降级，不影响 ASR、现有词本和总结。
- 不回写历史录音，不修改 raw，不把用户修改反向用于模型训练或跨设备上传。

## Test-First Implementation Slices

1. **RED — 纯领域契约**：学习资格、Unicode 边界、拼音分类、冲突、候选上限、响应 allowlist、默认 KEEP 和负反馈失败测试。
2. **GREEN — 本地学习存储**：v8 实体/DAO/migration，Repository 事务测试和 schema 导出。
3. **GREEN — 受约束 LLM**：各供应商共用的请求模型、严格解析、响应/超时上限和 prompt-injection 回归测试。
4. **GREEN — 流水线**：录音/导入共同路径、失败降级、修订竞争、审计和总结修订边界测试。
5. **GREEN — UI**：默认关闭、API 门槛、首次隐私说明、状态/停用/删除/清空和无障碍语义。
6. **REFACTOR / VERIFY**：完整 JVM、Lint、Release、v7→v8 迁移真机测试，再做新鲜上下文安全复核。

## Commands

- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`
- `./gradlew :app:assembleDebug`
- `./gradlew :app:assembleRelease`
- `./gradlew :app:connectedDebugAndroidTest`

本轮按用户之前的要求，完成代码后先不主动打包或安装 APK，除非用户再次明确要求。

## Success Criteria

- 开关关闭或 API 缺失时，数据库不新增学习样本、无纠错 LLM 请求、输出与当前版本一致。
- 开关开启时，符合条件的一次手动修改会自动持久化并异步评估；网络失败不影响修改保存。
- 已学规则未来命中时，LLM 只可选择候选；任何越权输出都保持原文。
- 拼音相同/相近会作为可审计信号参与判断，但永远不能绕过上下文裁判。
- 用户纠正一次自动误改后，旧规则立即停用且不会在下一次处理中再次误改。
- 录音和导入音频行为一致；raw、历史修订和手动稳妥词本保持兼容。
- v7 数据无损升级到 v8；迁移、Repository、流水线、UI 状态和安全边界测试通过。
- 管理页可以解释“学了什么、为什么生效、用了几次”，并可完整撤销或删除。

## Approval Gate

推荐按本文默认值实现：一次用户修改即可成为“上下文候选”，但每次未来应用都必须由 LLM
基于局部上下文给出高置信批准；每次最多发送 240 code point 的学习上下文，不发送音频或
整段录音；形似只作辅助信息，不做直接替换触发器。

用户已确认本文；实现按失败测试、数据库迁移、受约束 LLM、统一流水线和管理 UI
的顺序进行。
