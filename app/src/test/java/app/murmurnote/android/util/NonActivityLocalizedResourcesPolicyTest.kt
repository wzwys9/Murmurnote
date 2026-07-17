package app.murmurnote.android.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NonActivityLocalizedResourcesPolicyTest {

    @Test
    fun nonActivityUserVisibleStringsUseThePerAppLocaleContext() {
        val sourceRoot = sourceRoot()
        val namedContextFiles = buildList {
            addAll(File(sourceRoot, "ui").walkTopDown().filter { it.name.endsWith("ViewModel.kt") })
            addAll(File(sourceRoot, "widget").walkTopDown().filter { it.extension == "kt" })
            add(File(sourceRoot, "audio/AudioImporter.kt"))
            add(File(sourceRoot, "domain/pipeline/AudioPipeline.kt"))
            add(File(sourceRoot, "service/RecordingForegroundSession.kt"))
            add(File(sourceRoot, "util/LogExporter.kt"))
            add(File(sourceRoot, "data/remote/llm/LlmClient.kt"))
        }.filter(File::isFile)
        val receiverFiles = buildList {
            add(File(sourceRoot, "MurmurnoteApplication.kt"))
            addAll(File(sourceRoot, "service").walkTopDown().filter { file ->
                file.extension == "kt" && file.readText().contains(": Service()")
            })
        }.filter(File::isFile)

        val violations = buildList {
            namedContextFiles.forEach { file ->
                addMatches(file, NAMED_CONTEXT_GET_STRING)
            }
            receiverFiles.forEach { file ->
                addMatches(file, UNQUALIFIED_GET_STRING)
            }
        }

        assertTrue(
            "Use localizedString() for non-Activity user-visible resources:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun MutableList<String>.addMatches(file: File, pattern: Regex) {
        file.readLines().forEachIndexed { index, line ->
            if (pattern.containsMatchIn(line.substringBefore("//"))) {
                add("${file.relativeTo(projectRoot()).path}:${index + 1}: ${line.trim()}")
            }
        }
    }

    private fun sourceRoot(): File = File(projectRoot(), "src/main/java/app/murmurnote/android")

    private fun projectRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main").isDirectory }
        ?: error("Unable to find app project root")

    private companion object {
        val NAMED_CONTEXT_GET_STRING = Regex(
            "\\b(?:context|appContext|applicationContext|activityContext)\\.getString\\s*\\(",
        )
        val UNQUALIFIED_GET_STRING = Regex("(?<![\\w.])getString\\s*\\(")
    }
}
