package app.murmurnote.android.data.asr

/**
 * 本地 ASR 模型下载源 + 校验信息。所有 URL 集中在此，未来换 CDN / 换模型版本只改这里。
 *
 * 选用 Qwen3-ASR 0.6B int8。它覆盖普通话、粤语、多种中文方言和中英混说；代价是模型体积接近 1GB。
 *
 * SHA256 占位：首次本地下载一次官方 tar.bz2 后用 `sha256sum` 算出真实值填入；为空时 AsrModelManager
 * 会跳过校验并在日志里 WARN，方便集成期跑通；上线前必须填实值，否则 release 构建里只能下到不可信文件。
 */
object AsrModelUrls {

    /** Qwen3-ASR 0.6B int8，压缩包约 838MiB，解压后运行文件约 940MiB。 */
    const val QWEN3_ASR_TARBALL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"

    /** 解压后顶层目录名，用于 tar 解包后从临时位置 rename 到 qwen3_asr_0_6b/ 。 */
    const val QWEN3_ASR_TARBALL_TOP_DIR = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"

    /** 标称大小（字节），UI 进度条与 ETA 用。 */
    const val QWEN3_ASR_TARBALL_BYTES = 878_702_423L

    /** 期望 SHA256（小写 hex）。空表示跳过校验（仅集成期临时）。 */
    const val QWEN3_ASR_TARBALL_SHA256 = "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96"

    /**
     * 解压后所有 .onnx 文件总和的健全性判定阈值。Qwen3-ASR int8 三个 ONNX 文件合计约 937MB。
     */
    const val MODEL_MIN_TOTAL_BYTES = 800L * 1024 * 1024

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
