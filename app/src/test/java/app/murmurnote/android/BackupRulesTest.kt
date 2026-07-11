package app.murmurnote.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BackupRulesTest {

    @Test
    fun legacyBackupRulesExcludeEveryPersistentStorageDomain() {
        val document = parseResource("backup_rules.xml")
        val root = document.documentElement

        assertEquals("full-backup-content", root.tagName)
        assertEquals(REQUIRED_EXCLUDED_DOMAINS, excludedDomains(root))
    }

    @Test
    fun modernBackupRulesExcludeEveryDomainFromCloudAndDeviceTransfer() {
        val document = parseResource("data_extraction_rules.xml")
        val root = document.documentElement

        assertEquals("data-extraction-rules", root.tagName)
        listOf("cloud-backup", "device-transfer").forEach { sectionName ->
            val section = root.getElementsByTagName(sectionName).item(0) as? Element
            assertTrue("Missing $sectionName backup section", section != null)
            assertEquals(REQUIRED_EXCLUDED_DOMAINS, excludedDomains(checkNotNull(section)))
        }
    }

    private fun parseResource(fileName: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resourceFile(fileName))

    private fun resourceFile(fileName: String): File = sequenceOf(
        File("src/main/res/xml", fileName),
        File("app/src/main/res/xml", fileName)
    ).firstOrNull(File::isFile)
        ?: error("Unable to find Android XML resource $fileName")

    private fun excludedDomains(parent: Element): Set<String> {
        val excludes = parent.getElementsByTagName("exclude")
        return buildSet {
            for (index in 0 until excludes.length) {
                val exclude = excludes.item(index) as Element
                if (exclude.parentNode === parent && exclude.getAttribute("path") == ".") {
                    add(exclude.getAttribute("domain"))
                }
            }
        }
    }

    private companion object {
        val REQUIRED_EXCLUDED_DOMAINS = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref"
        )
    }
}
