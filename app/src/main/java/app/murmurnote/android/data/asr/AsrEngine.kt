package app.murmurnote.android.data.asr

import java.io.File

/**
 * ASR 引擎抽象。云端 / 本地两套实现都满足这个契约。
 *
 * 输入：单文件，已是 mono 16kHz WAV（由 AudioPipeline 经 AudioConverter + AudioSplitter 切出来的 ≤25s 段）。
 * 输出：完整文字 + 段时长，便于 Pipeline 入库 transcript_segments。
 *
 * 不再用 Flow<Event>：Pipeline 现在并发跑多段，每段只关心最终文本和进度回调，
 * Delta 流式只对单段实时 UI 有用，但 Pipeline 已经按段并发，没有"实时显示当前段中间结果"的需求。
 */
interface AsrEngine {

    val engineType: AsrEngineType

    /** 是否就绪：云端等价于"有 API Key"；本地等价于"模型已下载 + JNI 库已加载"。 */
    suspend fun isReady(): Boolean

    /**
     * 转写单个 wav 文件。onProgress 取值 0..1，仅本地引擎会持续上报；云端 SSE 通常一次拿到结果，
     * 实现可以只回 1f。
     */
    suspend fun transcribe(
        wav: File,
        onProgress: suspend (Float) -> Unit = {}
    ): Result<AsrResult>

    /** 释放资源。云端实现无成本，本地实现要 release OfflineRecognizer，否则会长期占用模型内存。 */
    fun release()
}

enum class AsrEngineType {
    CLOUD_GLM,
    LOCAL_SENSE_VOICE,
    LOCAL_QWEN3_ASR;

    fun isLocal(): Boolean =
        this == LOCAL_SENSE_VOICE || this == LOCAL_QWEN3_ASR

    companion object {
        fun parse(s: String?): AsrEngineType = when (s) {
            "LOCAL_FIRE_RED_ASR" -> LOCAL_SENSE_VOICE
            else -> entries.firstOrNull { it.name == s } ?: CLOUD_GLM
        }
    }
}

data class AsrResult(
    val text: String,
    val durationMs: Long
)

/** 本地引擎专用错误类型，便于 UI 区分原因展示对应行动。 */
sealed class LocalAsrError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** sherpa-onnx 的 AAR 没塞到 app/libs/，反射加载类失败。 */
    class NativeLibMissing(cause: Throwable) :
        LocalAsrError("sherpa-onnx 原生库未集成，请到 app/libs/ 放置 AAR 后重新构建", cause)

    /** 模型文件不存在或大小不合理。 */
    class ModelMissing(message: String) : LocalAsrError(message)

    /** 模型加载或 decode 阶段失败。 */
    class DecodeFailed(message: String, cause: Throwable? = null) : LocalAsrError(message, cause)
}
