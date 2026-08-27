package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.PoolExhausted
import ch.lkmc.bangnidraw.engine.core.SliceHandle
import ch.lkmc.bangnidraw.engine.core.TileKey

/** Executes cold structural layer composites without exposing raw GL to UI. */
internal class LayerPixelPass(
    private val pool: TilePool,
    program: GlProgram,
    private val state: GlState,
) {

    data class Source(
        val textures: LayerTextures,
        val keys: Set<TileKey>,
        val mode: BlendMode,
        val opacity: Float,
        val visible: Boolean,
    )

    private data class Pending(val key: TileKey, val handle: SliceHandle)

    interface Transaction {
        fun commit()
        fun abort()
    }

    private inner class PendingTransaction(
        private val target: LayerTextures,
        private val pending: List<Pending>,
        private val removed: List<TileKey> = emptyList(),
    ) : Transaction {
        private var open = true

        override fun commit() {
            check(open) { "layer pixel transaction is already closed" }
            pending.forEach { target.swap(it.key, it.handle) }
            removed.forEach(target::remove)
            open = false
        }

        override fun abort() {
            if (!open) return
            pending.forEach { pool.free(it.handle) }
            open = false
        }
    }

    private val pass = TileCompositePass(program, state, pool)
    private val copyPass = TileCopyPass(pool)
    private val clearFbo = GlFbo()
    private val excludedCopyPage = IntArray(1)
    private val excludedPages = IntArray(2)

    fun copy(
        source: LayerTextures,
        target: LayerTextures,
        keys: Set<TileKey>,
    ): Transaction? {
        val pending = ArrayList<Pending>(keys.size)
        for (key in keys) {
            val sourceHandle = source.slice(key)
            if (sourceHandle.isNone) {
                pending.forEach { pool.free(it.handle) }
                return null
            }

            excludedCopyPage[0] = sourceHandle.page
            val targetHandle = try {
                pool.allocateNotOn(excludedCopyPage)
            } catch (_: PoolExhausted) {
                pending.forEach { pool.free(it.handle) }
                return null
            }
            if (!copyPass.copy(sourceHandle, targetHandle)) {
                pool.free(targetHandle)
                pending.forEach { pool.free(it.handle) }
                return null
            }
            pending += Pending(key, targetHandle)
        }
        return PendingTransaction(target, pending)
    }

    fun merge(
        bottom: Source,
        top: Source,
        target: LayerTextures,
        keys: Set<TileKey>,
    ): Transaction? {
        val bottomAsSource = bottom.copy(mode = BlendMode.NORMAL)
        return composite(listOf(bottomAsSource, top), target, keys)
    }

    fun flatten(
        sources: List<Source>,
        target: LayerTextures,
        keys: Set<TileKey>,
    ): Transaction? = composite(sources, target, keys)

    fun restore(
        target: LayerTextures,
        tiles: Map<TileKey, ByteArray?>,
    ): Transaction? {
        val pending = ArrayList<Pending>(tiles.size)
        val removed = ArrayList<TileKey>()
        for ((key, pixels) in tiles) {
            if (pixels == null) {
                if (!target.slice(key).isNone) removed += key
                continue
            }
            if (pixels.size != TILE_BYTES) {
                pending.forEach { pool.free(it.handle) }
                return null
            }
            val handle = try {
                pool.allocate()
            } catch (_: PoolExhausted) {
                pending.forEach { pool.free(it.handle) }
                return null
            }
            upload(handle, pixels)
            pending += Pending(key, handle)
        }
        return PendingTransaction(target, pending, removed)
    }

    private fun composite(
        sources: List<Source>,
        target: LayerTextures,
        keys: Set<TileKey>,
    ): Transaction? {
        val pending = ArrayList<Pending>(keys.size)
        for (key in keys) {
            val handle = compositeTile(sources, key)
            if (handle == null) {
                pending.forEach { pool.free(it.handle) }
                return null
            }
            pending += Pending(key, handle)
        }
        return PendingTransaction(target, pending)
    }

    private fun compositeTile(sources: List<Source>, key: TileKey): SliceHandle? {
        var current = allocateTransparent() ?: return null
        for (source in sources) {
            if (!source.visible || source.opacity <= 0f) continue
            if (key !in source.keys) continue
            val sourceHandle = source.textures.slice(key)
            if (sourceHandle.isNone) {
                pool.free(current)
                return null
            }

            excludedPages[0] = current.page
            excludedPages[1] = sourceHandle.page
            val next = try {
                pool.allocateNotOn(excludedPages)
            } catch (_: PoolExhausted) {
                pool.free(current)
                return null
            }
            if (!pass.draw(sourceHandle, current, next, source.mode, source.opacity)) {
                pool.free(next)
                pool.free(current)
                return null
            }

            pool.free(current)
            current = next
        }
        return current
    }

    private fun allocateTransparent(): SliceHandle? {
        val handle = try {
            pool.allocate()
        } catch (_: PoolExhausted) {
            return null
        }
        if (!clearFbo.bindArrayLayer(pool.textureOf(handle.page), handle.slice)) {
            pool.free(handle)
            return null
        }
        state.scissorOff()
        state.viewport(0, 0, TILE_SIZE, TILE_SIZE)
        clearFbo.clear(0f, 0f, 0f, 0f)
        return handle
    }

    private fun upload(handle: SliceHandle, pixels: ByteArray) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, pool.textureOf(handle.page))
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage3D(
            GLES30.GL_TEXTURE_2D_ARRAY,
            0,
            0,
            0,
            handle.slice,
            TILE_SIZE,
            TILE_SIZE,
            1,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            java.nio.ByteBuffer.wrap(pixels),
        )
        GlErrors.checkGlDebug("layer restore")
    }

    fun release() {
        pass.release()
        copyPass.release()
        clearFbo.release()
    }
}
