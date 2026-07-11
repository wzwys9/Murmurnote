package app.murmurnote.android.domain.pipeline

import java.util.concurrent.CancellationException

internal object PipelineFailurePolicy {
    private val knownCancellationMessages = setOf(
        "用户取消处理，可手动重试",
        "处理服务被系统中断，可手动重试"
    )

    fun persistedFailure(stageName: String, failure: Throwable): String =
        "${stageName.take(32)} 阶段失败（${failure.javaClass.simpleName}），可手动重试"

    fun persistedCancellation(failure: CancellationException): String =
        failure.message?.takeIf(knownCancellationMessages::contains)
            ?: "处理被中断，可手动重试"
}
