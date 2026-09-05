package ch.lkmc.bangnidraw.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
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

        assertTrue(body.contains("Mixbox"))
        assertTrue(body.contains("CC BY-NC 4.0"))
    }

    @Test
    fun `stripped build does not claim Mixbox is included`() {
        val body = DesktopAbout.body(MixboxAttribution.Excluded)

        assertFalse(body.contains("Mixbox"))
    }
}
