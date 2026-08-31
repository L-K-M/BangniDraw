package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.PerfConstants.SLICES_PER_PAGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parts of the capability probe (`docs/plan/03-canvas-engine.md` §13) that
 * are arithmetic and string handling rather than GL calls.
 *
 * §13 says `GlCaps` is a plain data class *so that* it can be driven from
 * synthetic probes; this is that. `probe()` itself needs a context and belongs
 * to the device checklist.
 */
class GlCapsTest {

    private fun caps(
        major: Int = 3,
        minor: Int = 0,
        arrayLayers: Int = 256,
        maxTexture: Int = 4096,
    ) = GlCaps(
        glesMajor = major,
        glesMinor = minor,
        maxArrayTextureLayers = arrayLayers,
        maxTextureSize = maxTexture,
        maxRenderbufferSize = maxTexture,
        maxViewportWidth = maxTexture,
        maxViewportHeight = maxTexture,
        hasShaderFramebufferFetch = false,
        hasColorBufferHalfFloat = false,
        renderer = "Mali-G78",
        vendor = "ARM",
        version = "OpenGL ES $major.$minor v1.r26p0",
    )

    @Test
    fun `slicesPerPage is capped at 256 however generous the driver is`() {
        // §2.1: a page is the allocation granule (64 MiB at 256 slices) and
        // the first page must not be bigger than a phone-sized painting needs.
        // A driver advertising 2048 would otherwise make the first stroke cost
        // half a gigabyte.
        assertEquals(SLICES_PER_PAGE, caps(arrayLayers = 2048).slicesPerPage)
        assertEquals(SLICES_PER_PAGE, caps(arrayLayers = 256).slicesPerPage)
    }

    @Test
    fun `a driver below the spec minimum just gets smaller pages`() {
        // Trusted as-is rather than refused: the capacity arithmetic stays
        // self-consistent (smaller pages, more of them), so no cap comes out
        // wrong — the pool degenerates toward many near-empty pages.
        assertEquals(64, caps(arrayLayers = 64).slicesPerPage)
        assertEquals(1, caps(arrayLayers = 1).slicesPerPage)
    }

    @Test
    fun `support needs ES 3, a tile-sized texture, and at least one array layer`() {
        assertTrue(caps().isSupported)
        assertTrue(caps(major = 3, minor = 2).isSupported)
        assertFalse(caps(major = 2, minor = 0).isSupported, "ES 2 has no texture arrays")
        assertFalse(caps(maxTexture = 128).isSupported, "a tile is 256 px")
        assertFalse(caps(arrayLayers = 0).isSupported, "no array layers means no pool")
    }

    @Test
    fun `an extension is matched as a whole token, not as a substring`() {
        // A plain `contains` reports EXT_color_buffer_half_float present on a
        // driver advertising only a longer lookalike. It is invisible until
        // the device with the near-namesake is the one that renders wrong.
        val list = "GL_EXT_color_buffer_half_float_extra GL_OES_texture_npot GL_EXT_disjoint_timer_query"
        assertFalse(GlCaps.hasExtension(list, "GL_EXT_color_buffer_half_float"))
        assertTrue(GlCaps.hasExtension(list, "GL_OES_texture_npot"))
        assertTrue(GlCaps.hasExtension(list, "GL_EXT_disjoint_timer_query"))
        // First and last token, and an empty list.
        assertTrue(GlCaps.hasExtension(list, "GL_EXT_color_buffer_half_float_extra"))
        assertFalse(GlCaps.hasExtension("", "GL_EXT_shader_framebuffer_fetch"))
        assertFalse(GlCaps.hasExtension("GL_A GL_B", "GL_"))
    }

    @Test
    fun `the ES version parses out of the driver's version string`() {
        assertEquals(3 to 2, GlCaps.parseEsVersion("OpenGL ES 3.2 v1.r26p0-01eac0"))
        assertEquals(3 to 0, GlCaps.parseEsVersion("OpenGL ES 3.0 Mesa 21.2.6"))
        assertEquals(2 to 0, GlCaps.parseEsVersion("OpenGL ES 2.0"))
    }

    @Test
    fun `an unreadable version string is not assumed to be ES 3`() {
        // The failure mode this prevents: a driver whose GL_VERSION we cannot
        // parse gets treated as capable, allocates texture arrays it does not
        // have, and crashes instead of showing the unsupported-device screen.
        assertEquals(0 to 0, GlCaps.parseEsVersion(""))
        assertEquals(0 to 0, GlCaps.parseEsVersion("OpenGL ES"))
        assertFalse(caps(major = 0, minor = 0).isSupported)
    }

    @Test
    fun `describe carries the numbers the About screen and a bug report need`() {
        val text = caps(arrayLayers = 512).describe()
        for (fragment in listOf("ARM", "Mali-G78", "512", "4096")) {
            assertTrue(fragment in text, "describe() dropped $fragment: $text")
        }
    }

    @Test
    fun `forceCopyBeforeRmw is off by default`() {
        // §2.1's escape hatch for a driver that misbehaves despite the page
        // split. It costs a full-slice copy per pass, so it must never become
        // the default by accident.
        assertFalse(caps().forceCopyBeforeRmw)
    }
}
