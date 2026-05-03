package app.murmurnote.android.data.remote.interceptor

import app.murmurnote.android.data.local.dao.ApiLogDao
import app.murmurnote.android.data.local.entity.ApiLog
import app.murmurnote.android.di.ApplicationScope
import app.murmurnote.android.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把所有 GLM/Ollama 的请求/响应都落到 api_logs 表，Debug 页可见。
 * 注意：multipart 请求体可能很大（音频），写入 DB 会用截断。
 */
@Singleton
class ApiLogInterceptor @Inject constructor(
    private val apiLogDao: ApiLogDao,
    private val logger: Logger,
    @ApplicationScope private val scope: CoroutineScope
) : Interceptor {

    private companion object {
        // api_logs 上限：超过这个数后每次写入都会顺便裁旧的，避免长期用户的表无限增长。
        // 500 条历史在导出包里看绰绰有余（最近 100 已经覆盖大多数排查场景）。
        const val API_LOG_KEEP = 500
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestBody = peekRequestBody(request) ?: ""
        val apiName = when {
            request.url.host.contains("bigmodel") -> "GLM-ASR"
            request.url.host.contains("ollama") -> "Ollama"
            else -> request.url.host
        }

        logger.i("HTTP", "${request.method} ${request.url}")
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            logger.e("HTTP", "${request.method} ${request.url} → IOException", e)
            scope.launch {
                runCatching {
                    apiLogDao.insert(
                        ApiLog(
                            timestamp = startTime,
                            apiName = apiName,
                            method = request.method,
                            url = request.url.toString(),
                            requestBody = requestBody.truncate(8 * 1024),
                            responseCode = -1,
                            responseBody = null,
                            durationMs = System.currentTimeMillis() - startTime,
                            errorMessage = e.message
                        )
                    )
                    apiLogDao.trimToNewest(API_LOG_KEEP)
                }
            }
            throw e
        }

        // 流式响应（SSE）不能 peek 整个 body，会阻塞流。这里只对 application/json 取 body。
        val ctype = response.body?.contentType()?.toString().orEmpty()
        val responseBody = if (ctype.startsWith("text/event-stream")) {
            "<sse stream — body not captured>"
        } else {
            runCatching { response.peekBody(256 * 1024).string() }.getOrNull()
        }
        val durMs = System.currentTimeMillis() - startTime
        if (response.code in 200..399) {
            logger.i("HTTP", "${request.method} ${request.url} → ${response.code} (${durMs}ms)")
        } else {
            // 错误响应把 body 摘要也写进文件日志，方便用户导出后排查
            logger.e(
                "HTTP",
                "${request.method} ${request.url} → ${response.code} (${durMs}ms) body=${responseBody?.take(800).orEmpty()}"
            )
        }

        scope.launch {
            runCatching {
                apiLogDao.insert(
                    ApiLog(
                        timestamp = startTime,
                        apiName = apiName,
                        method = request.method,
                        url = request.url.toString(),
                        requestBody = requestBody.truncate(8 * 1024),
                        responseCode = response.code,
                        responseBody = responseBody?.truncate(64 * 1024),
                        durationMs = System.currentTimeMillis() - startTime,
                        errorMessage = null
                    )
                )
                apiLogDao.trimToNewest(API_LOG_KEEP)
            }
        }
        return response
    }

    private fun peekRequestBody(request: okhttp3.Request): String? {
        val body = request.body ?: return null
        // 跳过太大的 multipart（音频）
        val ctype = body.contentType()?.toString().orEmpty()
        if (ctype.startsWith("multipart/")) return "<multipart — body not captured>"
        return runCatching {
            val buf = Buffer()
            body.writeTo(buf)
            buf.readUtf8()
        }.getOrNull()
    }

    private fun String.truncate(max: Int): String =
        if (length <= max) this else substring(0, max) + "...<truncated>"
}
