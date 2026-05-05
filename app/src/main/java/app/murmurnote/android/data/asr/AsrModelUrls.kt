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

    /** FireRedASR v2 CTC int8（约 220MB 压缩），里面含 encoder.int8.onnx / decoder.int8.onnx / tokens.txt。 */
    const val FIRE_RED_ASR_TARBALL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.tar.bz2"

    /** 解压后顶层目录名，用于 tar 解包后从临时位置 rename 到 fire_red_asr_v2/ 。 */
    const val FIRE_RED_ASR_TARBALL_TOP_DIR = "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25"

    /** 标称大小（字节），UI 进度条与 ETA 用。压缩包约 220MB。 */
    const val FIRE_RED_ASR_TARBALL_BYTES = 220L * 1024 * 1024

    /** 期望 SHA256（小写 hex）。空表示跳过校验（仅集成期临时）。 */
    const val FIRE_RED_ASR_TARBALL_SHA256 = ""

    /**
     * 解压后所有 .onnx 文件**总和**的最小字节数，作为"已下载完整模型"的健全性判定。
     * CTC 版只有一个 model.int8.onnx (~220MB)；AED 版是 encoder + decoder 总和也接近。
     * 设 100MB 阈值能覆盖两种布局，不会误把"刚解压一半"的中间态当成就绪。
     */
    const val MODEL_MIN_TOTAL_BYTES = 100L * 1024 * 1024

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
