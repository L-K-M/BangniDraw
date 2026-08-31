package ch.lkmc.bangnidraw.engine.core

/** Pure allocation policy for a reusable RGBA8 offscreen texture. */
data class OffscreenCapacity(
    val width: Int,
    val height: Int,
) {
    init {
        require(width >= 0 && height >= 0) {
            "capacity dimensions must be non-negative, were ${width}x$height"
        }
    }

    /** Keeps the high-water size while either requested dimension shrinks. */
    fun growTo(width: Int, height: Int): OffscreenCapacity {
        require(width > 0 && height > 0) {
            "requested dimensions must be positive, were ${width}x$height"
        }
        if (width <= this.width && height <= this.height) return this

        return OffscreenCapacity(
            width = maxOf(this.width, width),
            height = maxOf(this.height, height),
        )
    }

    /** Actual immutable RGBA8 storage, not the current logical viewport. */
    val rgba8Bytes: Long get() = width.toLong() * height * RGBA8_BYTES_PER_PIXEL

    companion object {
        val EMPTY = OffscreenCapacity(0, 0)

        private const val RGBA8_BYTES_PER_PIXEL = 4L
    }
}
