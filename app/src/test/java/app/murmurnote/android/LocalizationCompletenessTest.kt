package app.murmurnote.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LocalizationCompletenessTest {

    @Test
    fun englishResourcesCoverEveryTranslatableString() {
        val defaultStrings = translatableStringNames(resourceDirectory("values"))
        val englishStrings = translatableStringNames(resourceDirectory("values-en"))
        val defaultPlurals = translatablePluralNames(resourceDirectory("values"))
        val englishPlurals = translatablePluralNames(resourceDirectory("values-en"))

        assertEquals(
            "English strings must exactly match the translatable default resource set",
            defaultStrings,
            englishStrings,
        )
        assertEquals(
            "English plurals must exactly match the translatable default resource set",
            defaultPlurals,
            englishPlurals,
        )
    }

    @Test
    fun englishUiResourcesDoNotContainChineseText() {
        val directory = resourceDirectory("values-en")
        val stringViolations = stringElements(directory)
            .filterNot { it.getAttribute("name").startsWith("prompt_") }
            .mapNotNull { element ->
                val value = element.textContent.trim()
                if (HAN_SCRIPT.containsMatchIn(value)) {
                    "${element.getAttribute("name")}: $value"
                } else {
                    null
                }
            }
        val pluralViolations = pluralElements(directory).flatMap { plural ->
            val items = plural.getElementsByTagName("item")
            (0 until items.length).mapNotNull { index ->
                val item = items.item(index) as Element
                val value = item.textContent.trim()
                if (HAN_SCRIPT.containsMatchIn(value)) {
                    "${plural.getAttribute("name")}[${item.getAttribute("quantity")}]: $value"
                } else {
                    null
                }
            }
        }
        val violations = stringViolations + pluralViolations

        assertTrue(
            "English UI resources still contain Chinese text:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun previewFailureCountUsesQuantityAwareResources() {
        val defaultPlural = pluralElements(resourceDirectory("values"))
            .singleOrNull { it.getAttribute("name") == "detail_preview_failed_count" }
        val englishPlural = pluralElements(resourceDirectory("values-en"))
            .singleOrNull { it.getAttribute("name") == "detail_preview_failed_count" }

        assertNotNull("Default resources must define the preview failure plural", defaultPlural)
        assertNotNull("English resources must define the preview failure plural", englishPlural)
        assertTrue(defaultPlural!!.pluralQuantities().contains("other"))
        assertEquals(setOf("one", "other"), englishPlural!!.pluralQuantities())
    }

    @Test
    fun userVisibleKotlinDoesNotHardCodeChineseText() {
        val sourceRoot = sourceRoot()
        val files = buildList {
            addAll(File(sourceRoot, "ui").walkTopDown().filter { it.extension == "kt" })
            addAll(File(sourceRoot, "service").walkTopDown().filter { it.extension == "kt" })
            addAll(File(sourceRoot, "widget").walkTopDown().filter { it.extension == "kt" })
            add(File(sourceRoot, "MainActivity.kt"))
        }
        val violations = files.flatMap { file ->
            uncommentedLines(file).mapIndexedNotNull { index, line ->
                if (HAN_SCRIPT.containsMatchIn(line)) {
                    "${file.relativeTo(projectRoot()).path}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }

        assertTrue(
            "Move user-visible Chinese text to string resources:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun translatableStringNames(directory: File): Set<String> = buildSet {
        stringElements(directory).forEach { element ->
            val name = element.getAttribute("name")
            if (element.getAttribute("translatable") != "false" &&
                !name.startsWith("prompt_")
            ) {
                add(name)
            }
        }
    }

    private fun translatablePluralNames(directory: File): Set<String> = buildSet {
        pluralElements(directory).forEach { element ->
            if (element.getAttribute("translatable") != "false") {
                add(element.getAttribute("name"))
            }
        }
    }

    private fun stringElements(directory: File): List<Element> = buildList {
        directory.listFiles { file -> file.extension == "xml" }.orEmpty().forEach { file ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = document.getElementsByTagName("string")
            for (index in 0 until strings.length) {
                add(strings.item(index) as Element)
            }
        }
    }

    private fun pluralElements(directory: File): List<Element> = buildList {
        directory.listFiles { file -> file.extension == "xml" }.orEmpty().forEach { file ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val plurals = document.getElementsByTagName("plurals")
            for (index in 0 until plurals.length) {
                add(plurals.item(index) as Element)
            }
        }
    }

    private fun Element.pluralQuantities(): Set<String> = buildSet {
        val items = getElementsByTagName("item")
        for (index in 0 until items.length) {
            add((items.item(index) as Element).getAttribute("quantity"))
        }
    }

    private fun uncommentedLines(file: File): List<String> {
        var insideBlockComment = false
        return file.readLines().map { rawLine ->
            val result = StringBuilder()
            var index = 0
            var insideString = false
            var escaped = false
            while (index < rawLine.length) {
                val current = rawLine[index]
                val next = rawLine.getOrNull(index + 1)
                when {
                    insideBlockComment && current == '*' && next == '/' -> {
                        insideBlockComment = false
                        index += 2
                    }
                    insideBlockComment -> index++
                    !insideString && current == '/' && next == '*' -> {
                        insideBlockComment = true
                        index += 2
                    }
                    !insideString && current == '/' && next == '/' -> index = rawLine.length
                    else -> {
                        result.append(current)
                        if (insideString) {
                            when {
                                escaped -> escaped = false
                                current == '\\' -> escaped = true
                                current == '"' -> insideString = false
                            }
                        } else if (current == '"') {
                            insideString = true
                        }
                        index++
                    }
                }
            }
            result.toString()
        }
    }

    private fun resourceDirectory(relativePath: String): File =
        File(projectRoot(), "src/main/res/$relativePath").also {
            require(it.isDirectory) { "Unable to find Android resource directory ${it.path}" }
        }

    private fun sourceRoot(): File = File(projectRoot(), "src/main/java/app/murmurnote/android")

    private fun projectRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main").isDirectory }
        ?: error("Unable to find app project root")

    private companion object {
        val HAN_SCRIPT = Regex("[\\p{IsHan}]")
    }
}
