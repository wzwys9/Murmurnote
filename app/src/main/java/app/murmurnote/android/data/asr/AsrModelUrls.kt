package app.murmurnote.android.data.asr

/**
 * 本地 ASR 模型下载源 + 校验信息。所有 URL 集中在此，未来换 CDN / 换模型版本只改这里。
 *
 * 选用 FireRedASR v2 CTC int8（速度优先，准确率仅次于 AED 版）。
 *
 * SHA256 占位：首次本地下载一次官方 tar.bz2 后用 `sha256sum` 算出真实值填入；为空时 AsrModelManager
 * 会跳过校验并在日志里 WARN，方便集成期跑通；上线前必须填实值，否则 release 构建里只能下到不可信文件。
 */
object AsrModelUrls {

    /** FireRedASR v2 AED int8（encoder+decoder，带标点），约 1.2GB。 */
    const val FIRE_RED_ASR_TARBALL =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26"

    /** 解压后顶层目录名，用于 tar 解包后从临时位置 rename 到 fire_red_asr_v2/ 。 */
    const val FIRE_RED_ASR_TARBALL_TOP_DIR = "sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26"

    /** 标称大小（字节），UI 进度条与 ETA 用。encoder ~780MB + decoder ~398MB。 */
    const val FIRE_RED_ASR_TARBALL_BYTES = 1200L * 1024 * 1024

    /** 期望 SHA256（小写 hex）。空表示跳过校验（仅集成期临时）。 */
    const val FIRE_RED_ASR_TARBALL_SHA256 = ""

    /**
     * 解压后所有 .onnx 文件**总和**的最小字节数，作为"已下载完整模型"的健全性判定。
     * AED 版 encoder 780MB + decoder 398MB ≈ 1.18GB。设 500MB 阈值。
     */
    const val MODEL_MIN_TOTAL_BYTES = 500L * 1024 * 1024

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
