package ch.lkmc.bangnidraw

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PluralResourceContractTest {

    @Test
    fun `singular-capable counts use locale plural forms`() {
        val english = File(repositoryRoot(), ENGLISH_STRINGS).readText()
        val chinese = File(repositoryRoot(), CHINESE_STRINGS).readText()

        for (name in COUNT_RESOURCES) {
            val englishBlock = pluralBlock(english, name)
            assertTrue("quantity=\"one\"" in englishBlock, "$name has no singular form")
            assertTrue("quantity=\"other\"" in englishBlock, "$name has no plural form")

            val chineseBlock = pluralBlock(chinese, name)
            assertTrue("quantity=\"other\"" in chineseBlock, "$name has no zh-Hans form")
        }
    }

    @Test
    fun `no locale overrides a plural with a plain string`() {
        val values = requireNotNull(File(repositoryRoot(), ENGLISH_STRINGS).parentFile)
        val resources = requireNotNull(values.parentFile)
        val valueDirectories = resources.listFiles { file ->
            file.isDirectory && file.name.startsWith(VALUES_PREFIX)
        }.orEmpty()
        assertTrue(valueDirectories.isNotEmpty(), "no values directories found under $resources")

        for (directory in valueDirectories) {
            val xmlFiles = directory.listFiles { file ->
                file.isFile && file.extension == XML_EXTENSION
            }.orEmpty()
            for (xmlFile in xmlFiles) {
                val source = xmlFile.readText()
                for (name in COUNT_RESOURCES) {
                    val plainString = Regex("""<string\b[^>]*\bname="$name"[^>]*>""")
                    assertFalse(
                        plainString.containsMatchIn(source),
                        "$name is a plain string in ${directory.name}/${xmlFile.name}",
                    )
                }
            }
        }
    }

    @Test
    fun `one-layer limit never suggests an impossible recovery`() {
        val english = File(repositoryRoot(), ENGLISH_STRINGS).readText()
        val chinese = File(repositoryRoot(), CHINESE_STRINGS).readText()

        val englishOne = pluralItem(english, LAYER_LIMIT_RESOURCE, "one")
        assertFalse(englishOne.contains("delete", ignoreCase = true))
        assertFalse(englishOne.contains("merge", ignoreCase = true))

        // zh-Hans selects `other` for one too, so its wording must fit every cap.
        val chineseOther = pluralItem(chinese, LAYER_LIMIT_RESOURCE, "other")
        assertFalse("删除" in chineseOther)
        assertFalse("合并" in chineseOther)
    }

    private fun pluralBlock(source: String, name: String): String {
        val pattern = Regex(
            """<plurals\b[^>]*\bname="$name"[^>]*>([\s\S]*?)</plurals>""",
        )
        return assertNotNull(pattern.find(source)?.groupValues?.get(1), "$name is not pluralized")
    }

    private fun pluralItem(source: String, name: String, quantity: String): String {
        val block = pluralBlock(source, name)
        val pattern = Regex("""<item\s+quantity="$quantity">([\s\S]*?)</item>""")

        return assertNotNull(pattern.find(block)?.groupValues?.get(1), "$name has no $quantity")
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val ENGLISH_STRINGS = "app/src/main/res/values/strings.xml"
        const val CHINESE_STRINGS = "app/src/main/res/values-b+zh+Hans/strings.xml"
        const val VALUES_PREFIX = "values"
        const val XML_EXTENSION = "xml"
        const val LAYER_LIMIT_RESOURCE = "layer_limit"

        val COUNT_RESOURCES = listOf(
            "studio_storage",
            "canvas_preset_fits",
            "layer_limit",
            "layer_flatten_title",
        )
    }
}
