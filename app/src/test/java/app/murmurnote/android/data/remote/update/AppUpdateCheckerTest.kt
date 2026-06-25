package app.murmurnote.android.data.remote.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun isNewerVersionHandlesGitHubStyleTags() {
        assertTrue(AppUpdateChecker.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(AppUpdateChecker.isNewerVersion("v1.0.10", "1.0.2"))
        assertFalse(AppUpdateChecker.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("v1.0.0", "1.0.1"))
    }
}
