package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.core.PerfConstants.SLICES_PER_PAGE
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE

/**
 * What this GPU can do, read once per context before any allocation
 * (`docs/plan/03-canvas-engine.md` §13).
 *
 * A plain data class with no GL in it, so `MemoryBudget` and the pool can be
 * tested against synthetic probes — §13 says exactly that, and it is the
 * reason [probe] is a companion function rather than a constructor.
 */
data class GlCaps(
    /** Major version from `GL_VERSION`; ES 3.0 is the baseline this engine needs. */
    val glesMajor: Int,
    val glesMinor: Int,
    /** `GL_MAX_ARRAY_TEXTURE_LAYERS` — the slices one page could hold. */
    val maxArrayTextureLayers: Int,
    /** `GL_MAX_TEXTURE_SIZE` — bounds `Accum`/`Scratch`, never the canvas (tiles are 256 px). */
    val maxTextureSize: Int,
    val maxRenderbufferSize: Int,
    val maxViewportWidth: Int,
    val maxViewportHeight: Int,
    /** `EXT_shader_framebuffer_fetch` — would remove the blit-to-scratch (§3.2). Recorded, unused in v1. */
    val hasShaderFramebufferFetch: Boolean,
    /** `EXT_color_buffer_half_float` — recorded; v1 is RGBA8 everywhere (§2.4). */
    val hasColorBufferHalfFloat: Boolean,
    val renderer: String,
    val vendor: String,
    val version: String,
    /**
     * The §2.1 escape hatch: copy an RMW source into a private page before the
     * pass instead of relying on `allocateNotOn`. Off by default — it exists
     * for a driver that misbehaves despite the page split, and flipping it
     * costs a full-slice copy per pass.
     */
    val forceCopyBeforeRmw: Boolean = false,
) {
    /**
     * ES 3.0 with big enough tiles. Anything less shows the "unsupported
     * device" screen: the Studio still works, the Canvas refuses to open
     * (§13). Not expected to fire on API-29 hardware — every device that
     * reaches this app has ES 3.0 — which is why it is a guard and not a
     * fallback path.
     */
    val isSupported: Boolean
        get() = glesMajor >= 3 && maxTextureSize >= TILE_SIZE && maxArrayTextureLayers >= 1

    /**
     * Slices in one [TilePool] page.
     *
     * `min(GL_MAX_ARRAY_TEXTURE_LAYERS, 256)` — we do not use more than 256
     * even where the driver allows 2048, because a page is the allocation
     * granule (64 MiB at 256 slices) and the first page must not be bigger
     * than a phone-sized painting needs (§2.1). A driver reporting fewer than
     * the spec minimum of 256 is trusted as-is: pages are simply smaller, and
     * the capacity arithmetic in `MemoryBudget` stays self-consistent.
     */
    val slicesPerPage: Int get() = minOf(maxArrayTextureLayers, SLICES_PER_PAGE)

    /** One line for the About screen's diagnostics and for Logcat at startup. */
    fun describe(): String =
        "$vendor $renderer, $version — arrayLayers=$maxArrayTextureLayers " +
            "maxTexture=$maxTextureSize viewport=${maxViewportWidth}x$maxViewportHeight " +
            "fbFetch=$hasShaderFramebufferFetch halfFloat=$hasColorBufferHalfFloat"

    companion object {
        /**
         * Runs on the GL thread with a current context, before any allocation.
         *
         * Every value is read here and never re-queried: a `glGetIntegerv` per
         * frame is a driver round-trip for a number that cannot change while
         * the context lives.
         */
        fun probe(): GlCaps {
            val version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
            val (major, minor) = parseEsVersion(version)
            val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
            val dims = IntArray(2)
            GLES30.glGetIntegerv(GLES30.GL_MAX_VIEWPORT_DIMS, dims, 0)
            return GlCaps(
                glesMajor = major,
                glesMinor = minor,
                maxArrayTextureLayers = getInt(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS),
                maxTextureSize = getInt(GLES30.GL_MAX_TEXTURE_SIZE),
                maxRenderbufferSize = getInt(GLES30.GL_MAX_RENDERBUFFER_SIZE),
                maxViewportWidth = dims[0],
                maxViewportHeight = dims[1],
                hasShaderFramebufferFetch = hasExtension(extensions, "GL_EXT_shader_framebuffer_fetch"),
                hasColorBufferHalfFloat = hasExtension(extensions, "GL_EXT_color_buffer_half_float"),
                renderer = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty(),
                vendor = GLES30.glGetString(GLES30.GL_VENDOR).orEmpty(),
                version = version,
            )
        }

        private fun getInt(pname: Int): Int {
            val v = IntArray(1)
            GLES30.glGetIntegerv(pname, v, 0)
            return v[0]
        }

        /**
         * `GL_EXTENSIONS` is a space-separated list, and a plain `contains`
         * would report `EXT_color_buffer_half_float` present on a driver
         * advertising only `EXT_color_buffer_half_float_something_else` — or,
         * more realistically, match a vendor-prefixed near-namesake. Split on
         * whitespace and compare whole tokens.
         *
         * Internal rather than private so `GlCapsTest` can pin that: the
         * substring bug is invisible until the device that has the lookalike
         * extension is the one that renders wrong.
         */
        internal fun hasExtension(extensions: String, name: String): Boolean =
            extensions.splitToSequence(' ', '\t', '\n').any { it == name }

        /**
         * `GL_VERSION` on ES reads `OpenGL ES 3.2 v1.r26p0-01eac0`. Parse the
         * first `major.minor` after the "ES" marker; anything unparseable
         * answers 0.0, which [isSupported] refuses — an unreadable version
         * string is not a reason to assume ES 3.
         */
        internal fun parseEsVersion(version: String): Pair<Int, Int> {
            val match = Regex("""(\d+)\.(\d+)""").find(version) ?: return 0 to 0
            return match.groupValues[1].toInt() to match.groupValues[2].toInt()
        }
    }
}
