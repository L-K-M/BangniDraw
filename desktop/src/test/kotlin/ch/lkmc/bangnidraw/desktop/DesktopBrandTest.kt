package ch.lkmc.bangnidraw.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopBrandTest {

    @Test
    fun `desktop reads the canonical Android application name`() {
        assertEquals("帮你Draw", DesktopBrand.displayName)
    }
}
