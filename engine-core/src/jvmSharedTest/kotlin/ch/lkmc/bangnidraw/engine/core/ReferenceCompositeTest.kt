package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CPU twin of the reference draw — the pixels the gallery's reference
 * variant composites. Pins the footprint, the two filters, and the
 * premultiplied tap order, all as pure arithmetic per §15's rule.
 */
class ReferenceCompositeTest {

    private fun reference(
        width: Int,
        height: Int,
        transform: ReferenceTransform = ReferenceTransform.IDENTITY,
        opacity: Float = TracingReference.DEFAULT_OPACITY,
        visibility: ReferenceVisibility = ReferenceVisibility.VISIBLE,
    ) = TracingReference(
        assetName = "reference-x.png",
        imageWidth = width,
        imageHeight = height,
        transform = transform,
        opacity = opacity,
        visibility = visibility,
    )

    /** A straight-ARGB image of one colour. */
    private fun solid(width: Int, height: Int, argb: Int) =
        IntArray(width * height) { argb }

    @Test
    fun `identity maps canvas pixel centres onto texel centres`() {
        // Bilinear at frac 0 is the exact texel, so an identity placement
        // reproduces the source pixels verbatim inside the footprint.
        val source = solid(4, 4, 0xFF3366AA.toInt())
        val tile = ReferenceComposite.tile(
            reference(4, 4),
            ReferenceComposite.Source { x, y -> source[y * 4 + x] },
            tileLeft = 0,
            tileTop = 0,
        )

        assertEquals(0xFF3366AA.toInt(), tile[3 * TILE + 3], "interior pixel")
        assertEquals(Composite.TRANSPARENT, tile[4 * TILE + 4], "just past the footprint")
        assertEquals(Composite.TRANSPARENT, tile.last(), "far outside")
    }

    @Test
    fun `half-transparent source stays premultiplied, not straight`() {
        val straight = 0x80FF0000.toInt() // 50 % opaque red
        val source = solid(4, 4, straight)
        val tile = ReferenceComposite.tile(
            reference(4, 4),
            ReferenceComposite.Source { x, y -> source[y * 4 + x] },
            tileLeft = 0,
            tileTop = 0,
        )

        // Premultiplied: rgb scaled by a/255 — 255 → 128 by round-to-nearest.
        assertEquals(0x80800000.toInt(), tile[0])
    }

    @Test
    fun `minification blends neighbouring texels`() {
        // Scale 0.5: two canvas pixels per texel column; the boundary pixel
        // centre lands between texels and must blend them.
        val source = IntArray(2 * 1) { if (it == 0) 0xFF000000.toInt() else -1 }
        val half = ReferenceTransform(xx = 0.5f, xy = 0f, yx = 0f, yy = 1f, tx = 0f, ty = 0f)
        val tile = ReferenceComposite.tile(
            reference(2, 1, transform = half),
            ReferenceComposite.Source { x, y -> source[y * 2 + x] },
            tileLeft = 0,
            tileTop = 0,
        )

        // Canvas x=0: centre 0.5 → u=1.0 → exactly between the texels.
        val r = Composite.red(tile[0])
        val g = Composite.green(tile[0])
        assertTrue(r in 0x7E..0x81 && g in 0x7E..0x81, "half black, half white, was $r/$g")
        // Canvas x=1: centre 1.5 → u=3.0, outside [0, 2] → transparent.
        assertEquals(Composite.TRANSPARENT, tile[1])
    }

    @Test
    fun `magnification past four picks exact texels`() {
        // Scale 4 turns FilterPolicy to nearest: each texel owns a 4 px block.
        val source = IntArray(2 * 1) { if (it == 0) 0xFF0000FF.toInt() else 0xFFFF0000.toInt() }
        val quad = ReferenceTransform(xx = 4f, xy = 0f, yx = 0f, yy = 4f, tx = 0f, ty = 0f)
        val tile = ReferenceComposite.tile(
            reference(2, 1, transform = quad),
            ReferenceComposite.Source { x, y -> source[y * 2 + x] },
            tileLeft = 0,
            tileTop = 0,
        )

        assertEquals(0xFF0000FF.toInt(), tile[0], "texel 0 starts at once")
        assertEquals(0xFF0000FF.toInt(), tile[3], "texel 0 owns four canvas pixels")
        assertEquals(0xFFFF0000.toInt(), tile[4], "texel 1 takes over")
        assertEquals(0xFFFF0000.toInt(), tile[7])
        assertEquals(Composite.TRANSPARENT, tile[8], "past the 8 px footprint")
    }

    @Test
    fun `a translated footprint draws only where the image lands`() {
        val source = solid(4, 4, -1)
        val moved = ReferenceTransform(xx = 1f, xy = 0f, yx = 0f, yy = 1f, tx = 260f, ty = 260f)
        val placed = reference(4, 4, transform = moved)
        val sourceReader = ReferenceComposite.Source { x, y -> source[y * 4 + x] }

        val away = ReferenceComposite.tile(placed, sourceReader, tileLeft = 0, tileTop = 0)
        assertTrue(away.all { it == Composite.TRANSPARENT })

        val onto = ReferenceComposite.tile(placed, sourceReader, tileLeft = 256, tileTop = 256)
        assertEquals(-1, onto[4 * TILE + 4], "the image's own origin, in tile-local px")
        assertEquals(Composite.TRANSPARENT, onto[0])

        assertFalse(ReferenceComposite.coversTile(placed, IntRect(0, 0, 256, 256)))
        assertTrue(ReferenceComposite.coversTile(placed, IntRect(256, 256, 512, 512)))
    }

    @Test
    fun `hidden or zero-opacity references contribute nothing`() {
        val source = solid(4, 4, -1)
        val sourceReader = ReferenceComposite.Source { x, y -> source[y * 4 + x] }

        val hidden = reference(4, 4, visibility = ReferenceVisibility.HIDDEN)
        val faded = reference(4, 4, opacity = 0f)

        for (subject in listOf(hidden, faded)) {
            val tile = ReferenceComposite.tile(subject, sourceReader, tileLeft = 0, tileTop = 0)
            assertTrue(tile.all { it == Composite.TRANSPARENT }, "${subject.visibility}/${subject.opacity}")
            assertFalse(ReferenceComposite.coversTile(subject, IntRect(0, 0, 4, 4)))
        }
    }

    @Test
    fun `edge texels clamp rather than smear at the footprint border`() {
        // One texel wide: every interior sample clamps to it, so the whole
        // footprint is that colour — no outside pixels bleed in, none needed.
        val source = solid(1, 1, 0xFF00FF00.toInt())
        val stretched = ReferenceTransform(xx = 4f, xy = 0f, yx = 0f, yy = 4f, tx = 0f, ty = 0f)
        val tile = ReferenceComposite.tile(
            reference(1, 1, transform = stretched),
            ReferenceComposite.Source { _, _ -> source[0] },
            tileLeft = 0,
            tileTop = 0,
        )

        assertEquals(0xFF00FF00.toInt(), tile[0])
        assertEquals(0xFF00FF00.toInt(), tile[3 * TILE + 3])
        assertEquals(Composite.TRANSPARENT, tile[4 * TILE], "v = 4 is past the quad")
    }

    private companion object {
        const val TILE = PerfConstants.TILE_SIZE
    }
}
