package app.murmurnote.android.data.remote.ollama

import android.content.Context
import app.murmurnote.android.R
import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.data.remote.ollama.dto.ChatCompletionRequest
import app.murmurnote.android.data.remote.ollama.dto.ChatCompletionResponse
import app.murmurnote.android.data.remote.ollama.dto.ChatMessage
import app.murmurnote.android.data.remote.ollama.dto.ExtractedItemDto
import app.murmurnote.android.data.remote.ollama.dto.ExtractionResult
import app.murmurnote.android.data.remote.ollama.dto.ModelsResponse
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
class OllamaClient @Inject constructor(
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
        const val CHUNK_TARGET_CHARS = 1_500
        // 上限保护:无论多长都不超过这个块数,超出部分合并到最后一块。
        const val MAX_CHUNKS = 6
        // 同时跑的分块抽取数:OkHttp dispatcher 默认每 host 5 并发,3 留余地给同时跑的 ASR。
        const val CHUNK_CONCURRENCY = 3
    }

    /**
     * 调用 chat completions 让 DeepSeek 提取结构化信息。
     * 对可重试错误（5xx / 429 / 网络异常）做指数退避；4xx（鉴权 / 请求格式）即时失败不重试，省得浪费配额。
     */
    suspend fun extractItems(transcript: String): Result<ExtractionResult> = runCatching {
        val baseUrl = appPreferences.ollamaBaseUrl.first()
        val key = appPreferences.ollamaApiKey.first()
        val model = appPreferences.ollamaModel.first()
        val effort = appPreferences.reasoningEffort.first()
        if (key.isBlank()) error("Ollama API Key 未配置")

        val systemPromptOverride = appPreferences.systemPromptOverride.first()
        val userPromptOverride = appPreferences.userPromptOverride.first()
        val systemPrompt = systemPromptOverride?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.prompt_extract_system)
        val userPromptTemplate = userPromptOverride?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.prompt_extract_user_template)
        val userPrompt = userPromptTemplate.format(transcript)

        val req = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userPrompt)
            ),
            reasoning_effort = effort.takeIf { it != "none" },
            temperature = 0.3,
            stream = false
        )
        val payload = json.encodeToString(ChatCompletionRequest.serializer(), req)
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        // 这一行让"提取阶段为什么这么慢"或"用了哪个 model + reasoning"在导出日志后一眼可见，
        // 不用再去翻 api_logs.txt 里的 JSON body。
        logger.i(
            "Ollama",
            "extractItems begin model=$model effort=${effort.takeIf { it != "none" } ?: "-"} promptOverride=${systemPromptOverride != null || userPromptOverride != null} chars=${transcript.length}"
        )
        val started = SystemClock.elapsedRealtime()

        val responseBody = withRetry("extractItems") {
            // 同一份 RequestBody 不能被复用（OkHttp 会在第一次失败后关掉），所以放进 retry 闭包里每次新建。
            val httpReq = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(httpReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        // 把 HTTP 状态码塞到异常里，让 isRetryable 据此判定
                        throw OllamaHttpException(resp.code, body.take(400))
                    }
                    body
                }
            }
        }

        val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), responseBody)
        val rawContent = parsed.choices.firstOrNull()?.message?.content
            ?: error("Ollama 响应缺 content")

        val cleaned = stripThink(rawContent).trim()
        val jsonStr = extractJsonObject(cleaned)
            ?: run {
                logger.e("Ollama", "extractItems: no JSON object in response: ${cleaned.take(400)}")
                error("无法从响应抽取 JSON: ${cleaned.take(400)}")
            }
        val parsedResult = json.decodeFromString(ExtractionResult.serializer(), jsonStr)
        // 兜底:即便 prompt 已经强调"summary 不可为空", LLM 偶尔仍会顽固返回 "":这里用转写原文合成
        // 一条 bullet,保证详情页永远有总结可看。一句话型短录音用全文,长录音截 80 字。
        val finalResult = if (parsedResult.summary.isBlank()) {
            val snippet = transcript.replace(Regex("\\s+"), " ").trim().take(80)
            val synthesized = "• 用户的录音内容：$snippet"
            logger.w("Ollama", "extractItems synthesized summary (LLM returned blank) chars=${snippet.length}")
            ExtractionResult(synthesized, parsedResult.items)
        } else {
            parsedResult
        }
        logger.i(
            "Ollama",
            "extractItems ok items=${finalResult.items.size} summaryChars=${finalResult.summary.length} elapsed=${SystemClock.elapsedRealtime() - started}ms"
        )
        finalResult
    }.onFailure { e ->
        logger.e("Ollama", "extractItems failed: ${e.message?.take(200)}", e)
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
                "Ollama",
                "extractItemsAuto LONG chars=${transcript.length} chunks=${chunks.size}"
            )
            val parts: List<ExtractionResult> = coroutineScope {
                val sem = Semaphore(CHUNK_CONCURRENCY)
                chunks.mapIndexed { idx, chunk ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            val r = extractItems(chunk).getOrThrow()
                            logger.d(
                                "Ollama",
                                "chunk ${idx + 1}/${chunks.size} chars=${chunk.length} items=${r.items.size} summaryChars=${r.summary.length}"
                            )
                            r
                        }
                    }
                }.awaitAll()
            }
            val merged = mergeExtractions(parts)
            logger.i(
                "Ollama",
                "extractItemsAuto merged items=${merged.items.size} summaryChars=${merged.summary.length} elapsed=${SystemClock.elapsedRealtime() - started}ms"
            )
            merged
        }.onFailure { e ->
            logger.e("Ollama", "extractItemsAuto failed: ${e.message?.take(200)}", e)
        }
    }

    /** 按句末标点切句,贪心拼成 ~CHUNK_TARGET_CHARS 字的块。最多 MAX_CHUNKS 块,超出部分合并到末块。 */
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

        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + s.length > CHUNK_TARGET_CHARS) {
                chunks += cur.toString()
                cur.clear()
            }
            cur.append(s)
        }
        if (cur.isNotEmpty()) chunks += cur.toString()

        if (chunks.size <= MAX_CHUNKS) return chunks
        // 超出 MAX_CHUNKS:把溢出的尾部全部并到最后一块,避免无界并发。
        val head = chunks.take(MAX_CHUNKS - 1)
        val tail = chunks.drop(MAX_CHUNKS - 1).joinToString("")
        return head + tail
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
            logger.w("Ollama", "merge LLM failed, fallback to dedup-concat: ${e.message?.take(120)}")
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
        val baseUrl = appPreferences.ollamaBaseUrl.first()
        val key = appPreferences.ollamaApiKey.first()
        val model = appPreferences.ollamaModel.first()
        val effort = appPreferences.reasoningEffort.first()
        if (key.isBlank()) error("Ollama API Key 未配置")

        val mergerSystem = """
            你是一名编辑,负责把同一段录音被拆开后产出的多份子摘要,合并成一份统一的最终摘要。
            每份子摘要都按 "• 主题：...\n• 背景：...\n• 事实：..." 的 bullet 格式给出。
            合并要求:
            1. 输出仍然是 bullet 列表,每条以 "• " 开头,行间用 \n 换行,不要 markdown 围栏。
            2. 第一条必须是 "• 主题：xxx",一句话概括整段录音的总主题(综合所有子摘要里的"主题"行)。不要保留多个"主题"行。
            3. 之后按 背景 → 事实 → 计划 → 决定 → 待办 的顺序分组,组内合并去重(意思相同的合并,关键事实不丢)。
            4. 6-15 条为宜,长录音可适当更多,但不要灌水。
            5. 不输出任何解释、思考过程、前后缀,只输出最终的 bullet 列表本身。
        """.trimIndent()

        val mergerUser = buildString {
            append("以下是同一段录音被分块抽取后得到的多份子摘要,请合并成统一的最终摘要:\n\n")
            summaries.forEachIndexed { i, s ->
                append("[子摘要 ${i + 1}]\n")
                append(s)
                append("\n\n")
            }
            append("直接输出合并后的 bullet 列表,不要任何前后文。")
        }

        val req = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", mergerSystem),
                ChatMessage("user", mergerUser)
            ),
            reasoning_effort = effort.takeIf { it != "none" },
            temperature = 0.3,
            stream = false
        )
        val payload = json.encodeToString(ChatCompletionRequest.serializer(), req)
        val url = baseUrl.trimEnd('/') + "/chat/completions"

        val responseBody = withRetry("mergeSummaries") {
            val httpReq = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(httpReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw OllamaHttpException(resp.code, body.take(400))
                    body
                }
            }
        }
        val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), responseBody)
        val raw = parsed.choices.firstOrNull()?.message?.content
            ?: error("merge: 响应缺 content")
        stripThink(raw).trim().takeIf { it.isNotBlank() }
            ?: error("merge: 响应为空")
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
                    logger.e("Ollama", "$label give up after ${attempt + 1} attempt(s): ${t.message?.take(120)}")
                    throw t
                }
                val backoff = min(BASE_BACKOFF_MS shl attempt, MAX_BACKOFF_MS)
                logger.w("Ollama", "$label retry ${attempt + 1}/${MAX_ATTEMPTS - 1} after ${backoff}ms: ${t.message?.take(120)}")
                delay(backoff)
            }
        }
        // 不应到达
        throw lastError ?: IllegalStateException("$label retry loop exited unexpectedly")
    }

    private fun isRetryable(t: Throwable): Boolean = when (t) {
        is OllamaHttpException -> t.code == 429 || t.code in 500..599
        is IOException -> true                     // 网络中断 / 超时 / 连接重置
        else -> false
    }

    private class OllamaHttpException(val code: Int, body: String) :
        RuntimeException("Ollama HTTP $code: $body")

    /** 拉取模型列表，过滤出适合做文本处理的（带 :cloud，排除 embed/vision）。 */
    suspend fun fetchAvailableModels(): Result<List<String>> = runCatching {
        val baseUrl = appPreferences.ollamaBaseUrl.first()
        val key = appPreferences.ollamaApiKey.first()
        if (key.isBlank()) error("API Key 未配置")
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/models")
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        logger.i("Ollama", "fetchAvailableModels begin base=$baseUrl")
        val body = withContext(Dispatchers.IO) {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }
        val parsed = json.decodeFromString(ModelsResponse.serializer(), body)
        val filtered = parsed.data
            .map { it.id }
            .filter { !it.contains("embed", ignoreCase = true) && !it.contains("vl", ignoreCase = true) && !it.contains("vision", ignoreCase = true) }
            .sorted()
        logger.i("Ollama", "fetchAvailableModels ok total=${parsed.data.size} filtered=${filtered.size}")
        filtered
    }.onFailure { e ->
        logger.e("Ollama", "fetchAvailableModels failed: ${e.message?.take(200)}", e)
    }

    suspend fun testConnection(): Result<Unit> = runCatching {
        val res = fetchAvailableModels().getOrThrow()
        if (res.isEmpty()) error("无可用模型")
    }

    /** 去除 <think>...</think> */
    private fun stripThink(s: String): String {
        return Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE).replace(s, "")
            .replace("```json", "")
            .replace("```", "")
    }

    /** 从字符串中抽取最外层的 JSON 对象 */
    private fun extractJsonObject(s: String): String? {
        var depth = 0
        var start = -1
        var inString = false
        var escape = false
        for (i in s.indices) {
            val c = s[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
