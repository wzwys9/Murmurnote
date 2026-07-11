package app.murmurnote.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class UnverifiedModelInstallPolicyTest {

    @Test
    fun productionCodeHasNoHashVerificationBypass() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .first(File::isDirectory)
        val productionSource = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(productionSource.contains("installDownloadedWithoutHashCheck"))
        assertFalse(productionSource.contains("ACTION_INSTALL_UNVERIFIED"))
    }
}
