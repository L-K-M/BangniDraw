package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ColorFieldSyncTest {

    @Test
    fun `six digit hex draft survives the parent echo of its shorthand prefix`() {
        val initial = ColorFieldSync.fromColor(BLACK)
        val shorthand = ColorFieldSync.editHex(initial, "3A6")

        assertEquals("3A6", shorthand.hex)
        assertEquals(SHORTHAND, shorthand.selectedColor)

        val echoed = ColorFieldSync.syncParent(shorthand, SHORTHAND)
        assertEquals("3A6", echoed.hex)

        val fourth = ColorFieldSync.editHex(echoed, "3A6F")
        assertEquals("3A6F", fourth.hex)
        assertNull(fourth.selectedColor)

        val complete = ColorFieldSync.editHex(fourth, "3A6FD8")
        assertEquals("3A6FD8", complete.hex)
        assertEquals(LONGHAND, complete.selectedColor)
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val SHORTHAND = 0xFF33AA66.toInt()
        const val LONGHAND = 0xFF3A6FD8.toInt()
    }
}
