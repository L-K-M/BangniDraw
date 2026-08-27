package ch.lkmc.bangnidraw.engine.core

/** Protects transient tile capacity when reopening a stack saved under an older cap. */
internal object TileCapacityPolicy {

    fun hasTransientReserve(layerCount: Int, maxLayers: Int): Boolean {
        require(layerCount >= 0) { "layerCount must not be negative" }
        require(maxLayers >= 0) { "maxLayers must not be negative" }

        return layerCount <= maxLayers
    }
}
