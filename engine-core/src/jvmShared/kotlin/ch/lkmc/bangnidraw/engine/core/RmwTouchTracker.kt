package ch.lkmc.bangnidraw.engine.core

/** Tracks the before-image keys of one direct-to-layer stroke without repeats. */
class RmwTouchTracker(private val grid: TileGrid) {

    private val seen = BooleanArray(grid.tileCount)
    private val candidates = IntArray(grid.tileCount)
    private val touched = IntArray(grid.tileCount)
    private var touchedCount = 0

    /** Writes newly touched packed keys to [out] and returns their count. */
    fun add(rect: IntRect, out: IntArray): Int =
        add(rect.left, rect.top, rect.right, rect.bottom, out)

    /** Primitive form for direct-to-layer dab loops. */
    fun add(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        out: IntArray,
    ): Int {
        val candidateCount = grid.keysForBounds(left, top, right, bottom, candidates)
        require(out.size >= candidateCount) {
            "RMW touch output needs $candidateCount slots, got ${out.size}"
        }
        var added = 0
        for (i in 0 until candidateCount) {
            val packed = candidates[i]
            val key = TileKey(packed)
            val index = key.ty * grid.tilesX + key.tx
            if (seen[index]) continue

            seen[index] = true
            touched[touchedCount++] = packed
            out[added++] = packed
        }

        return added
    }

    /** Writes every touched packed key to [out], in first-touch order. */
    fun all(out: IntArray): Int {
        require(out.size >= touchedCount) {
            "RMW touch output needs $touchedCount slots, got ${out.size}"
        }
        touched.copyInto(out, endIndex = touchedCount)
        return touchedCount
    }

    fun reset() {
        for (i in 0 until touchedCount) {
            val key = TileKey(touched[i])
            seen[key.ty * grid.tilesX + key.tx] = false
        }
        touchedCount = 0
    }
}
