package app.murmurnote.android.domain.pipeline

import java.util.concurrent.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineFailurePolicyTest {

    @Test
    fun persistedFailureNeverIncludesAnUpstreamBodyOrTranscript() {
        val secret = "用户逐字稿和上游响应正文"

        val message = PipelineFailurePolicy.persistedFailure(
            stageName = "extract",
            failure = IllegalStateException(secret)
        )

        assertFalse(message.contains(secret))
        assertTrue(message.contains("extract"))
        assertTrue(message.contains("IllegalStateException"))
    }

    @Test
    fun onlyKnownCancellationReasonsArePersistedVerbatim() {
        val unknown = PipelineFailurePolicy.persistedCancellation(
            CancellationException("transcript text")
        )
        val user = PipelineFailurePolicy.persistedCancellation(
            CancellationException("用户取消处理，可手动重试")
        )

        assertFalse(unknown.contains("transcript text"))
        assertTrue(user.startsWith("用户取消"))
    }
}
