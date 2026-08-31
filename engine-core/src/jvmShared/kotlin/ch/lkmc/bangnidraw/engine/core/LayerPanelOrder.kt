package ch.lkmc.bangnidraw.engine.core

enum class LayerReorderAction {
    UP,
    DOWN,
    TOP,
    BOTTOM,
}

/** Converts the panel's top-first order to the model's bottom-first order. */
object LayerPanelOrder {
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

    fun actions(stackIndex: Int, size: Int): List<LayerReorderAction> {
        if (stackIndex !in 0 until size) return emptyList()

        return LayerReorderAction.entries.filter { move(stackIndex, it, size) != null }
    }

    fun move(stackIndex: Int, action: LayerReorderAction, size: Int): Move? {
        if (stackIndex !in 0 until size) return null

        val target = when (action) {
            LayerReorderAction.UP -> stackIndex + 1
            LayerReorderAction.DOWN -> stackIndex - 1
            LayerReorderAction.TOP -> size - 1
            LayerReorderAction.BOTTOM -> 0
        }
        if (target !in 0 until size || target == stackIndex) return null

        return Move(from = stackIndex, to = target)
    }
}
