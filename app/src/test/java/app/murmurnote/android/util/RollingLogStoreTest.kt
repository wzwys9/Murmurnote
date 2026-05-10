package app.murmurnote.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RollingLogStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun rotatesCurrentLogAndKeepsConfiguredFileCount() {
        val store = RollingLogStore(
            dir = temp.newFolder("logs"),
            maxSizeBytes = 1,
            maxFiles = 2
        )

        store.appendLine("first-line")
        store.appendLine("second-line")
        store.appendLine("third-line")
        store.appendLine("fourth-line")

        assertTrue(store.currentFile().readText().contains("fourth-line"))
        assertTrue(store.files()[1].readText().contains("third-line"))
        assertTrue(store.files()[2].readText().contains("second-line"))
        assertEquals(3, store.files().count { it.exists() })
    }

    @Test
    fun clearRemovesRotatedLogsAndLeavesCurrentFile() {
        val store = RollingLogStore(
            dir = temp.newFolder("logs"),
            maxSizeBytes = 1,
            maxFiles = 2
        )
        store.appendLine("first-line")
        store.appendLine("second-line")

        store.clear()

        assertTrue(store.currentFile().exists())
        assertEquals("", store.currentFile().readText())
        assertFalse(store.files().drop(1).any { it.exists() })
    }
}
