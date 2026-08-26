package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey

/**
 * One tile's pixels on the CPU side, as handed from the readback to the
 * flusher (`docs/plan/03-canvas-engine.md` §10.1).
 *
 * [revision] is the commit sequence the pixels belong to. Readback chunks can
 * complete out of order across two in-flight PBOs, so the flusher compares
 * revisions per key and a stale tile never overwrites a newer one
 * (`docs/plan/12-roadmap.md` step 3, risks).
 *
 * [pixels] is premultiplied RGBA8, [TILE_BYTES] long, and owned by the
 * receiver from the moment the value is handed over — it usually comes from
 * [TileBufferPool] and goes back there after the write.
 */
class CpuTile(
    val layerId: LayerId,
    val key: TileKey,
    val revision: Int,
    val pixels: ByteArray,
) {
    init {
        require(pixels.size == TILE_BYTES) {
            "a tile is $TILE_BYTES bytes, got ${pixels.size}"
        }
    }
}

/**
 * Recycles the 256 KiB tile buffers the readback fills (§10.1's
 * `TileBufferPool`): one is needed per merged tile per stroke, so allocating
 * fresh would produce megabytes of garbage per minute of painting.
 *
 * Thread-safe by one lock — acquire runs on the GL thread, release on the IO
 * writer, and the critical section is a deque operation either way.
 */
class TileBufferPool(private val maxPooled: Int = DEFAULT_MAX_POOLED) {

    private val lock = Any()
    private val free = ArrayDeque<ByteArray>()

    fun acquire(): ByteArray {
        synchronized(lock) {
            free.removeLastOrNull()?.let { return it }
        }
        return ByteArray(TILE_BYTES)
    }

    /**
     * Hands a buffer back. The caller must not touch it afterwards — the next
     * [acquire] returns it, dirty contents and all; every consumer overwrites
     * the full tile, so nothing clears it.
     */
    fun release(buffer: ByteArray) {
        if (buffer.size != TILE_BYTES) return
        synchronized(lock) {
            if (free.size < maxPooled) free.addLast(buffer)
        }
    }

    private companion object {
        /**
         * 64 buffers = 16 MiB, one readback chunk's worth — the most tiles
         * that can be in flight between the GPU and the flusher at once.
         * Beyond that, buffers are dropped to the GC rather than hoarded.
         */
        const val DEFAULT_MAX_POOLED = 64
    }
}
