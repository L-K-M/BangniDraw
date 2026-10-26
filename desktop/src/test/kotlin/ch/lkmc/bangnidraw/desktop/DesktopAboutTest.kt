package ch.lkmc.bangnidraw.desktop

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAboutTest {

    @Test
    fun `the canvas help survives the trip through Android's string escaping`() {
        // The body used to be five Kotlin strings joined with "\n\n"; it is
        // one `strings.xml` entry now, and AAPT's rules are not Kotlin's — a
        // raw newline there collapses to a space, so the paragraphs live as
        // `\n` escapes that DesktopStrings has to decode, and the save
        // directory arrives as a `%1$s` argument rather than interpolation.
        // Nothing about that fails a build if it breaks.
        val body = DesktopHelp.canvasBody(picturesDirectory = "/tmp/pictures")

        assertTrue(body.contains("\n\n"), "the paragraphs collapsed into one")
        assertFalse(body.contains("\\n"), "an escape reached the screen literally")
        assertTrue(body.contains("/tmp/pictures"), "the save directory was dropped")
        assertFalse(body.contains("%1"), "the format argument was not substituted")
    }

    @Test
    fun `Mixbox build carries its required attribution`() {
        val body = DesktopAbout.body(MixboxAttribution.Included)

        assertTrue(body.contains("Mixbox", ignoreCase = true))
        // CC BY-NC 4.0 3(a)(1): the creators, the license by URI, the
        // warranty notice and a link to the material, as Android shows them.
        assertTrue(body.contains("Secret Weapons"))
        assertTrue(body.contains("Sarka Sochorova and Ondrej Jamriska"))
        assertTrue(body.contains(MIXBOX_LICENSE_URI), "the CC BY-NC 4.0 license URI is missing")
        assertTrue(body.contains(MIXBOX_SOURCE), "the link to the Mixbox source is missing")
    }

    @Test
    fun `both builds scope the Unlicense to the app's own code`() {
        // The app bundles third-party libraries under their own licenses, so
        // the dedication must not read as covering the whole download.
        for (attribution in MixboxAttribution.entries) {
            val body = DesktopAbout.body(attribution)

            assertTrue(body.contains("Unlicense"), "$attribution About omits the app's own license")
            assertTrue(
                body.contains(DesktopStrings.get("open_source_licenses")),
                "$attribution About does not point at the bundled notices",
            )
        }
    }

    @Test
    fun `About names the bundled notices file where the package ships it`() {
        val resources = createTempDirectory("app-resources").toFile()
        try {
            assertNull(DesktopNotices.file(resources.path), "no notices file, no pointer")

            val notices = File(resources, DesktopNotices.FILE_NAME).apply { writeText("notices") }
            assertEquals(notices, DesktopNotices.file(resources.path))
            assertTrue(DesktopAbout.body(MixboxAttribution.Excluded, notices).contains(notices.path))
        } finally {
            resources.deleteRecursively()
        }
    }

    @Test
    fun `stripped build does not claim Mixbox is included`() {
        val body = DesktopAbout.body(MixboxAttribution.Excluded)

        assertFalse(body.contains("Mixbox"))
    }

    private companion object {
        const val MIXBOX_LICENSE_URI = "https://creativecommons.org/licenses/by-nc/4.0/"
        const val MIXBOX_SOURCE = "https://github.com/scrtwpns/mixbox"
    }
}
