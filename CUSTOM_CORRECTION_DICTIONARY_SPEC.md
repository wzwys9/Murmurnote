# Spec: 自定义纠错词典与统一上下文裁决

> 状态：用户已于 2026-07-13 确认方向；本文取代“稳妥词本”作为当前产品与实现规格。

## Objective

把含义不清的“稳妥词本”改为“自定义纠错词典”，并允许每个用户词条选择两种应用方式：

- `CONTEXTUAL_LLM`（默认，UI 名称“结合上下文”）：本地精确召回用户固定的词对，随后由
  受约束 LLM 只判断 `APPLY` 或 `KEEP`。
- `EXACT_TEXT`（UI 名称“始终替换”）：完全在本地进行确定性替换，保留给无歧义的专名、
  品牌名和固定术语。

用户自定义规则与自动学习规则共用未来转写的候选裁决和安全校验，但来源、管理入口和生命周期
保持可区分。用户自定义规则优先于自动学习规则；自动学习不得覆盖、串改或反向抵消用户明确
定义的词条。原始模型输出、历史转写和既有修订保持不变。

## Product Behavior

1. 实验室入口、管理页、详情页动作及提示统一使用“自定义纠错词典”。
2. 新建词条默认“结合上下文”，表单解释隐私边界和两种模式的差异。
3. 现有全局 `EXACT_TEXT` 词条迁移后保持“始终替换”，不静默改变已有行为。
4. 用户可查看并修改每条规则的应用方式；启停和删除语义保持不变。
5. “始终替换”在总开关开启时不依赖网络；没有 LLM API 时，“结合上下文”词条安全跳过，
   不回退为强制替换。
6. 自动学习开关仍独立控制样本采集、学习审核和已学规则应用。
7. 用户自定义上下文词条无需学习审核，启用后直接参与未来候选召回，但每次实际替换仍要求
   LLM 返回 `APPLY + HIGH` 且通过本地 allowlist、ID、重叠和修订校验。

## Contracts and Precedence

```kotlin
enum class CorrectionRuleOrigin {
    USER_DEFINED,
    PERSONAL_LEARNING,
}

data class CorrectionRule(
    val origin: CorrectionRuleOrigin,
    val matchMode: CorrectionMatchMode,
)
```

- `origin` 表示规则由用户明确创建还是由手动修改自动学习；`matchMode` 表示应用方式。
- 用户规则允许 `EXACT_TEXT` 和 `CONTEXTUAL_LLM`；学习规则只允许 `CONTEXTUAL_LLM`。
- 同一候选区间发生跨来源竞争时，`USER_DEFINED` 优先；无法安全消解的其他重叠仍全部保持原文。
- 创建、启用或改动用户规则时，停用以下冲突学习规则：同一原词、学习原词等于用户目标词，
  或学习目标词等于用户原词。
- 采集学习样本时，如果词对会触碰启用的用户规则，则不创建该学习规则。
- 确定性替换产生的坐标区间继续受现有 raw/corrected 映射保护，后续上下文规则不得改写该区间。

## Data Model and Migration

- Room 从 v8 升到 v9；`correction_rules` 新增非空 `origin`，默认 `USER_DEFINED`。
- 迁移时，带 `correction_learning_profiles` 的既有规则标记为 `PERSONAL_LEARNING`；其他既有规则
  保持 `USER_DEFINED`。
- 不新增第三方依赖，不 destructive migration，不删除或重建现有规则、学习事件或审计记录。
- DAO 查询必须显式限制来源和模式，避免用户管理 API 修改自动学习规则，反之亦然。

## LLM and Privacy Boundary

- 用户输入和转写上下文均视为不可信 JSON 数据，不是指令。
- “结合上下文”的用户启用词条最多 100 条；超出时在保存、启用或切换模式处明确拒绝，
  不让已保存词条在运行时静默失效。
- 每批仍最多 24 个候选、每段最多 6 个、候选左右各最多 80 Unicode code point。
- LLM 只能返回已有候选 ID、`APPLY|KEEP`、固定置信度和原因码；未知、重复、低置信、重叠、
  超时、解析失败或修订变化全部保持原文。
- 请求不包含音频、标题、总结、API Key 或完整录音；生产日志不记录词条与上下文正文。
- 多个用户与学习候选合并为同一次既有候选裁决请求，不为每个词条单独调用 LLM。

## Tech Stack

- Kotlin、Jetpack Compose、Hilt、Room、DataStore
- 现有 `PersonalCorrectionService`、候选查找器、LLM JSON 解析器和转写修订机制
- Java 17；不新增依赖、权限、模型或 JNI 资产

## Commands

- 领域/JVM 测试：`./gradlew :app:testDebugUnitTest`
- Android 测试编译：`./gradlew :app:compileDebugAndroidTestKotlin`
- Android Lint：`./gradlew :app:lintDebug`
- Kotlin 编译：`./gradlew :app:compileDebugKotlin`
- 本轮不主动运行 `assemble*`、`install*` 或真机安装，除非用户再次明确要求。

## Project Structure

- `domain/correction`：规则来源、模式、冲突优先级和 LLM 计划校验
- `data/local`：Room v9 实体、DAO、schema 与无损迁移
- `data/repository`：用户规则事务、学习冲突处理、统一候选快照与应用
- `data/remote/llm`：复用现有受约束候选协议，不扩大模型权限
- `ui/screen/settings`：名称、说明、模式选择、状态与无障碍语义
- `ui/screen/detail`：加入词典动作和反馈文案
- `app/src/test`、`app/src/androidTest`：领域、偏好、Repository 与迁移回归测试

## Code Style

```kotlin
transcriptRepository.saveUserDefinedRule(
    observedText = observedText,
    replacementText = replacementText,
    matchMode = CorrectionMatchMode.CONTEXTUAL_LLM,
)
```

- Kotlin 4 空格缩进；枚举值使用 `UPPER_SNAKE_CASE`。
- UI 只表达用户意图；输入、来源、冲突和 LLM 输出校验位于领域或 Repository 边界。
- 数据库枚举解析失败时安全跳过规则，不把损坏数据升级成自动修改权限。

## Testing Strategy

- 先写失败 JVM 测试：来源契约、用户优先、无法消解重叠默认 KEEP、跨来源冲突策略。
- Room migration 测试证明 v8 的精确规则和学习规则分别迁移为正确来源且数据无损。
- Repository instrumentation 测试覆盖两种用户模式、模式切换、来源隔离和学习规则停用。
- 偏好测试覆盖：自定义词典总开关不再因 API 缺失被强制关闭；上下文执行层自行安全跳过。
- LLM prompt/parser 既有注入、数量、ID 和枚举测试必须继续通过。
- UI 通过 Kotlin 编译、Lint 和可访问文本/语义审查；APK 与真机视觉检查留待用户指令。

## Boundaries

- Always：现有词条无损迁移；默认上下文模式；用户规则优先；无 LLM 时保持原文；所有模型输出
  继续本地严格校验；历史和 raw 不变。
- Always：用户切换到“始终替换”时明确展示无条件替换风险；删除前确认；错误信息不泄露内部细节。
- Ask first：把词典注入 ASR 模型、修改历史转写、增加云同步或上传完整转写。
- Never：让 LLM 生成任意替换文本；把上下文模式静默降级成强制替换；跨来源形成循环后仍自动应用；
  在日志中记录词条、上下文或密钥。

## Success Criteria

- 所有用户可见“稳妥词本/实验室词本”文案改为“自定义纠错词典”。
- 新词条默认结合上下文，可选始终替换；旧词条升级后继续始终替换。
- 用户上下文词条和自动学习词条在一次 LLM 请求中裁决，用户规则竞争时优先。
- 创建、启用和学习阶段均阻止跨来源同源、串联与反向冲突。
- LLM/API 不可用时不误改，确定性词条仍按用户选择执行。
- Room v8→v9、领域、偏好、Repository、LLM 安全回归测试通过；Kotlin 编译和 Lint 通过。
- 不生成或安装 APK。

## Open Questions

无。默认模式、旧数据兼容、无 LLM 降级、来源优先级及 UI 分层均按用户已确认方案实施。
