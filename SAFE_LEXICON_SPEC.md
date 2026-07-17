# Spec: 稳妥词本 MVP

> 历史规格：该 MVP 已完成；当前产品语义由 `CUSTOM_CORRECTION_DICTIONARY_SPEC.md` 取代。

## Objective

为本地 ASR 增加一个可管理的个人词本，但只复用现有的确定性 `EXACT_TEXT` 后处理规则。
用户明确录入“识别结果 → 正确写法”后，启用的全局词条只作用于之后完成的转录；模型原文、
历史修订和审计记录不可被覆盖。第一版不做拼音猜测、同音词推断、Qwen 模型热词注入、
SenseVoice Homophone Replacer 或历史文本批量重写。

词本提供独立总开关且默认关闭。关闭时不加载、不应用全局词条，已保存词条不被删除；
现有录音级规则、ASR、AI 整理和总结链路保持原样。

总开关还要求当前所选 LLM 供应商已经配置 API Key。未配置时开关不可用；清空当前
API Key 或切换到未配置的供应商时，持久化开关与运行时状态都必须关闭。之后首次补齐
API Key 也不能从残留值自动恢复开启，必须由用户重新手动开启。该门槛为后续 LLM 纠错
预留，本 MVP 本身仍不新增 LLM 网络请求。

## Tech Stack

- Kotlin、Jetpack Compose、Hilt ViewModel
- Room 现有 `correction_rules` / `correction_records` 表
- 现有 `TranscriptRepository` 与 `DeterministicCorrectionEngine`
- 不新增第三方依赖，不新增网络调用，不修改 ASR/VAD 模型资产

## Commands

- 单元测试：`./gradlew :app:testDebugUnitTest`
- Android Lint：`./gradlew :app:lintDebug`
- Release 构建：`./gradlew :app:assembleRelease`
- 真机安装：`adb -s 192.168.10.175:36441 install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk`

## Project Structure

- `domain/correction`：词条输入规范化与安全校验
- `data/local/dao`：只查询和变更全局精确规则
- `data/repository/TranscriptRepository.kt`：创建、启停、删除的事务边界
- `ui/screen/settings`：实验室入口、实验室页、独立词本页面及 ViewModel
- `app/src/test`：纯规则策略测试
- `app/src/androidTest`：Room/Repository 持久化测试（沿用现有测试基础设施）

## Code Style

```kotlin
val rule = SafeLexiconRulePolicy.normalize(
    observedText = observedText,
    replacementText = replacementText,
)
```

- 使用现有 Kotlin/Compose 命名与 4 空格缩进。
- UI 只发送用户意图；输入校验和数据不变量放在领域/Repository 边界。
- 错误信息说明如何修复，不显示数据库或内部堆栈细节。

## Testing Strategy

- JVM 单测覆盖修剪、字符长度、控制字符、相同映射与合法 Unicode。
- Repository/Room 测试覆盖创建、重复、冲突、启停、删除及作用域保护。
- 定稿测试覆盖总开关默认关闭、关闭时跳过全局词条、开启后才应用全局词条。
- 偏好策略测试覆盖未配置 LLM API 时无法开启，以及残留开启值运行时失效。
- 现有纠错引擎测试继续证明冲突规则不会被自动应用、raw 不可变。
- 完整单测、Lint、Release 构建后进行设置页空态、添加、启停、删除真机检查。

## Boundaries

- Always：新词条至少 2 个且至多 32 个 Unicode code point；去除首尾空白；禁止控制字符；
  原词与目标词必须不同；删除前二次确认；所有 Room 查询参数化。
- Always：API 可用性在持久化入口和定稿读取处双重校验；不在日志或 UI 状态中暴露密钥。
- Ask first：未来把词条注入 Qwen/SenseVoice、加入拼音库、修改历史转录或新增自动学习。
- Never：覆盖模型 raw、从 LLM 自动生成规则、记录词条正文到运行日志、静默批量修改历史文本。

## Threat Model

- 用户输入可能极长或包含控制字符：在领域边界限制 code point 数量并拒绝控制字符。
- 冲突词条可能让同一原词得到多个结果：Repository 拒绝启用冲突映射和反向映射。
- 删除规则可能破坏历史审计：只删除规则定义；外键按既有约束清空 source ID，既有
  correction records 的原词、替换词和录音修订快照继续保留。
- 自动应用可能改坏旧内容：规则变化不触发历史重写，只在新的模型定稿事务中读取启用规则。

## Success Criteria

- 设置页可进入“实验室功能”，再进入“稳妥词本”管理页；主设置页不直接展示实验词本。
- 实验室页提供默认关闭的词本总开关，并明确说明关闭不会删除已保存词条。
- 当前 LLM API 未配置时总开关置灰；清空密钥或切换到未配置供应商后立即关闭。
- 词本页展示明确的非热词/非拼音说明和空状态。
- 用户可添加全局精确映射、查看启用状态、启停并经确认删除。
- 重复映射幂等处理；同一原词的不同目标或反向映射被拒绝。
- 只有总开关与词条本身均启用时，全局规则才参与之后的最终转录；实时预览、历史转录和 raw 不改变。
- 无新增依赖、权限、网络请求或数据库迁移。
- 测试、Lint、Release 构建与 arm64 真机检查通过。

## Open Questions

无。用户已确认采用保守版本；LLM 分类词本、模型内热词、拼音候选和历史重算明确留待
后续单独讨论。未来的分类词本若由 LLM 根据初稿判断类别，只能用于二次纠错/二次识别；
如需影响首轮 ASR，则必须增加用户预选类别或可验证的会话类别继承机制。
