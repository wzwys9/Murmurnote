package app.murmurnote.android.data.asr

import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.data.remote.glm.GlmAsrClient
import app.murmurnote.android.util.Logger
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云端 GLM-ASR 适配器。直接复用现有 GlmAsrClient.transcribeStream（保留所有 SSE 边界处理与
 * 4 种 delta 格式兼容），把 Flow<Event> 折叠成 suspend Result<AsrResult>。
 *
 * 没有"是否就绪"的本地资源概念，只看 GLM API Key 是否配置。
 */
@Singleton
class CloudAsrEngine @Inject constructor(
    private val glmAsrClient: GlmAsrClient,
    private val appPreferences: AppPreferences,
    private val logger: Logger
) : AsrEngine {

    override val engineType: AsrEngineType = AsrEngineType.CLOUD_GLM

    override suspend fun isReady(): Boolean = appPreferences.glmApiKey.first().isNotBlank()

    override suspend fun transcribe(
        wav: File,
        onProgress: suspend (Float) -> Unit
    ): Result<AsrResult> = runCatching {
        val sb = StringBuilder()
        var lastError: GlmAsrClient.Event.Error? = null

        glmAsrClient.transcribeStream(wav).collect { ev ->
            when (ev) {
                is GlmAsrClient.Event.Delta -> {
                    sb.append(ev.text)
                    // GLM 没有"已识别百分比"的语义，只能用累计字符长度做毛估。
                    // Pipeline 那边按段对齐进度（idx/total），所以这里给段内 0.5 表示"在进行"，结束再给 1f。
                    onProgress(0.5f)
                }
                is GlmAsrClient.Event.Done -> {
                    if (sb.isEmpty()) sb.append(ev.finalText)
                    onProgress(1f)
                }
                is GlmAsrClient.Event.Error -> {
                    lastError = ev
                }
            }
        }

        lastError?.let { err ->
            error("GLM-ASR 失败 code=${err.code}: ${err.message.take(120)}")
        }

        AsrResult(
            text = sb.toString(),
            durationMs = 0L // 段时长由 Pipeline 从 Slice 拿，引擎不需要知道
        )
    }.onFailure { logger.e("CloudAsr", "transcribe failed for ${wav.name}: ${it.message}", it) }

    override fun release() {
        // 云端无本地资源
    }
}
