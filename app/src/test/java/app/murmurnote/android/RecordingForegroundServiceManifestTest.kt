package app.murmurnote.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class RecordingForegroundServiceManifestTest {

    @Test
    fun recordingServiceIsPrivateAndDeclaresTheMicrophoneForegroundContract() {
        val document = parseManifest()
        val permissions = document.getElementsByTagName("uses-permission")
        val permissionNames = buildSet {
            for (index in 0 until permissions.length) {
                add((permissions.item(index) as Element).getAttribute("android:name"))
            }
        }
        assertTrue(
            permissionNames.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE")
        )
        assertTrue(permissionNames.contains("android.permission.WAKE_LOCK"))

        val services = document.getElementsByTagName("service")
        val recordingService = (0 until services.length)
            .map { services.item(it) as Element }
            .firstOrNull {
                it.getAttribute("android:name") == ".service.RecordingForegroundService"
            }

        assertNotNull("Recording foreground service is missing", recordingService)
        assertEquals("false", checkNotNull(recordingService).getAttribute("android:exported"))
        assertEquals("microphone", recordingService.getAttribute("android:foregroundServiceType"))
        assertEquals("false", recordingService.getAttribute("android:stopWithTask"))
    }

    @Test
    fun startupProviderIsExplicitlyPrivate() {
        val providers = parseManifest().getElementsByTagName("provider")
        val startupProvider = (0 until providers.length)
            .map { providers.item(it) as Element }
            .firstOrNull {
                it.getAttribute("android:name") == "androidx.startup.InitializationProvider"
            }

        assertNotNull("AndroidX Startup provider is missing", startupProvider)
        assertEquals("false", checkNotNull(startupProvider).getAttribute("android:exported"))
    }

    private fun parseManifest() =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile())

    private fun manifestFile(): File = sequenceOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).firstOrNull(File::isFile)
        ?: error("Unable to find AndroidManifest.xml")
}
