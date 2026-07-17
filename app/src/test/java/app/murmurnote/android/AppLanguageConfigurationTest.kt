package app.murmurnote.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AppLanguageConfigurationTest {

    @Test
    fun mainActivityHandlesLocaleChangesWithoutRecreation() {
        val activities = parseManifest().getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map { activities.item(it) as Element }
            .single { it.getAttribute("android:name") == ".MainActivity" }
        val handledChanges = mainActivity
            .getAttribute("android:configChanges")
            .split('|')
            .toSet()

        assertTrue("MainActivity must handle locale changes", "locale" in handledChanges)
        assertTrue(
            "MainActivity must handle locale-driven layout direction changes",
            "layoutDirection" in handledChanges,
        )
    }

    private fun parseManifest() =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile())

    private fun manifestFile(): File = sequenceOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).firstOrNull(File::isFile)
        ?: error("Unable to find AndroidManifest.xml")
}
