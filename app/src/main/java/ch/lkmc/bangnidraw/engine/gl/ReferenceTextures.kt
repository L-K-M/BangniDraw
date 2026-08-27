package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.nio.ByteBuffer

/** GPU tiles for one tracing asset, kept outside the paint-layer map. */
internal class ReferenceTextures(
    width: Int,
    height: Int,
    pool: TilePool,
) {
    internal val layer = LayerTextures(TileGrid(width, height), pool)

    fun upload(key: TileKey, pixels: ByteBuffer) = layer.upload(key, pixels)

    fun release() = layer.release()

    fun forgetAll() = layer.forgetAll()
}
