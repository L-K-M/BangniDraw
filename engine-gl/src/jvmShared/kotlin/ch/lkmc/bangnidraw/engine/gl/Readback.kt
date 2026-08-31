package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.gl.platform.GLES30
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.SliceHandle
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.nio.ByteBuffer

/**
 * Copies merged tiles back to the CPU, asynchronously
 * (`docs/plan/03-canvas-engine.md` §10.1).
 *
 * After a merge the GPU holds the truth for the touched keys and the CPU does
 * not. Two pixel-pack buffers are filled with `glReadPixels` — which returns
 * immediately when a PBO is bound — a fence is placed after each chunk, and
 * the results are mapped on a later GL entry once the fence has signalled. A
 * synchronous `glReadPixels` would stall the render thread for the whole GPU
 * pipeline depth on every pen-up.
 *
 * **`enqueue`'s wait when both PBOs are busy is back-pressure, and only that.**
 * It exists so a third PBO never has to be allocated. It is emphatically *not*
 * §10.1's ordering rule, and an earlier revision of this comment claimed it
 * was: the wait fires only when the round-robin lands on a slot still in
 * flight, so a readback of at most [READBACK_CHUNK] tiles — a normal stroke —
 * leaves the other slot free and the next `enqueue` never waits at all.
 *
 * §10.1's actual rule is a rule about the **journal capture**, and it cannot
 * live here: a merge for stroke *n+1* must not capture its "before" tiles
 * until stroke *n*'s readback has been mapped into `TileStore`, or the capture
 * records pre-*n* contents and undoing *n+1* silently reverts *n* too. That
 * capture happens at the merge call site *before* `enqueue` is reached, so no
 * amount of waiting inside `enqueue` can protect it — draining every slot here
 * would buy a blocking stall on the GL thread and still leave the hole open.
 * [finish] is the call the capture site needs, before it reads `TileStore`.
 *
 * Nothing calls it yet, because nothing captures yet: `TileStore`, the journal
 * and the undo stack are step 3's, and `CanvasRenderer.endStroke` is passed a
 * null `Readback` today. Stated at this length because the rule is invisible
 * from the capture site, and because a comment that says the guarantee is
 * already handled here is worse than no comment at all.
 *
 * GL-thread-only. The mapped bytes leave through [onTile], which the caller
 * hands to a `Channel` consumed on `Dispatchers.IO`.
 */
class Readback(
    /**
     * Receives one finished tile. Called on the GL thread, so it must not
     * block.
     *
     * **The buffer dies when this returns.** It is a view of the mapped PBO,
     * which is unmapped immediately after the callback and refilled by the next
     * readback, so the bytes must be copied *here* — into a pooled buffer, per
     * §10.1's `TileBufferPool` — before being handed on. Handing the
     * `ByteBuffer` itself to the `Channel` the class KDoc describes would read
     * torn or unrelated pixels, and would do so only under timings a healthy
     * GPU almost never produces in a test.
     */
    private val onTile: (LayerId, TileKey, Int, ByteBuffer) -> Unit,
) {

    private class Chunk {
        val pbo = IntArray(1)
        var fence: Long = 0L
        var layer: LayerId? = null
        var revision: Int = 0
        val keys = ArrayList<TileKey>(READBACK_CHUNK)
        val inFlight: Boolean get() = fence != 0L
    }

    private val chunks = Array(2) { Chunk() }
    private val fbo = GlFbo()
    private var next = 0
    private var built = false

    /** How many chunks are waiting on a fence — for tests and the debug overlay. */
    val pending: Int get() = chunks.count { it.inFlight }

    /**
     * Reads [keys] of [layer] out of the GPU.
     *
     * Splits into chunks of [READBACK_CHUNK] tiles, because one PBO sized for
     * the whole key set of an 8192² flatten would be 256 MiB. §10.4 streams
     * exactly this way.
     */
    fun enqueue(
        layer: LayerId,
        textures: LayerTextures,
        keys: List<TileKey>,
        revision: Int,
    ) {
        if (keys.isEmpty()) return
        ensureBuilt()
        var from = 0
        while (from < keys.size) {
            val to = minOf(from + READBACK_CHUNK, keys.size)
            enqueueChunk(layer, textures, keys, from, to, revision)
            from = to
        }
    }

    private fun enqueueChunk(
        layer: LayerId,
        textures: LayerTextures,
        keys: List<TileKey>,
        from: Int,
        to: Int,
        revision: Int,
    ) {
        val chunk = chunks[next]
        // Both PBOs busy: wait out the oldest rather than allocating a third.
        // Back-pressure only — see the class KDoc for why this is not, and
        // cannot be, §10.1's journal-ordering rule.
        if (chunk.inFlight) {
            drain(chunk, block = true)
            if (chunk.inFlight) {
                // `drain` deliberately leaves a timed-out chunk in flight so a
                // later poll can retry it — but this path is about to reuse the
                // chunk, and reusing it would overwrite `fence` without
                // deleting the old sync object and clear `keys` without ever
                // delivering them. Drop it explicitly instead: the same "stale
                // mirror" outcome as `drain`'s failure branch, arrived at
                // deliberately rather than by leaking.
                GLES30.glDeleteSync(chunk.fence)
                chunk.fence = 0L
                chunk.keys.clear()
                chunk.layer = null
            }
        }
        next = (next + 1) % chunks.size

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, chunk.pbo[0])
        chunk.keys.clear()
        var offset = 0
        for (i in from until to) {
            val key = keys[i]
            val handle: SliceHandle = textures.slice(key)
            if (handle.isNone) continue
            if (!fbo.bindArrayLayer(textures.pageTexture(handle.page), handle.slice)) continue
            GLES30.glReadPixels(
                0, 0, TILE_SIZE, TILE_SIZE,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, offset,
            )
            chunk.keys.add(key)
            offset += TILE_BYTES
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)

        if (chunk.keys.isEmpty()) return
        chunk.layer = layer
        chunk.revision = revision
        chunk.fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (chunk.fence == 0L) {
            // A driver that refuses a fence cannot tell us when the read is
            // done. Falling back to "assume it is" would hand out garbage, so
            // the chunk is dropped and the CPU mirror simply stays stale — the
            // tile is still correct on the GPU and on the next save.
            chunk.keys.clear()
            chunk.layer = null
        }
    }

    /**
     * Maps whatever has finished. Call from every GL-thread entry and from
     * §10.1's `Choreographer`-driven poll while [pending] is non-zero.
     */
    fun poll() {
        for (chunk in chunks) if (chunk.inFlight) drain(chunk, block = false)
    }

    /**
     * Blocks until everything in flight has been handed over — context
     * teardown (§12) and the synchronous flatten of §10.4.
     */
    fun finish() {
        for (chunk in chunks) if (chunk.inFlight) drain(chunk, block = true)
    }

    private fun drain(chunk: Chunk, block: Boolean) {
        val timeout = if (block) FENCE_TIMEOUT_NS else 0L
        val flags = if (block) GLES30.GL_SYNC_FLUSH_COMMANDS_BIT else 0
        val status = GLES30.glClientWaitSync(chunk.fence, flags, timeout)
        when (status) {
            GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> Unit

            // Not ready yet. A non-blocking poll expects this; a blocking wait
            // that still timed out leaves the chunk in flight so the next call
            // retries, because dropping it here would strand the fence.
            GLES30.GL_TIMEOUT_EXPIRED -> return

            // GL_WAIT_FAILED, or anything else a driver invents. The fence can
            // no longer say whether the read finished, so the bytes in the PBO
            // may be half-written — handing them on would put torn pixels into
            // the CPU mirror and from there into the journal and the save.
            // Drop the chunk instead: the tiles are still correct on the GPU,
            // the mirror stays stale, and the next merge reads them again.
            else -> {
                GLES30.glDeleteSync(chunk.fence)
                chunk.fence = 0L
                chunk.keys.clear()
                chunk.layer = null
                return
            }
        }

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, chunk.pbo[0])
        val mapped = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER, 0, chunk.keys.size * TILE_BYTES,
            GLES30.GL_MAP_READ_BIT,
        ) as? ByteBuffer
        if (mapped != null) {
            val layer = chunk.layer
            if (layer != null) {
                for ((i, key) in chunk.keys.withIndex()) {
                    mapped.position(i * TILE_BYTES)
                    mapped.limit((i + 1) * TILE_BYTES)
                    // Row order already matches the CPU tile format (§3.1: row
                    // 0 = canvas top), so the consumer's copy is a memcpy.
                    onTile(layer, key, chunk.revision, mapped.slice())
                }
                mapped.clear()
            }
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)

        GLES30.glDeleteSync(chunk.fence)
        chunk.fence = 0L
        chunk.keys.clear()
        chunk.layer = null
    }

    private fun ensureBuilt() {
        if (built) return
        for (chunk in chunks) {
            GlErrors.checkAllocation("readback PBO ($READBACK_CHUNK tiles)") {
                GLES30.glGenBuffers(1, chunk.pbo, 0)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, chunk.pbo[0])
                GLES30.glBufferData(
                    GLES30.GL_PIXEL_PACK_BUFFER,
                    READBACK_CHUNK * TILE_BYTES,
                    null,
                    GLES30.GL_STREAM_READ,
                )
            }
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        built = true
    }

    /**
     * Drops everything without mapping — §12's context loss, where the fences
     * and PBOs are already gone and waiting on them would hang.
     */
    fun forgetAll() {
        for (chunk in chunks) {
            chunk.fence = 0L
            chunk.keys.clear()
            chunk.layer = null
        }
        built = false
    }

    fun release() {
        if (built) {
            for (chunk in chunks) {
                if (chunk.inFlight) GLES30.glDeleteSync(chunk.fence)
                chunk.fence = 0L
                GLES30.glDeleteBuffers(1, chunk.pbo, 0)
            }
            built = false
        }
        fbo.release()
    }

    private companion object {
        /** §10.1: 64 tiles is 16 MiB per PBO. */
        const val READBACK_CHUNK = 64

        /**
         * A second. Long enough that no healthy GPU reaches it, short enough
         * that a wedged one does not hang the render thread forever — the
         * chunk stays in flight and the next poll retries.
         */
        const val FENCE_TIMEOUT_NS = 1_000_000_000L
    }
}
