package app.murmurnote.android.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundNotificationIdsTest {

    @Test
    fun simultaneousForegroundServicesUseDistinctNotificationIds() {
        val ids = setOf(
            TranscriptionService.NOTIFICATION_ID,
            AsrModelDownloadService.NOTIFICATION_ID,
            RecordingForegroundService.NOTIFICATION_ID,
        )

        assertEquals(3, ids.size)
    }
}
