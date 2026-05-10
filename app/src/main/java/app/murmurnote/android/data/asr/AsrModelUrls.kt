package app.murmurnote.android.data.asr

/**
 * 本地 ASR 模型下载源 + 校验信息。所有 URL 集中在此，未来换 CDN / 换模型版本只改这里。
 *
 * 选用 SenseVoiceSmall int8。它覆盖普通话、粤语和中英混说；体积明显小于 Qwen3-ASR。
 *
 * SHA256 必须配置真实值。AsrModelManager 会拒绝安装未配置 hash 或 hash 不匹配的下载文件。
 */
data class LocalAsrModelSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val tarballUrl: String,
    val tarballTopDir: String,
    val tarballBytes: Long,
    val tarballSha256: String,
    val minOnnxTotalBytes: Long,
    val supportsFastConcurrency: Boolean
) {
    val assetRoot: String = "asr_models/$id"
    val partialFileName: String = "$id.downloading"
    val sizeLabel: String = when {
        tarballBytes >= 1024L * 1024L * 1024L -> "%.1fGiB".format(tarballBytes / (1024.0 * 1024.0 * 1024.0))
        else -> "${tarballBytes / (1024 * 1024)}MiB"
    }
}

object AsrModelUrls {

    const val SENSE_VOICE_ID = "sense_voice_zh_en_ja_ko_yue"
    const val QWEN3_ASR_ID = "qwen3_asr_0_6b"

    /** SenseVoiceSmall int8，压缩包约 155MiB，解压后运行文件约 228MiB。 */
    const val SENSE_VOICE_TARBALL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"

    /** 解压后顶层目录名，用于 tar 解包后从临时位置 rename 到 sense_voice_zh_en_ja_ko_yue/ 。 */
    const val SENSE_VOICE_TARBALL_TOP_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"

    /** 标称大小（字节），UI 进度条与 ETA 用。 */
    const val SENSE_VOICE_TARBALL_BYTES = 163_002_883L

    /** 期望 SHA256（小写 hex）。不能为空，否则拒绝安装。 */
    const val SENSE_VOICE_TARBALL_SHA256 = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"

    /**
     * 解压后所有 .onnx 文件总和的健全性判定阈值。SenseVoice int8 的 ONNX 文件约 228MB。
     */
    const val SENSE_VOICE_MIN_TOTAL_BYTES = 200L * 1024 * 1024

    /** Qwen3-ASR 0.6B int8，压缩包约 838MiB，解压后运行文件约 940MiB。 */
    const val QWEN3_ASR_TARBALL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"

    /** 解压后顶层目录名，用于 tar 解包后从临时位置 rename 到 qwen3_asr_0_6b/ 。 */
    const val QWEN3_ASR_TARBALL_TOP_DIR = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"

    /** 标称大小（字节），UI 进度条与 ETA 用。 */
    const val QWEN3_ASR_TARBALL_BYTES = 878_702_423L

    /** 期望 SHA256（小写 hex）。不能为空，否则拒绝安装。 */
    const val QWEN3_ASR_TARBALL_SHA256 = "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96"

    const val QWEN3_ASR_MIN_TOTAL_BYTES = 850L * 1024 * 1024

    val MODELS: List<LocalAsrModelSpec> = listOf(
        LocalAsrModelSpec(
            id = SENSE_VOICE_ID,
            displayName = "SenseVoiceSmall int8",
            description = "小模型，中文/粤语/中英混说，支持 1x-3x 并行识别",
            tarballUrl = SENSE_VOICE_TARBALL,
            tarballTopDir = SENSE_VOICE_TARBALL_TOP_DIR,
            tarballBytes = SENSE_VOICE_TARBALL_BYTES,
            tarballSha256 = SENSE_VOICE_TARBALL_SHA256,
            minOnnxTotalBytes = SENSE_VOICE_MIN_TOTAL_BYTES,
            supportsFastConcurrency = true
        ),
        LocalAsrModelSpec(
            id = QWEN3_ASR_ID,
            displayName = "Qwen3-ASR 0.6B int8",
            description = "大模型，准确率优先，体积和内存占用明显更高",
            tarballUrl = QWEN3_ASR_TARBALL,
            tarballTopDir = QWEN3_ASR_TARBALL_TOP_DIR,
            tarballBytes = QWEN3_ASR_TARBALL_BYTES,
            tarballSha256 = QWEN3_ASR_TARBALL_SHA256,
            minOnnxTotalBytes = QWEN3_ASR_MIN_TOTAL_BYTES,
            supportsFastConcurrency = false
        )
    )

    const val DEFAULT_MODEL_ID = SENSE_VOICE_ID

    fun modelById(id: String?): LocalAsrModelSpec =
        MODELS.firstOrNull { it.id == id } ?: MODELS.first()

    /**
     * 镜像前缀。空字符串代表官方直连。手动切换在设置页生效；自动回落由 AsrModelManager 在
     * "持续 10s 速度低于 50KB/s" 触发。
     */
    val MIRROR_PREFIXES: List<String> = listOf(
        "",
        "https://mirror.ghproxy.com/",
        "https://gh-proxy.com/"
    )
}
