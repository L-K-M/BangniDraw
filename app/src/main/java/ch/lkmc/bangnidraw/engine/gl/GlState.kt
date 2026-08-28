package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30

/**
 * A shadow of the GL state the engine actually changes, so redundant calls
 * never reach the driver.
 *
 * Every setter here is on the frame path — the compositor touches blend,
 * scissor, viewport, program and one texture bind per page, per layer, per
 * frame — and on mobile drivers a redundant state change is not free: it can
 * flush a tile or re-validate the pipeline. §3.4 asks for this explicitly for
 * the sampler filter ("`glTexParameteri` is called on each page only when the
 * filter changes"); the rest follows the same shape.
 *
 * **The cache is only true while nothing else issues GL calls.** graphics-core
 * runs our callbacks with a framebuffer already bound and can change state
 * between frames, so [invalidate] is called at the top of every frame and
 * after any context event. A cache that silently went stale would skip the
 * one call that mattered, which is far worse than the redundancy it saves.
 */
class GlState {

    private var program = 0
    private var blendEnabled: Boolean? = null
    private var blendSrc = -1
    private var blendDst = -1
    private var blendEquation = -1
    private var ditherEnabled: Boolean? = null
    private var scissorEnabled: Boolean? = null
    private var scissorX = -1
    private var scissorY = -1
    private var scissorW = -1
    private var scissorH = -1
    private var viewportX = -1
    private var viewportY = -1
    private var viewportW = -1
    private var viewportH = -1

    /**
     * Per texture id, the min/mag filter it was last set to — `GL_NEAREST` or
     * `GL_LINEAR` (§3.4). A `HashMap` rather than an array because texture ids
     * are driver-assigned and not dense; it is read once per page per frame,
     * which is single digits.
     */
    private val filters = HashMap<Int, Int>()

    /**
     * Forgets everything. Call at the top of every frame and whenever another
     * component may have issued GL calls.
     */
    fun invalidate() {
        program = 0
        blendEnabled = null
        blendSrc = -1
        blendDst = -1
        blendEquation = -1
        ditherEnabled = null
        scissorEnabled = null
        scissorX = -1
        scissorY = -1
        scissorW = -1
        scissorH = -1
        viewportX = -1
        viewportY = -1
        viewportW = -1
        viewportH = -1
        // Deliberately NOT cleared: filters are texture *object* state, which
        // survives anything another component does to the bound-state machine.
        // Only deleting the texture invalidates it, and `forgetTexture` says so.
    }

    /**
     * Drops [texture]'s remembered filter. **Every path that deletes a texture
     * this engine owns must route through here or [forgetAllTextures].**
     *
     * Drivers recycle texture ids. A stale entry means a *new* texture behind a
     * recycled id is believed to already carry its filter, [textureFilter]
     * skips the `glTexParameteri`, and the fresh page keeps GL's default
     * `GL_NEAREST_MIPMAP_LINEAR` — which on a page with one level is an
     * incomplete texture that samples black, with no GL error. That is the
     * "cache that silently went stale" this class's KDoc warns about, and it is
     * why `TilePool.release` takes a [GlState] rather than defaulting to null.
     */
    fun forgetTexture(texture: Int) {
        filters.remove(texture)
    }

    /** Drops every remembered filter — the context died, so every id is stale. */
    fun forgetAllTextures() {
        filters.clear()
    }

    fun useProgram(p: GlProgram) {
        if (program != p.id) {
            GLES30.glUseProgram(p.id)
            program = p.id
        }
    }

    /**
     * Source-over with premultiplied alpha — `GL_ONE,
     * GL_ONE_MINUS_SRC_ALPHA` — which is the whole reason §2.4 stores
     * premultiplied: hardware blending is then a single multiply-add and needs
     * no backdrop read.
     */
    fun blendSourceOver() =
        setBlend(true, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_FUNC_ADD)

    /**
     * `glBlendEquation(GL_MAX)` — the stroke buffer's `BufferMode.Max` (§7.2),
     * where overlapping dabs within one stroke never exceed the strongest
     * single dab.
     *
     * GL ignores the blend factors entirely under `GL_MAX`, but they are still
     * set to a fixed pair rather than left alone: the cache below has to hold
     * one known state per mode, or the next `blendSourceOver` could decide the
     * factors already match and skip the `glBlendFunc` that `GL_MAX` never
     * needed to issue.
     */
    fun blendMax() = setBlend(true, GLES30.GL_ONE, GLES30.GL_ONE, GLES30.GL_MAX)

    /** Blending off: the shader writes the finished composite (§3.3). */
    fun blendOff() = setBlend(false, 0, 0, GLES30.GL_FUNC_ADD)

    /** Fixed-point simulation targets must not receive driver dithering. */
    fun ditherOff() {
        if (ditherEnabled == false) return

        GLES30.glDisable(GLES30.GL_DITHER)
        ditherEnabled = false
    }

    /**
     * The equation is cached alongside the factors, and that is not
     * housekeeping. `GL_MAX` is sticky context state: a pass that set it and a
     * later `blendSourceOver` that only compared factors would leave every
     * subsequent composite maxing instead of blending — a whole-screen
     * corruption that no JVM test can reach and that looks like a shader bug.
     */
    private fun setBlend(enabled: Boolean, src: Int, dst: Int, equation: Int) {
        if (blendEnabled != enabled) {
            if (enabled) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
            blendEnabled = enabled
        }
        if (!enabled) return
        if (src != blendSrc || dst != blendDst) {
            GLES30.glBlendFunc(src, dst)
            blendSrc = src
            blendDst = dst
        }
        if (equation != blendEquation) {
            GLES30.glBlendEquation(equation)
            blendEquation = equation
        }
    }

    /**
     * Scissors to a rect in **target** pixels, y measured from the bottom —
     * GL's convention, and the one place besides `u_projection` where it
     * surfaces. Callers hold y-down rects (§3.1's row convention), so they
     * convert here rather than each keeping its own flip.
     */
    fun scissor(x: Int, yFromBottom: Int, width: Int, height: Int) {
        if (scissorEnabled != true) {
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            scissorEnabled = true
        }
        if (x != scissorX || yFromBottom != scissorY || width != scissorW || height != scissorH) {
            GLES30.glScissor(x, yFromBottom, width, height)
            scissorX = x
            scissorY = yFromBottom
            scissorW = width
            scissorH = height
        }
    }

    fun scissorOff() {
        if (scissorEnabled != false) {
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            scissorEnabled = false
        }
    }

    fun viewport(x: Int, y: Int, width: Int, height: Int) {
        if (x != viewportX || y != viewportY || width != viewportW || height != viewportH) {
            GLES30.glViewport(x, y, width, height)
            viewportX = x
            viewportY = y
            viewportW = width
            viewportH = height
        }
    }

    /**
     * Sets [texture]'s min and mag filter to [filter] if it is not already,
     * assuming the texture is bound to [target].
     *
     * §3.4's table picks the filter from `ScreenTransform.effectiveScale`:
     * `GL_NEAREST` at 4× and above, where users are placing individual pixels
     * and bilinear turns the grid to mush; `GL_LINEAR` below it.
     */
    fun textureFilter(target: Int, texture: Int, filter: Int) {
        if (filters[texture] == filter) return
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        filters[texture] = filter
    }
}
