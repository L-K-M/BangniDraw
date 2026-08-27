package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PaletteTest {

    @Test
    fun `painters palette matches the documented pigment table`() {
        assertEquals(
            listOf(
                0xFFFEEC00.toInt(), 0xFFFCD300.toInt(), 0xFFFF6900.toInt(),
                0xFFFF2702.toInt(), 0xFF80022E.toInt(), 0xFF4E0042.toInt(),
                0xFF190059.toInt(), 0xFF002185.toInt(), 0xFF0D1B44.toInt(),
                0xFF003C32.toInt(), 0xFF076D16.toInt(), 0xFF6B9404.toInt(),
                0xFF7B4800.toInt(), 0xFFFFFFFF.toInt(), 0xFF141414.toInt(),
            ),
            PaletteCatalog.Painters.swatches,
        )
    }

    @Test
    fun `recent colors are deduplicated newest-first and capped`() {
        val colors = List(PalettePolicy.RECENT_LIMIT) { it }

        val recent = PalettePolicy.noteRecent(colors, 7)

        assertEquals(PalettePolicy.RECENT_LIMIT, recent.size)
        assertEquals(7, recent.first())
        assertEquals(1, recent.count { it == 7 })
        assertEquals(15, recent.last())
    }

    @Test
    fun `only user palettes can be edited`() {
        val user = Palette("user", "User", listOf(1, 2, 3))

        assertEquals(listOf(1, 9, 3), PalettePolicy.replace(user, 1, 9).swatches)
        assertEquals(listOf(1, 3), PalettePolicy.remove(user, 1).swatches)
        assertEquals(listOf(2, 3, 1), PalettePolicy.move(user, 0, 2).swatches)
        assertEquals(
            listOf("first", "user", "last"),
            PalettePolicy.upsert(
                listOf(Palette("first", "First", emptyList()), user, Palette("last", "Last", emptyList())),
                user.copy(swatches = listOf(9)),
            ).map(Palette::id),
        )
        assertFailsWith<IllegalArgumentException> { PalettePolicy.append(PaletteCatalog.Basic, 4) }
    }

    @Test
    fun `stored colors round-trip signed ARGB values`() {
        val colors = listOf(0xFF123456.toInt(), 0xFFFFFFFF.toInt(), 0)

        assertEquals(colors, StoredColors.decode(StoredColors.encode(colors)))
    }

    @Test
    fun `color fields accept documented forms and reject invalid channels`() {
        assertEquals(0xFFAABBCC.toInt(), ColorText.parseHex("#ABC"))
        assertEquals(0xFF3A6FD8.toInt(), ColorText.parseHex("3A6FD8"))
        assertNull(ColorText.parseHex("1234"))
        assertEquals(255, ColorText.parseChannel("255"))
        assertNull(ColorText.parseChannel("256"))
        assertEquals("#3A6FD8", ColorText.hex(0x123A6FD8))
    }

    @Test
    fun `eyedropper preview targets current color or one dish well and cancel restores`() {
        val dish = DishState(a = 1, b = 2)
        val well = ColorPickSession(ColorPickTarget.WellB, currentBefore = 3, dishBefore = dish)
        val current = ColorPickSession(ColorPickTarget.Current, currentBefore = 3, dishBefore = dish)

        assertEquals(ColorPickSession.Result(3, dish.copy(b = 9)), well.preview(9, 3, dish))
        assertEquals(ColorPickSession.Result(9, dish), current.preview(9, 3, dish))
        assertEquals(ColorPickSession.Result(3, dish), well.cancel())
    }

    @Test
    fun `swatch eyedropper preview is reversible`() {
        val palette = Palette("user", "User", listOf(1, 2, 3))
        val pick = PaletteSwatchPickSession(palette.id, index = 1, colorBefore = 2)

        val preview = pick.preview(palette, 9)

        assertEquals(listOf(1, 9, 3), preview.swatches)
        assertEquals(palette, pick.cancel(preview))
    }

    @Test
    fun `a created palette's name is the typed one, blank falls back to the token`() {
        assertEquals("Cadmiums", PalettePolicy.createdName(" Cadmiums "))
        assertEquals("Cad miums", PalettePolicy.createdName("Cad miums"))
        // Blank input keeps the display token, so the chip still localizes.
        assertEquals(PaletteCatalog.MY_PALETTE_NAME, PalettePolicy.createdName("   "))
    }
}
