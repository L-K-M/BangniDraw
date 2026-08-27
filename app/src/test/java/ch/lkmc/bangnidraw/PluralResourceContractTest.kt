package ch.lkmc.bangnidraw

import java.io.File
import kotlin.test.Test
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

    private fun pluralBlock(source: String, name: String): String {
        val pattern = Regex("""<plurals\s+name="$name">([\s\S]*?)</plurals>""")
        return assertNotNull(pattern.find(source)?.groupValues?.get(1), "$name is not pluralized")
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

        val COUNT_RESOURCES = listOf(
            "studio_storage",
            "canvas_preset_fits",
            "layer_limit",
            "layer_flatten_title",
        )
    }
}
