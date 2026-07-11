package app.murmurnote.android.data.repository

import app.murmurnote.android.data.local.entity.ProcessingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRetentionPolicyTest {
    @Test
    fun completedAndFailedAudioAreEligibleAtTheExpiryBoundary() {
        listOf(ProcessingStatus.COMPLETED, ProcessingStatus.FAILED).forEach { status ->
            assertTrue(
                AudioRetentionPolicy.isEligible(
                    audioAvailable = true,
                    keepAudio = false,
                    audioExpiresAt = 1_000L,
                    processingStatus = status,
                    nowMs = 1_000L
                )
            )
        }
    }

    @Test
    fun everyInProgressStatusIsProtectedFromRetention() {
        ProcessingStatus.entries
            .filterNot { it == ProcessingStatus.COMPLETED || it == ProcessingStatus.FAILED }
            .forEach { status ->
                assertFalse(
                    AudioRetentionPolicy.isEligible(
                        audioAvailable = true,
                        keepAudio = false,
                        audioExpiresAt = 1_000L,
                        processingStatus = status,
                        nowMs = 1_000L
                    )
                )
            }
    }

    @Test
    fun pinnedUnavailableMissingOrFutureAudioIsNotEligible() {
        assertFalse(eligible(keepAudio = true))
        assertFalse(eligible(audioAvailable = false))
        assertFalse(eligible(audioExpiresAt = null))
        assertFalse(eligible(audioExpiresAt = 1_001L))
    }

    private fun eligible(
        audioAvailable: Boolean = true,
        keepAudio: Boolean = false,
        audioExpiresAt: Long? = 1_000L
    ): Boolean = AudioRetentionPolicy.isEligible(
        audioAvailable = audioAvailable,
        keepAudio = keepAudio,
        audioExpiresAt = audioExpiresAt,
        processingStatus = ProcessingStatus.COMPLETED,
        nowMs = 1_000L
    )
}
