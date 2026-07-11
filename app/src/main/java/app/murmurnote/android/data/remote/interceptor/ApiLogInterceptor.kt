package app.murmurnote.android.data.remote.interceptor

import app.murmurnote.android.data.local.dao.ApiLogDao
import app.murmurnote.android.data.local.entity.ApiLog
import app.murmurnote.android.di.ApplicationScope
import app.murmurnote.android.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 GLM/Ollama 的请求元数据落到 api_logs 表，Debug 页可见。
 * 请求和响应正文可能包含录音、转写或提示词，因此不会读取、记录或落库。
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

        val safeUrl = ApiLogCapturePolicy.sanitizeUrl(request.url.toString())
        val apiName = when {
            request.url.host.contains("bigmodel") -> "GLM-ASR"
            request.url.host.contains("ollama") -> "Ollama"
            else -> request.url.host
        }

        logger.i(
            "HTTP",
            "request started",
            fields = mapOf(
                "apiName" to apiName,
                "method" to request.method,
                "url" to safeUrl
            )
        )
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            val durationMs = System.currentTimeMillis() - startTime
            val errorMessage = ApiLogCapturePolicy.errorType(e)
            logger.e(
                "HTTP",
                "request failed",
                fields = mapOf(
                    "apiName" to apiName,
                    "method" to request.method,
                    "url" to safeUrl,
                    "status" to -1,
                    "durationMs" to durationMs,
                    "error" to errorMessage
                )
            )
            scope.launch {
                runCatching {
                    apiLogDao.insert(
                        ApiLog(
                            timestamp = startTime,
                            apiName = apiName,
                            method = request.method,
                            url = safeUrl,
                            requestBody = null,
                            responseCode = -1,
                            responseBody = null,
                            durationMs = durationMs,
                            errorMessage = errorMessage
                        )
                    )
                    apiLogDao.trimToNewest(API_LOG_KEEP)
                }
            }
            throw e
        }

        val durMs = System.currentTimeMillis() - startTime
        val fields = mapOf(
            "apiName" to apiName,
            "method" to request.method,
            "url" to safeUrl,
            "status" to response.code,
            "durationMs" to durMs
        )
        if (response.code in 200..399) {
            logger.i("HTTP", "request completed", fields = fields)
        } else {
            logger.e("HTTP", "request completed with HTTP error", fields = fields)
        }

        scope.launch {
            runCatching {
                apiLogDao.insert(
                    ApiLog(
                            timestamp = startTime,
                            apiName = apiName,
                            method = request.method,
                            url = safeUrl,
                            requestBody = null,
                            responseCode = response.code,
                            responseBody = null,
                            durationMs = durMs,
                            errorMessage = null
                        )
                )
                apiLogDao.trimToNewest(API_LOG_KEEP)
            }
        }
        return response
    }
}
