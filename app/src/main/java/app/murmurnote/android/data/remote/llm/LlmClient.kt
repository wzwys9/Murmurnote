package app.murmurnote.android.data.remote.llm

import android.content.Context
import app.murmurnote.android.R
import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.data.remote.llm.ExtractionJsonParser
import app.murmurnote.android.data.remote.llm.dto.ChatCompletionResponse
import app.murmurnote.android.data.remote.llm.dto.ExtractedItemDto
import app.murmurnote.android.data.remote.llm.dto.ExtractionResult
import app.murmurnote.android.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.os.SystemClock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class LlmClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val appPreferences: AppPreferences,
    private val json: Json,
    private val logger: Logger
) {

    private companion object {
        const val MAX_ATTEMPTS = 4                 // 1 初次 + 3 重试
        const val BASE_BACKOFF_MS = 800L           // 800ms / 1.6s / 3.2s 的指数退避
        const val MAX_BACKOFF_MS = 8_000L
        // 长转写 map-reduce:超过这个字数就分块抽取再合并,避免单次 LLM 在中间段丢细节或截断。
        const val LONG_TRANSCRIPT_THRESHOLD = 3_000
        // 每个分块的目标字数(贪心按句号拼,不会精确到字符,允许 +/- 几百字)。
        const val CHUNK_TARGET_CHARS = 1_400
        // 上限保护:无论多长都不超过这个块数,超出部分合并到最后一块。
        const val MAX_CHUNKS = 6
        // 同时跑的分块抽取数:OkHttp dispatcher 默认每 host 5 并发,3 留余地给同时跑的 ASR。
        const val CHUNK_CONCURRENCY = 3

        /** 分块专用的系统提示——告知模型它看到的只是长录音的一个片段。 */
        val CHUNK_SYSTEM_PROMPT = """
你正在处理一段较长录音的其中一个片段（非完整录音）。

从当前片段提取：
1. 本段要点（bullet 列表，以 "• " 开头，\n 换行）
2. 结构化条目（todo/idea/note/decision）

注意：
- 这是片段，不要写 "• 主题：" 行（整体主题由后续合并步骤确定）
- 聚焦当前片段的关键事实、决策、待办、想法
- 条目类型：todo（待办）/ idea（想法）/ note（备忘）/ decision（决策）
- 没有可提取事项时 items 返回 []
- summary 至少要有 1 条 bullet，绝不可为空

输出严格 JSON，无 markdown 围栏：
{"summary":"• 事实：...\n• 待办：...","items":[{"type":"todo","content":"...","deadline":null,"sourceTimestampMs":null}]}
""".trimIndent()
    }

    /**
     * 调用当前 LLM provider 提取结构化信息。
     * 对可重试错误（5xx / 429 / 网络异常）做指数退避；4xx（鉴权 / 请求格式）即时失败不重试，省得浪费配额。
     */
    suspend fun extractItems(transcript: String, isChunk: Boolean = false): Result<ExtractionResult> = runCatching {
        val config = currentConfig()
        if (config.provider.requiresApiKey && config.apiKey.isBlank()) {
            error("${config.provider.displayName} API Key 未配置")
        }

        val systemPromptOverride = appPreferences.systemPromptOverride.first()
        val userPromptOverride = appPreferences.userPromptOverride.first()
        // 用户自定义 prompt 优先；否则分块和完整转录用不同提示
        val systemPrompt = systemPromptOverride?.takeIf { it.isNotBlank() }
            ?: if (isChunk) CHUNK_SYSTEM_PROMPT
            else context.getString(R.string.prompt_extract_system)
        val userPromptTemplate = userPromptOverride?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.prompt_extract_user_template)
        val userPrompt = userPromptTemplate.format(transcript)

        logger.i(
            "LLM",
            "extractItems begin provider=${config.provider.name} model=${config.model} effort=${config.effort} promptOverride=${systemPromptOverride != null || userPromptOverride != null} chars=${transcript.length}"
        )
        val started = SystemClock.elapsedRealtime()

        val rawContent = completeText(
            config = config,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            jsonMode = true,
            label = "extractItems"
        )

        val parsedResult = runCatching {
            ExtractionJsonParser.parse(rawContent, json)
        }.getOrElse { e ->
            logger.e("LLM", "extractItems: failed to parse ExtractionResult raw=${rawContent.take(300)}", e)
            // 降级：summary 用全文摘要，items 为空，让用户至少能看到转写内容
            ExtractionResult(summary = "", items = emptyList())
        }
        // 兜底:即便 prompt 已经强调"summary 不可为空", LLM 偶尔仍会顽固返回 "":这里用转写原文合成
        // 一条 bullet,保证详情页永远有总结可看。一句话型短录音用全文,长录音截 80 字。
        val finalResult = if (parsedResult.summary.isBlank()) {
            val snippet = transcript.replace(Regex("\\s+"), " ").trim().take(80)
            val synthesized = "• 用户的录音内容：$snippet"
            logger.w("LLM", "extractItems synthesized summary (LLM returned blank) chars=${snippet.length}")
            ExtractionResult(synthesized, parsedResult.items)
        } else {
            parsedResult
        }
        logger.i(
            "LLM",
            "extractItems ok items=${finalResult.items.size} summaryChars=${finalResult.summary.length} elapsed=${SystemClock.elapsedRealtime() - started}ms"
        )
        finalResult
    }.onFailure { e ->
        logger.e("LLM", "extractItems failed: ${e.message?.take(200)}", e)
    }

    /**
     * 长转写自动分块抽取的入口。AudioPipeline 调用这个,而不是直接 extractItems。
     * - 短转写(<阈值):透传到 extractItems(单次调用)。
     * - 长转写:按句号切句 → 贪心拼成 ~CHUNK_TARGET_CHARS 字的块 → 并发抽取 → 合并:
     *     items 直接拼接并按 (type, content normalized) 去重;
     *     summary 走一次 merger LLM 生成统一的分组结构化摘要,失败兜底为简单去重拼接。
     */
    suspend fun extractItemsAuto(transcript: String): Result<ExtractionResult> {
        if (transcript.length <= LONG_TRANSCRIPT_THRESHOLD) {
            return extractItems(transcript)
        }
        return runCatching {
            val chunks = chunkTranscript(transcript)
            val started = SystemClock.elapsedRealtime()
            logger.i(
                "LLM",
                "extractItemsAuto LONG chars=${transcript.length} chunks=${chunks.size}"
            )
            val parts: List<ExtractionResult> = coroutineScope {
                val sem = Semaphore(CHUNK_CONCURRENCY)
                chunks.mapIndexed { idx, chunk ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            val r = extractItems(chunk, isChunk = true).getOrThrow()
                            logger.d(
                                "LLM",
                                "chunk ${idx + 1}/${chunks.size} chars=${chunk.length} items=${r.items.size} summaryChars=${r.summary.length}"
                            )
                            r
                        }
                    }
                }.awaitAll()
            }
            val merged = mergeExtractions(parts)
            logger.i(
                "LLM",
                "extractItemsAuto merged items=${merged.items.size} summaryChars=${merged.summary.length} elapsed=${SystemClock.elapsedRealtime() - started}ms"
            )
            merged
        }.onFailure { e ->
            logger.e("LLM", "extractItemsAuto failed: ${e.message?.take(200)}", e)
        }
    }

    /** 按句末标点切句,贪心拼成 ~CHUNK_TARGET_CHARS 字的块,相邻块之间重叠 1–2 句以保持上下文连续。最多 MAX_CHUNKS 块,超出部分合并到末块。 */
    internal fun chunkTranscript(text: String): List<String> {
        val sentenceEnd = setOf('。', '！', '？', '.', '!', '?', '\n')
        val sentences = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            sb.append(c)
            if (c in sentenceEnd) {
                sentences += sb.toString()
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) sentences += sb.toString()

        // 先把句子按 CHUNK_TARGET_CHARS 贪心分组（句子级，不重叠）
        val groups = mutableListOf<MutableList<String>>()
        var curLen = 0
        for (s in sentences) {
            if (groups.isEmpty() || (curLen > 0 && curLen + s.length > CHUNK_TARGET_CHARS)) {
                groups += mutableListOf<String>()
                curLen = 0
            }
            groups.last() += s
            curLen += s.length
        }

        // 上限保护：超出 MAX_CHUNKS 的尾部全并到最后一组
        if (groups.size > MAX_CHUNKS) {
            val head = groups.take(MAX_CHUNKS - 1)
            val tail = groups.drop(MAX_CHUNKS - 1).flatten()
            groups.clear()
            groups.addAll(head)
            groups += tail.toMutableList()
        }

        // 相邻块之间重叠最后 2 句，让 LLM 看到跨边界的上下文
        val chunks = groups.mapIndexed { i, group ->
            if (i == 0) {
                group.joinToString("")
            } else {
                val prev = groups[i - 1]
                val overlap = prev.takeLast(2).joinToString("")
                overlap + group.joinToString("")
            }
        }
        return chunks
    }

    /** 合并多份分块抽取结果:items 去重拼接,summary 通过 merger LLM 整合。 */
    private suspend fun mergeExtractions(parts: List<ExtractionResult>): ExtractionResult {
        val seen = mutableSetOf<Pair<String, String>>()
        val mergedItems = mutableListOf<ExtractedItemDto>()
        for (p in parts) {
            for (item in p.items) {
                val key = item.type.lowercase() to
                    item.content.replace(Regex("\\s+"), " ").trim().lowercase()
                if (seen.add(key)) mergedItems += item
            }
        }

        val unifiedSummary = mergeSummariesViaLLM(parts.map { it.summary }).getOrElse { e ->
            logger.w("LLM", "merge LLM failed, fallback to dedup-concat: ${e.message?.take(120)}")
            // 兜底:直接拼接去重 bullet 行,确保用户至少能看到所有要点。
            parts.flatMap { it.summary.lines() }
                .map { it.trim() }
                .filter { it.startsWith("•") }
                .distinct()
                .joinToString("\n")
        }
        return ExtractionResult(unifiedSummary, mergedItems)
    }

    /** 跑一次 merger LLM,把多份 bullet 子摘要合并成统一的分组结构化摘要。 */
    private suspend fun mergeSummariesViaLLM(summaries: List<String>): Result<String> = runCatching {
        val config = currentConfig()
        if (config.provider.requiresApiKey && config.apiKey.isBlank()) {
            error("${config.provider.displayName} API Key 未配置")
        }

        val mergerSystem = """
你是一名编辑，负责把一段长录音的各分段摘要合并成统一的最终摘要。

各分段摘要是从录音的不同时间片段独立抽取的 bullet 列表，按时序排列。注意：分段摘要没有 "主题" 行——你需要根据所有分段的内容，自己提炼出整段录音的总主题。

## 合并流程
1. 通读所有分段摘要，理解整段录音讲了什么，识别主要话题和话题切换点
2. 按话题归类（不是机械地按分段归类）：同一话题的内容合并到一起，跨分段的相关 bullet 合并去重
3. 确定整体叙事结构：如果是单一话题，按「背景 → 事实 → 计划 → 决定 → 待办」组织；如果跨越多个不相关的话题，按话题分块，每个话题内部再用上述结构
4. 构思 "主题" 行：一句话（8–40 字）概括整段录音的核心内容

## 输出要求
- bullet 列表，每条 "• " 开头，\n 换行，无 markdown 围栏
- 第一条固定 "• 主题：xxx"（8–40 字，你根据所有分段自己提炼）
- 之后按逻辑顺序展开：单一话题按「背景 → 事实 → 计划 → 决定 → 待办」；多话题按时序或重要性排列，每个话题内保持结构
- 意思相同或高度相似的 bullet 合并为一条，关键事实（人名、时间、数字）不丢
- 6–15 条为宜，内容丰富的录音可更多，但避免灌水和重复
- 直接输出 bullet 列表，不要任何解释、思考过程、前后缀
""".trimIndent()

        val mergerUser = buildString {
            append("以下是同一段录音按时序分段抽取的子摘要，请合并成统一的最终摘要：\n\n")
            summaries.forEachIndexed { i, s ->
                append("=== 第 ${i + 1} 段 ===\n")
                append(s)
                append("\n\n")
            }
            append("直接输出合并后的 bullet 列表，不要任何前后文。")
        }

        val raw = completeText(
            config = config,
            systemPrompt = mergerSystem,
            userPrompt = mergerUser,
            jsonMode = false,
            label = "mergeSummaries"
        )
        ExtractionJsonParser.stripThink(raw).trim().takeIf { it.isNotBlank() }
            ?: error("merge: 响应为空")
    }

    private data class LlmConfig(
        val provider: LlmProvider,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val effort: String
    )

    private suspend fun currentConfig(): LlmConfig {
        val provider = LlmProvider.parse(appPreferences.llmProvider.first())
        return LlmConfig(
            provider = provider,
            baseUrl = appPreferences.llmBaseUrl.first().trimEnd('/'),
            apiKey = appPreferences.llmApiKey.first(),
            model = appPreferences.llmModel.first(),
            effort = appPreferences.reasoningEffort.first()
        )
    }

    private suspend fun completeText(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean,
        label: String
    ): String = when (config.provider) {
        LlmProvider.DEEPSEEK,
        LlmProvider.OPENAI -> completeOpenAiCompatible(config, systemPrompt, userPrompt, jsonMode, label)
        LlmProvider.OLLAMA -> completeOllamaCloud(config, systemPrompt, userPrompt, jsonMode, label)
        LlmProvider.ANTHROPIC -> completeAnthropic(config, systemPrompt, userPrompt, label)
        LlmProvider.GEMINI -> completeGemini(config, systemPrompt, userPrompt, jsonMode, label)
    }

    private suspend fun completeOpenAiCompatible(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean,
        label: String
    ): String {
        val payload = buildJsonObject {
            put("model", config.model)
            put("stream", false)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }
            if (jsonMode) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
            applyOpenAiCompatibleThinking(config)
            if (config.effort == "none") {
                put("temperature", 0.3)
            }
        }.toString()
        val responseBody = postJsonWithRetry(
            label = label,
            url = config.baseUrl + "/chat/completions",
            body = payload,
            headers = authHeaders(config)
        )
        val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), responseBody)
        return parsed.choices.firstOrNull()?.message?.content
            ?: error("${config.provider.displayName} 响应缺 content")
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.applyOpenAiCompatibleThinking(config: LlmConfig) {
        when (config.provider) {
            LlmProvider.DEEPSEEK -> {
                val enabled = config.effort != "none"
                putJsonObject("thinking") { put("type", if (enabled) "enabled" else "disabled") }
                if (enabled) put("reasoning_effort", deepSeekEffort(config.effort))
            }
            LlmProvider.OPENAI -> {
                put("reasoning_effort", openAiEffort(config.effort))
            }
            else -> Unit
        }
    }

    private suspend fun completeOllamaCloud(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean,
        label: String
    ): String {
        val payload = buildJsonObject {
            put("model", config.model)
            put("stream", false)
            if (jsonMode) put("format", "json")
            ollamaThink(config.effort)?.let { think ->
                when (think) {
                    is Boolean -> put("think", think)
                    is String -> put("think", think)
                }
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }
        }.toString()
        val responseBody = postJsonWithRetry(
            label = label,
            url = config.baseUrl + "/chat",
            body = payload,
            headers = mapOf("Authorization" to "Bearer ${config.apiKey}")
        )
        val root = json.parseToJsonElement(responseBody).jsonObject
        return root["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: error("Ollama 响应缺 message.content")
    }

    private suspend fun completeAnthropic(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String,
        label: String
    ): String {
        val thinkingBudget = anthropicThinkingBudget(config.effort)
        val maxTokens = if (thinkingBudget != null) (thinkingBudget + 4096).coerceAtLeast(8192) else 4096
        val payload = buildJsonObject {
            put("model", config.model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            if (thinkingBudget != null) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", thinkingBudget)
                }
            } else {
                put("temperature", 0.3)
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }
        }.toString()
        val responseBody = postJsonWithRetry(
            label = label,
            url = config.baseUrl + "/messages",
            body = payload,
            headers = mapOf(
                "x-api-key" to config.apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )
        val root = json.parseToJsonElement(responseBody).jsonObject
        return root["content"]?.jsonArray
            ?.mapNotNull { block ->
                val obj = block.jsonObject
                if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                    obj["text"]?.jsonPrimitive?.contentOrNull
                } else null
            }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
            ?: error("Anthropic 响应缺 text content")
    }

    private suspend fun completeGemini(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean,
        label: String
    ): String {
        val payload = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemPrompt) }) }
            }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", userPrompt) }) }
                })
            }
            putJsonObject("generationConfig") {
                if (jsonMode) put("responseMimeType", "application/json")
                geminiThinkingConfig(config.model, config.effort)?.let { put("thinkingConfig", it) }
            }
        }.toString()
        val responseBody = postJsonWithRetry(
            label = label,
            url = config.baseUrl + "/models/${config.model}:generateContent",
            body = payload,
            headers = mapOf("x-goog-api-key" to config.apiKey)
        )
        val root = json.parseToJsonElement(responseBody).jsonObject
        return root["candidates"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
            ?: error("Gemini 响应缺 text content")
    }

    private suspend fun postJsonWithRetry(
        label: String,
        url: String,
        body: String,
        headers: Map<String, String>
    ): String = withRetry(label) {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> if (v.isNotBlank()) builder.header(k, v) }
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(builder.build()).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw LlmHttpException(resp.code, responseBody.take(400))
                responseBody
            }
        }
    }

    private fun authHeaders(config: LlmConfig): Map<String, String> =
        if (config.apiKey.isBlank() && config.provider == LlmProvider.OLLAMA) emptyMap()
        else mapOf("Authorization" to "Bearer ${config.apiKey}")

    private fun deepSeekEffort(effort: String): String = when (effort) {
        "max", "xhigh" -> "max"
        else -> "high"
    }

    private fun openAiEffort(effort: String): String = when (effort) {
        "none", "minimal", "low", "medium", "high", "xhigh" -> effort
        "max" -> "xhigh"
        else -> "medium"
    }

    private fun anthropicThinkingBudget(effort: String): Int? = when (effort) {
        "none" -> null
        "low", "minimal" -> 1024
        "medium" -> 4096
        "high" -> 8192
        "max", "xhigh" -> 12000
        else -> 8192
    }

    private fun geminiThinkingConfig(model: String, effort: String): JsonObject? {
        if (effort == "none" && (model.contains("pro", ignoreCase = true) || model.startsWith("gemini-3"))) {
            return null
        }
        return if (model.startsWith("gemini-3")) {
            buildJsonObject {
                put("thinkingLevel", when (effort) {
                    "none", "minimal" -> if (model.contains("flash", ignoreCase = true)) "minimal" else "low"
                    "low" -> "low"
                    "medium" -> if (model.contains("flash", ignoreCase = true)) "medium" else "low"
                    "max", "xhigh", "high" -> "high"
                    else -> if (model.contains("flash", ignoreCase = true)) "medium" else "high"
                })
            }
        } else {
            buildJsonObject {
                put("thinkingBudget", when (effort) {
                    "none" -> 0
                    "low", "minimal" -> 1024
                    "medium" -> 8192
                    "high" -> 24576
                    "max", "xhigh" -> 32768
                    else -> 24576
                })
            }
        }
    }

    private fun ollamaThink(effort: String): Any? = when (effort) {
        "none" -> false
        "low", "minimal" -> "low"
        "medium" -> "medium"
        "high", "max", "xhigh" -> "high"
        else -> true
    }

    /**
     * 通用重试包装。`block` 是可挂起的实际调用；返回它的结果或最终异常。
     * 仅对 5xx / 429 / IOException 重试 —— 4xx 是配置/鉴权问题，重试只会浪费配额。
     */
    private suspend fun <T> withRetry(label: String, block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (t: Throwable) {
                lastError = t
                if (!isRetryable(t) || attempt == MAX_ATTEMPTS - 1) {
                    logger.e("LLM", "$label give up after ${attempt + 1} attempt(s): ${t.message?.take(120)}")
                    throw t
                }
                val backoff = min(BASE_BACKOFF_MS shl attempt, MAX_BACKOFF_MS)
                logger.w("LLM", "$label retry ${attempt + 1}/${MAX_ATTEMPTS - 1} after ${backoff}ms: ${t.message?.take(120)}")
                delay(backoff)
            }
        }
        // 不应到达
        throw lastError ?: IllegalStateException("$label retry loop exited unexpectedly")
    }

    private fun isRetryable(t: Throwable): Boolean = when (t) {
        is LlmHttpException -> t.code == 429 || t.code in 500..599
        is IOException -> true                     // 网络中断 / 超时 / 连接重置
        else -> false
    }

    private class LlmHttpException(val code: Int, body: String) :
        RuntimeException("LLM HTTP $code: $body")

    /** 按当前 provider 从官方模型接口拉取模型列表，过滤出适合做文本处理的模型。 */
    suspend fun fetchAvailableModels(): Result<List<String>> = runCatching {
        val config = currentConfig()
        if (config.provider.requiresApiKey && config.apiKey.isBlank()) error("${config.provider.displayName} API Key 未配置")
        logger.i("LLM", "fetchAvailableModels begin provider=${config.provider.name} base=${config.baseUrl}")
        val filtered = when (config.provider) {
            LlmProvider.ANTHROPIC -> fetchAnthropicModels(config)
            LlmProvider.GEMINI -> fetchGeminiModels(config)
            LlmProvider.OLLAMA -> fetchOllamaTags(config)
            else -> fetchOpenAiModels(config).getOrThrow()
        }.filterModelIds()
        logger.i("LLM", "fetchAvailableModels ok provider=${config.provider.name} filtered=${filtered.size}")
        filtered
    }.onFailure { e ->
        logger.e("LLM", "fetchAvailableModels failed: ${e.message?.take(200)}", e)
    }

    private suspend fun fetchOpenAiModels(config: LlmConfig): Result<List<String>> = runCatching {
        val req = Request.Builder()
            .url(config.baseUrl + "/models")
            .apply { authHeaders(config).forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        getModelIdsFromDataArray(req)
    }

    private suspend fun fetchAnthropicModels(config: LlmConfig): List<String> {
        val req = Request.Builder()
            .url(config.baseUrl + "/models?limit=1000")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .get()
            .build()
        return getModelIdsFromDataArray(req)
    }

    private suspend fun fetchGeminiModels(config: LlmConfig): List<String> {
        val req = Request.Builder()
            .url(config.baseUrl + "/models")
            .header("x-goog-api-key", config.apiKey)
            .get()
            .build()
        val root = getJson(req).jsonObject
        return root["models"]?.jsonArray
            ?.mapNotNull { model ->
                model.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
            }
            .orEmpty()
    }

    private suspend fun fetchOllamaTags(config: LlmConfig): List<String> {
        val req = Request.Builder()
            .url(config.baseUrl + "/tags")
            .header("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        val root = getJson(req).jsonObject
        return root["models"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
    }

    private suspend fun getModelIdsFromDataArray(req: Request): List<String> {
        val root = getJson(req).jsonObject
        return root["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
    }

    private suspend fun getJson(req: Request): JsonElement = withContext(Dispatchers.IO) {
        okHttpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmHttpException(resp.code, body.take(400))
            json.parseToJsonElement(body)
        }
    }

    private fun List<String>.filterModelIds(): List<String> =
        filter {
            !it.contains("embed", ignoreCase = true) &&
                !it.contains("embedding", ignoreCase = true) &&
                !it.contains("vision", ignoreCase = true)
        }.distinct().sorted()

    suspend fun testConnection(): Result<Unit> = runCatching {
        val res = fetchAvailableModels().getOrThrow()
        if (res.isEmpty()) error("无可用模型")
    }

}
