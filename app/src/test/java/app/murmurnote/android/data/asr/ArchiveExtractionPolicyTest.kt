package app.murmurnote.android.data.asr

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveExtractionPolicyTest {

    @Test
    fun downloadBudgetAcceptsTheExactLastByteAndRejectsAnythingBeyondIt() {
        assertEquals(10L, ModelDownloadSizePolicy.accountRead(8L, 2, 10L))
        assertThrows(IOException::class.java) {
            ModelDownloadSizePolicy.accountRead(9L, 2, 10L)
        }
    }

    @Test
    fun resolvesOnlyRegularPathsInsideTheExpectedModelDirectory() {
        val root = Files.createTempDirectory("archive-policy").toFile()
        try {
            val target = ArchiveExtractionPolicy.resolveTarget(
                root = root,
                entryName = "./expected/tokenizer/vocab.json",
                expectedTopDirectory = "expected",
            )

            assertEquals(
                File(root, "expected/tokenizer/vocab.json").canonicalFile,
                target,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsAbsoluteTraversalSiblingAndBackslashPaths() {
        val root = Files.createTempDirectory("archive-policy").toFile()
        try {
            listOf(
                "/expected/model.onnx",
                "../outside",
                "expected/../../outside",
                "another/model.onnx",
                "expected\\..\\outside",
            ).forEach { name ->
                assertThrows(IOException::class.java) {
                    ArchiveExtractionPolicy.resolveTarget(root, name, "expected")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun budgetRejectsTooManyEntriesAndDeclaredOrActualExpansion() {
        val entryBudget = ArchiveExtractionBudget(
            maxEntries = 1,
            maxEntryBytes = 8,
            maxTotalBytes = 8,
        )
        entryBudget.beginEntry(declaredSize = 0, writesData = false)
        entryBudget.finishEntry()
        assertThrows(IOException::class.java) {
            entryBudget.beginEntry(declaredSize = 0, writesData = false)
        }

        val declaredBudget = ArchiveExtractionBudget(2, 8, 8)
        assertThrows(IOException::class.java) {
            declaredBudget.beginEntry(declaredSize = 9, writesData = true)
        }

        val actualBudget = ArchiveExtractionBudget(2, 8, 8)
        actualBudget.beginEntry(declaredSize = 4, writesData = true)
        actualBudget.recordExtractedBytes(4)
        assertThrows(IOException::class.java) {
            actualBudget.recordExtractedBytes(1)
        }
    }
}
