package ch.lkmc.bangnidraw.engine.core

/** Converts the panel's top-first order to the model's bottom-first order. */
internal object LayerPanelOrder {
    data class Move(val from: Int, val to: Int)

    fun stackIndex(displayIndex: Int, size: Int): Int {
        require(displayIndex in 0 until size)

        return size - displayIndex - 1
    }

    fun displayIndex(stackIndex: Int, size: Int): Int {
        require(stackIndex in 0 until size)

        return size - stackIndex - 1
    }

    fun move(fromDisplay: Int, toDisplay: Int, size: Int): Move? {
        if (fromDisplay !in 0 until size || toDisplay !in 0 until size) return null
        if (fromDisplay == toDisplay) return null

        return Move(
            from = stackIndex(fromDisplay, size),
            to = stackIndex(toDisplay, size),
        )
    }
}
