package app.murmurnote.android.data.remote.glm

import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.data.remote.glm.dto.AsrChunk
import app.murmurnote.android.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GLM-ASR-2512 客户端。
 * 关键约束（NOTES 第一节，违反 → HTTP 400 code 1214）：
 *   - 文件格式仅 wav / mp3
 *   - 单声道
 *   - 单段 ≤ 30s（我们走 25s 安全边界）
 * 上传一律走 multipart/form-data；流式用 SSE（OkHttp EventSource）。
 */
@Singleton
class GlmAsrClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val appPreferences: AppPreferences,
    private val json: Json,
    private val logger: Logger
) {

    sealed class Event {
        data class Delta(val text: String) : Event()
        data class Done(val finalText: String) : Event()
        data class Error(val code: Int, val message: String) : Event()
    }

    /** 流式转写一段 wav 文件（≤25s），逐字 emit Delta，结束 emit Done。 */
    fun transcribeStream(wav: File, hotwords: List<String> = emptyList(), prompt: String? = null): Flow<Event> = callbackFlow {
        val baseUrl = appPreferences.glmBaseUrl.first()
        val key = appPreferences.glmApiKey.first()
        if (key.isBlank()) {
            trySend(Event.Error(401, "GLM API Key 未配置"))
            close()
            return@callbackFlow
        }
        val url = baseUrl.trimEnd('/') + "/audio/transcriptions"

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "glm-asr-2512")
            .addFormDataPart("stream", "true")
            .addFormDataPart(
                "file",
                wav.name,
                wav.asRequestBody("audio/wav".toMediaTypeOrNull())
            )
        if (hotwords.isNotEmpty()) {
            val hotwordsJson = hotwords.joinToString(prefix = "[", postfix = "]") {
                "\"" + it.replace("\"", "\\\"") + "\""
            }
            multipart.addFormDataPart("hotwords", hotwordsJson)
        }
        if (!prompt.isNullOrBlank()) multipart.addFormDataPart("prompt", prompt)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(multipart.build())
            .build()

        val accumulator = StringBuilder()
        var eventCount = 0
        // [DONE] / onClosed 之后我们会主动 close() callbackFlow，awaitClose 触发 es.cancel()，
        // OkHttp 紧接着以 "stream was reset: CANCEL" 回调 onFailure。这是预期路径，不是真失败；
        // 用这个标志把后续的 onFailure 降级为 debug 信息，避免 runtime.log 里满屏假红线。
        var streamFinished = false
        val factory = EventSources.createFactory(okHttpClient)
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                logger.i("ASR", "SSE open ${response.code} for ${wav.name}")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                eventCount++
                if (eventCount <= 3 || eventCount % 20 == 0) {
                    logger.d("ASR", "SSE evt#$eventCount type=$type data=${data.take(200)}")
                }
                if (data == "[DONE]") {
                    logger.i("ASR", "SSE [DONE] for ${wav.name}, total=${accumulator.length} chars: ${accumulator.toString().take(200)}")
                    streamFinished = true
                    trySend(Event.Done(accumulator.toString()))
                    close()
                    return
                }
                val piece = parseDelta(data)
                if (!piece.isNullOrEmpty()) {
                    accumulator.append(piece)
                    trySend(Event.Delta(piece))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                logger.i("ASR", "SSE closed for ${wav.name}, events=$eventCount accumulated=${accumulator.length} chars: ${accumulator.toString().take(200)}")
                streamFinished = true
                if (accumulator.isNotEmpty()) trySend(Event.Done(accumulator.toString()))
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code ?: -1
                val body = runCatching { response?.body?.string() }.getOrNull()
                val msg = t?.message ?: body ?: "GLM-ASR SSE 失败"
                if (streamFinished) {
                    // 我们自己 cancel 后 OkHttp 回调过来的；累计文字已经通过 Event.Done 发出，这里不要再 emit Error。
                    logger.d("ASR", "SSE post-finish cancel ignored for ${wav.name}: ${msg.take(120)}")
                } else {
                    logger.e("ASR", "SSE failure code=$code msg=$msg body=${body?.take(400).orEmpty()}", t)
                    trySend(Event.Error(code, msg))
                }
                close()
            }
        }
        val es = factory.newEventSource(request, listener)
        awaitClose { es.cancel() }
    }

    /**
     * 兼容多种 SSE delta 格式：
     *   1) GLM 原生：{"type":"transcript.text.delta","delta":"x"}
     *   2) GLM 完成：{"type":"transcript.text.done"}
     *   3) OpenAI 兼容：{"choices":[{"delta":{"content":"x"}}]}
     *   4) 非流式：{"text":"完整文字"}
     */
    private fun parseDelta(data: String): String? {
        val obj = runCatching { json.parseToJsonElement(data) }
            .getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null

        when (str(obj["type"])) {
            "transcript.text.delta" -> return str(obj["delta"])
            "transcript.text.done" -> return null
        }
        str(obj["text"])?.takeIf { it.isNotEmpty() }?.let { return it }

        val choices = obj["choices"] as? kotlinx.serialization.json.JsonArray ?: return null
        val first = choices.firstOrNull() as? kotlinx.serialization.json.JsonObject ?: return null
        val deltaContent = str((first["delta"] as? kotlinx.serialization.json.JsonObject)?.get("content"))
        if (!deltaContent.isNullOrEmpty()) return deltaContent
        return str((first["message"] as? kotlinx.serialization.json.JsonObject)?.get("content"))
    }

    private fun str(el: kotlinx.serialization.json.JsonElement?): String? {
        val p = el as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (p is kotlinx.serialization.json.JsonNull) return null
        return p.content
    }

    /** 测试 Key 是否有效（用一个故意空的 multipart 调用，看 401 与否）。 */
    suspend fun testConnection(): Result<Unit> = runCatching {
        val baseUrl = appPreferences.glmBaseUrl.first()
        val key = appPreferences.glmApiKey.first()
        if (key.isBlank()) error("API Key 未配置")
        val url = baseUrl.trimEnd('/') + "/audio/transcriptions"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "glm-asr-2512")
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        // 必须切到 IO 线程：execute() 是同步阻塞，主线程会 NetworkOnMainThreadException
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(req).execute().use { r ->
                when (r.code) {
                    401, 403 -> error("API Key 无效（HTTP ${r.code}）")
                    in 200..399 -> Unit
                    400 -> Unit // 故意空 multipart，能拿到 400 说明 Key 有效
                    else -> error("HTTP ${r.code}: ${r.body?.string().orEmpty().take(200)}")
                }
            }
        }
    }
}
