package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.Palette
import ch.lkmc.bangnidraw.engine.core.PaletteCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPaletteCodecTest {

    private val palettes = listOf(
        Palette(id = "user.a", name = "Inks", swatches = listOf(-1, -16777216)),
        Palette(id = "user.b", name = PaletteCatalog.MY_PALETTE_NAME, swatches = emptyList()),
    )

    @Test
    fun `round-trips the user's palettes`() {
        assertEquals(palettes, DesktopPaletteCodec.decode(DesktopPaletteCodec.encode(palettes)))
    }

    @Test
    fun `an absent or blank value is not a decode failure`() {
        assertNull(DesktopPaletteCodec.decode(null))
        assertNull(DesktopPaletteCodec.decode("   "))
    }

    @Test
    fun `unreadable stored text keeps the session's palettes`() {
        assertNull(DesktopPaletteCodec.decode("not json"))
        assertNull(DesktopPaletteCodec.decode("""{"id":"user.a"}"""))
    }

    @Test
    fun `a stored palette with an unsafe id is refused rather than joined to a path`() {
        val hostile = """[{"id":"../../evil","name":"x","swatches":[]}]"""

        assertNull(DesktopPaletteCodec.decode(hostile))
    }

    @Test
    fun `a stored copy of a built-in palette is dropped`() {
        // The catalogue owns those ids; a stored copy would shadow one with
        // whatever swatches an older build (or a hand edit) left in it.
        val stored = """
            [{"id":"${PaletteCatalog.PAINTERS_ID}","name":"x","swatches":[1],"builtIn":true},
             {"id":"user.a","name":"Inks","swatches":[2]}]
        """.trimIndent()

        val decoded = DesktopPaletteCodec.decode(stored)

        assertEquals(listOf("user.a"), decoded?.map(Palette::id))
    }

    @Test
    fun `the encoding is the one app writes per palette file`() {
        val encoded = DesktopPaletteCodec.encode(listOf(palettes.first()))

        assertTrue("\"id\":\"user.a\"" in encoded, encoded)
        assertTrue("\"swatches\":[-1,-16777216]" in encoded, encoded)
    }
}
