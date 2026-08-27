package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TRANSIENT_TILE_RESERVE_LAYERS

/** Protects transient tile capacity when reopening on a smaller device. */
internal object TileCapacityPolicy {

    /** Every relisted sparse tile needs one pool slice before the canvas opens. */
    fun residentTilesFit(residentTiles: Long, poolSlices: Long): Boolean {
        require(residentTiles >= 0L) { "residentTiles must not be negative" }
        require(poolSlices >= 0L) { "poolSlices must not be negative" }

        return residentTiles <= poolSlices
    }

    fun withinLayerCap(layerCount: Int, maxLayers: Int): Boolean {
        require(layerCount >= 0) { "layerCount must not be negative" }
        require(maxLayers >= 0) { "maxLayers must not be negative" }

        return layerCount <= maxLayers
    }

    /** The clamped layer cap is not capacity proof for an oversized reopen. */
    fun hasTransientReserve(
        layerCount: Int,
        canvas: CanvasSize,
        poolSliceCapacity: Long,
    ): Boolean {
        require(layerCount >= 0) { "layerCount must not be negative" }
        require(poolSliceCapacity >= 0L) { "poolSliceCapacity must not be negative" }
        val tilesPerLayer = canvas.tilesPerLayer
        if (tilesPerLayer <= 0L) return false

        val requiredLayers = layerCount.toLong() + TRANSIENT_TILE_RESERVE_LAYERS
        if (tilesPerLayer > Long.MAX_VALUE / requiredLayers) return false

        val requiredSlices = requiredLayers * tilesPerLayer
        return requiredSlices <= poolSliceCapacity
    }
}
