package ch.lkmc.bangnidraw.ui.home

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The About dialog states the app's own license without claiming it for the
 * code the APK bundles, and gives Mixbox the attribution CC BY-NC 4.0 asks for.
 *
 * It used to end with "Public domain (Unlicense)." for the whole app, while
 * the APK ships Apache-2.0 AndroidX, Compose and Kotlin libraries and the
 * non-commercial Mixbox, and nothing listed their notices.
 */
class AboutLicenseContractTest {

    @Test
    fun `About scopes the Unlicense to the app's own code in every locale`() {
        for (path in STRINGS.keys) {
            val strings = ContractTestSources.read(path)
            val license = string(strings, "about_license", path)
            val pointer = string(strings, "open_source_licenses", path)

            assertTrue("Unlicense" in license, "$path about_license does not name the Unlicense")
            assertTrue(pointer in license, "$path about_license does not point at $pointer")
            assertFalse(
                "Unlicense" in string(strings, "about_body", path),
                "$path about_body claims the Unlicense for the whole app again",
            )
        }
    }

    @Test
    fun `Mixbox attribution links the material and the warranty notice in every locale`() {
        // CC BY-NC 4.0 3(a)(1)(A)(iv) and (v): a notice that refers to the
        // disclaimer of warranties, and a link to the licensed material.
        for ((path, warranty) in STRINGS) {
            val mixbox = string(ContractTestSources.read(path), "about_mixbox", path)

            assertTrue(MIXBOX_LICENSE_URI in mixbox, "$path about_mixbox lost the license URI")
            assertTrue(MIXBOX_SOURCE in mixbox, "$path about_mixbox does not link the Mixbox source")
            assertTrue(warranty in mixbox, "$path about_mixbox has no warranty notice")
        }
    }

    @Test
    fun `the About dialog opens the open-source licenses screen`() {
        val sheet = ContractTestSources.readCompact(SETTINGS_SHEET)

        assertTrue("stringResource(R.string.about_license)" in sheet)
        assertTrue("Intent(context,LicensesActivity::class.java)" in sheet)
    }

    private fun string(source: String, name: String, path: String): String =
        Regex("""<string\b[^>]*\bname="$name"[^>]*>([^<]*)</string>""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?: fail("$path has no string $name")

    private companion object {
        /** Each locale's strings, with the words its warranty notice uses. */
        val STRINGS = mapOf(
            "app/src/main/res/values/strings.xml" to "without warranty",
            "app/src/main/res/values-b+zh+Hans/strings.xml" to "不附带任何担保",
        )
        const val SETTINGS_SHEET = "app/src/main/java/ch/lkmc/bangnidraw/ui/home/SettingsSheet.kt"
        const val MIXBOX_LICENSE_URI = "https://creativecommons.org/licenses/by-nc/4.0/"
        const val MIXBOX_SOURCE = "https://github.com/scrtwpns/mixbox"
    }
}
