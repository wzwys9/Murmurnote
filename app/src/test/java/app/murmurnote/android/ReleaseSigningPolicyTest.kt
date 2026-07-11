package app.murmurnote.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class ReleaseSigningPolicyTest {

    @Test
    fun releaseBuildNeverFallsBackToTheDebugSigningKey() {
        val buildScript = sequenceOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first(File::isFile)
            .readText()

        assertFalse(buildScript.contains("signingConfigs.getByName(\"debug\")"))
    }
}
