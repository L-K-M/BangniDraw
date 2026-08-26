package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.core.BufferMode
import ch.lkmc.bangnidraw.engine.core.DabBatch
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.PoolExhausted
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Stamps a batch of dabs into the stroke buffer
 * (`docs/plan/03-canvas-engine.md` §7.2).
 *
 * **Grouped by tile key, not drawn in arrival order.** Each touched key gets
 * one FBO bind and one `glDrawArraysInstanced`. On tile-based GPUs — every
 * phone this ships to — an FBO rebind ends a render pass and flushes tile
 * memory, so drawing dabs in arrival order across keys would cost a render
 * pass per dab instead of one per key.
 *
 * Instance order within a key equals batch order, and GL blends primitives in
 * order, so overlap within a batch is deterministic and matches the fold
 * `DabStamp.blendIntoBuffer` performs on the CPU — the property §7.2 states
 * and `DabStampTest` pins on the JVM side.
 *
 * **One allocation per dab, and no more** (`10-performance.md` §2.4). Every
 * scratch array and the instance buffer are fields that grow and are reused.
 * The exception is `IntRect.forDab`, whose own KDoc calls it out as the
 * allocation the dab path makes; it runs once per dab here, with the bounds
 * cached for the per-key pass so the cost does not multiply by the number of
 * tiles a batch touches.
 *
 * GL-thread-only.
 */
class DabPass(
    private val program: GlProgram,
    private val state: GlState,
) {

    private val fbo = GlFbo()
    private val vao = IntArray(1)
    private val cornerVbo = IntArray(1)
    private val instanceVbo = IntArray(1)
    private var instanceCapacityDabs = 0
    private var built = false

    /**
     * Per-dab instance data, interleaved in [INSTANCE_LAYOUT]'s order.
     *
     * Interleaved rather than one buffer per attribute: the GPU reads a whole
     * instance at once, and separate streams would be six strided fetches per
     * dab for no gain.
     */
    private var instanceData = FloatArray(0)
    private var instanceBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** The distinct keys the current batch touches, packed. */
    private var distinctKeys = IntArray(0)

    /** Dense "already collected" flags, indexed by tile, cleared after each batch. */
    private var seen = BooleanArray(0)

    /** Scratch for the keys a single dab touches. */
    private val dabKeys = IntArray(MAX_KEYS_PER_DAB)

    /**
     * Each dab's canvas rect, four ints per dab, computed once per batch by
     * [collectKeys] and read by [gatherDabsFor].
     *
     * `IntRect.forDab` allocates — its own KDoc calls that out as the one
     * allocation the dab path makes — and `gatherDabsFor` runs once per
     * *touched key*, so recomputing there cost `dabs x keys` short-lived
     * objects per batch: four thousand of them for a full batch across four
     * tiles, on the path `10-performance.md` §2.4 is about.
     */
    private var dabBounds = IntArray(0)

    /**
     * Stamps [batch] into [buffer], returning the canvas rect it dirtied and
     * growing the buffer's own dirty rect by it.
     *
     * A key whose slice the pool refuses is **skipped**, not fatal: §2.1 makes
     * a full pool a normal outcome the caller declines. A dropped tile is a
     * gap in one stroke; a crash loses the painting. §7.1's reservation of a
     * full layer's worth for the buffer is what makes this the pathological
     * stroke rather than the ordinary one.
     *
     * `[from, until)` selects part of the batch, which is how one batch reaches
     * two different buffers: §8.1's header marks the predicted dabs
     * ([DabBatch.predictedFrom]) and they go to the tail rather than the stroke
     * buffer (§9). The default is the whole batch.
     */
    fun stamp(
        batch: DabBatch,
        buffer: StrokeBuffer,
        mode: BufferMode,
        colorR: Float,
        colorG: Float,
        colorB: Float,
        from: Int = 0,
        until: Int = batch.count,
    ): IntRect {
        require(from >= 0 && until <= batch.count && from <= until) {
            "dab range $from..$until is outside 0..${batch.count}"
        }
        val dabs = until - from
        if (dabs == 0) return IntRect.EMPTY
        val grid = buffer.grid
        ensureBuilt()
        ensureInstanceCapacity(dabs)
        if (instanceCapacityDabs < dabs) return IntRect.EMPTY

        state.useProgram(program)
        program.uniform3f("u_color", colorR, colorG, colorB)
        when (mode) {
            BufferMode.Accumulate -> state.blendSourceOver()
            BufferMode.Max -> state.blendMax()
        }
        state.viewport(0, 0, TILE_SIZE, TILE_SIZE)
        // Every dab is clipped to the slice by its own quad geometry, so no
        // scissor is wanted here — and one left enabled by a previous pass
        // would silently clip dabs to that pass's rect.
        state.scissorOff()
        GLES30.glBindVertexArray(vao[0])

        val keyCount = collectKeys(batch, grid, from, until)
        var dirty = IntRect.EMPTY
        for (i in 0 until keyCount) {
            val key = TileKey(distinctKeys[i])
            val n = gatherDabsFor(batch, grid, key, from, until)
            if (n == 0) continue
            val handle = try {
                buffer.sliceForWrite(key)
            } catch (_: PoolExhausted) {
                continue
            }
            if (!fbo.bindArrayLayer(buffer.pageTexture(handle.page), handle.slice)) continue

            val origin = grid.origin(key)
            program.uniform2f("u_tileOrigin", origin.x.toFloat(), origin.y.toFloat())
            uploadInstances(n)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, CORNERS, n)
            dirty = dirty.union(grid.tileRect(key))
        }

        GLES30.glBindVertexArray(0)
        // GL_MAX is sticky context state. Leaving it set would make the next
        // composite take the maximum of source and destination instead of
        // blending — a whole-screen corruption that reads as a shader bug.
        // GlState caches the equation, so under Accumulate this costs nothing.
        state.blendSourceOver()
        buffer.growDirty(dirty)
        return dirty
    }

    // ------------------------------------------------------------- gathering

    /**
     * Fills [distinctKeys] with the keys [batch] touches and returns how many.
     *
     * A dense flag array rather than a `HashSet`: a set would allocate per
     * batch on the touch path, and the grid's tile count bounds the flags at
     * `TileGrid.MAX_TILES` booleans. The flags are cleared by walking the keys
     * just collected, not by refilling the whole array, so the cost is the
     * number of touched tiles rather than the canvas size.
     */
    private fun collectKeys(batch: DabBatch, grid: TileGrid, from: Int, until: Int): Int {
        val tiles = grid.tileCount
        if (seen.size < tiles) seen = BooleanArray(tiles)
        if (distinctKeys.size < tiles) distinctKeys = IntArray(tiles)
        // Indexed by the dab's own batch index, not by its offset within the
        // range, so [gatherDabsFor] can read it with the same `i`. Sized for
        // the whole batch for that reason: a range starting late in a batch
        // still writes at `i * 4`.
        if (dabBounds.size < batch.count * 4) dabBounds = IntArray(batch.count * 4)
        var distinct = 0
        for (i in from until until) {
            val rect = IntRect.forDab(batch.x[i], batch.y[i], batch.radius[i])
            val o = i * 4
            dabBounds[o] = rect.left
            dabBounds[o + 1] = rect.top
            dabBounds[o + 2] = rect.right
            dabBounds[o + 3] = rect.bottom
            val n = grid.keysFor(rect, dabKeys)
            for (j in 0 until n) {
                val key = TileKey(dabKeys[j])
                val index = key.ty * grid.tilesX + key.tx
                // Bounded by the grid's own tile count, not by `seen.size`:
                // the arrays are grown and never shrunk, so a smaller grid
                // would otherwise let an index past its end mark a flag that
                // the clearing loop below never visits.
                if (index < 0 || index >= tiles || seen[index]) continue
                seen[index] = true
                distinctKeys[distinct++] = dabKeys[j]
            }
        }
        for (i in 0 until distinct) {
            val key = TileKey(distinctKeys[i])
            seen[key.ty * grid.tilesX + key.tx] = false
        }
        return distinct
    }

    /**
     * Copies the dabs whose rect meets [key] into [instanceData], **in batch
     * order**, and returns how many.
     *
     * Batch order is the contract: GL blends instances in order, so this is
     * what makes GPU overlap equal `DabStamp`'s CPU fold.
     */
    private fun gatherDabsFor(batch: DabBatch, grid: TileGrid, key: TileKey, from: Int, until: Int): Int {
        val tile = grid.tileRect(key)
        var n = 0
        for (i in from until until) {
            val b = i * 4
            if (dabBounds[b + 2] <= tile.left || dabBounds[b] >= tile.right) continue
            if (dabBounds[b + 3] <= tile.top || dabBounds[b + 1] >= tile.bottom) continue
            var o = n * DAB_FLOATS
            instanceData[o++] = batch.x[i]
            instanceData[o++] = batch.y[i]
            instanceData[o++] = batch.radius[i]
            instanceData[o++] = batch.hardness[i]
            instanceData[o++] = batch.flow[i]
            instanceData[o++] = batch.angle[i]
            instanceData[o] = batch.aspect[i]
            n++
        }
        return n
    }

    private fun uploadInstances(n: Int) {
        instanceBuffer.position(0)
        instanceBuffer.put(instanceData, 0, n * DAB_FLOATS)
        instanceBuffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo[0])
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * DAB_FLOATS * 4, instanceBuffer)
    }

    // -------------------------------------------------------------- plumbing

    private fun ensureInstanceCapacity(dabs: Int) {
        if (dabs <= instanceCapacityDabs) return
        val capacity = maxOf(dabs, instanceCapacityDabs * 2, MIN_INSTANCE_DABS)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, capacity * DAB_FLOATS * 4, null, GLES30.GL_STREAM_DRAW,
        )
        // Capacity is committed only once the driver has accepted it.
        // GL_OUT_OF_MEMORY here would otherwise leave the pass believing it has
        // room it does not, and the next glBufferSubData would write past the
        // buffer's real end. `stamp` checks the capacity again and draws
        // nothing rather than corrupting memory.
        if (GlErrors.checkAllocation("dab instance VBO ($capacity dabs)") != GLES30.GL_NO_ERROR) return
        instanceData = FloatArray(capacity * DAB_FLOATS)
        instanceBuffer = ByteBuffer
            .allocateDirect(capacity * DAB_FLOATS * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        instanceCapacityDabs = capacity
    }

    private fun ensureBuilt() {
        if (built) return
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, cornerVbo, 0)
        GLES30.glGenBuffers(1, instanceVbo, 0)
        GLES30.glBindVertexArray(vao[0])

        // The unit quad every instance shares: (-1,-1)..(1,1) as a strip.
        val corners = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val cornerBuf = ByteBuffer.allocateDirect(corners.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(corners)
        cornerBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cornerVbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, corners.size * 4, cornerBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(Shaders.ATTR_DAB_CORNER)
        GLES30.glVertexAttribPointer(Shaders.ATTR_DAB_CORNER, 2, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo[0])
        val stride = DAB_FLOATS * 4
        var offset = 0
        for ((location, size) in INSTANCE_LAYOUT) {
            GLES30.glEnableVertexAttribArray(location)
            GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, stride, offset)
            // The divisor is what makes these per-dab rather than per-vertex.
            // Without it every dab in a batch would draw with the first dab's
            // parameters: four correct-looking quads stacked in one place.
            GLES30.glVertexAttribDivisor(location, 1)
            offset += size * 4
        }
        GLES30.glBindVertexArray(0)
        built = true
    }

    fun release() {
        if (built) {
            GLES30.glDeleteVertexArrays(1, vao, 0)
            GLES30.glDeleteBuffers(1, cornerVbo, 0)
            GLES30.glDeleteBuffers(1, instanceVbo, 0)
            built = false
            // The staging pair goes with the capacity that describes it.
            // `instanceCapacityDabs = 0` alone would leave both alive but
            // unreachable-by-bookkeeping: the next `ensureInstanceCapacity`
            // reallocates from MIN_INSTANCE_DABS regardless, so the grown ones
            // could never be read again — and `instanceBuffer` is a direct
            // buffer, whose off-heap bytes are freed only when the buffer
            // object itself becomes unreachable. A pass kept across
            // context-loss cycles would hold one dead megabyte per cycle.
            //
            // `distinctKeys` and `seen` are deliberately NOT reset: their sizes
            // track the tile grid rather than this capacity, nothing here
            // invalidates them, and they are on-heap. They are reuse; these two
            // are waste.
            instanceCapacityDabs = 0
            instanceData = FloatArray(0)
            instanceBuffer = ByteBuffer.allocateDirect(0)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        fbo.release()
    }

    private companion object {
        const val CORNERS = 4

        /** centre (2), radius, hardness, flow, angle, aspect — §6's per-dab fields. */
        const val DAB_FLOATS = 7

        const val MIN_INSTANCE_DABS = 256

        /**
         * A dab spans at most 2×2 tiles at ordinary sizes; the scratch is sized
         * for the largest `DabGenerator` will emit, and `TileGrid.keysFor`
         * stops at the array's length rather than overrunning it.
         */
        const val MAX_KEYS_PER_DAB = 64

        val INSTANCE_LAYOUT = arrayOf(
            Shaders.ATTR_DAB_CENTER to 2,
            Shaders.ATTR_DAB_RADIUS to 1,
            Shaders.ATTR_DAB_HARDNESS to 1,
            Shaders.ATTR_DAB_FLOW to 1,
            Shaders.ATTR_DAB_ANGLE to 1,
            Shaders.ATTR_DAB_ASPECT to 1,
        )
    }
}
