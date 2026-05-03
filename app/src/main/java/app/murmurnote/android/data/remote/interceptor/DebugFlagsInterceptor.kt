package app.murmurnote.android.data.remote.interceptor

import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.di.ApplicationScope
import app.murmurnote.android.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 Debug 页的两个调试开关接到所有 GLM / Ollama 出站请求上：
 *  - 强制网络失败（debug_force_network_fail）：直接抛 IOException，让 Pipeline 走失败分支，便于
 *    复现"重新处理"路径。
 *  - 注入延时（debug_simulate_delay_ms）：在 chain.proceed 之前 sleep 给定毫秒，让 ASR/Ollama 看起来很慢，
 *    用来手测 UI 进度卡片的渐变与超时阈值。
 *
 * 之所以不直接在 Interceptor 里 `runBlocking { prefs.first() }`：那会阻塞 OkHttp Dispatcher 线程，
 * 大量并发请求时容易卡死。这里维护一对 volatile 字段，用 ApplicationScope 一次性订阅 Prefs Flow 来更新，
 * Interceptor 同步读 = 正常路径无开销。
 */
@Singleton
class DebugFlagsInterceptor @Inject constructor(
    private val appPreferences: AppPreferences,
    private val logger: Logger,
    @ApplicationScope private val scope: CoroutineScope
) : Interceptor {

    @Volatile private var forceFail: Boolean = false
    @Volatile private var simulateDelayMs: Long = 0L

    init {
        scope.launch {
            appPreferences.debugForceNetworkFail.collect { forceFail = it }
        }
        scope.launch {
            appPreferences.debugSimulateDelayMs.collect { simulateDelayMs = it }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (forceFail) {
            logger.w("Debug", "force-fail toggle on → throwing IOException for ${chain.request().url}")
            throw IOException("Debug: forced network failure")
        }
        val d = simulateDelayMs
        if (d > 0) {
            logger.d("Debug", "simulate-delay toggle on → sleeping ${d}ms before ${chain.request().url}")
            try {
                Thread.sleep(d)
            } catch (e: InterruptedException) {
                // 让 OkHttp 自己处理：保留中断标志，重抛为 IOException 等价物。
                Thread.currentThread().interrupt()
                throw IOException("Debug delay interrupted", e)
            }
        }
        return chain.proceed(chain.request())
    }
}
