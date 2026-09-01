package ch.lkmc.bangnidraw.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAboutTest {

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
