package app.murmurnote.android.util

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPrivacyUpgradeTest {

    @Test
    fun clearsLegacyLogsExactlyOnceBeforeWritingMarker() {
        val filesDir = Files.createTempDirectory("diagnostic-privacy-").toFile()
        var clearCount = 0
        try {
            assertTrue(
                DiagnosticPrivacyUpgrade.apply(filesDir) {
                    clearCount++
                }
            )
            assertEquals(1, clearCount)

            assertFalse(
                DiagnosticPrivacyUpgrade.apply(filesDir) {
                    clearCount++
                }
            )
            assertEquals(1, clearCount)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun failedClearDoesNotWriteMarkerSoNextLaunchCanRetry() {
        val filesDir = Files.createTempDirectory("diagnostic-privacy-failure-").toFile()
        try {
            assertThrows(IllegalStateException::class.java) {
                DiagnosticPrivacyUpgrade.apply(filesDir) {
                    error("clear failed")
                }
            }

            var retried = false
            assertTrue(
                DiagnosticPrivacyUpgrade.apply(filesDir) {
                    retried = true
                }
            )
            assertTrue(retried)
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
