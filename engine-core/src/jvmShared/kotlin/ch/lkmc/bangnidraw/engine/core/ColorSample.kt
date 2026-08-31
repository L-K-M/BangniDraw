package ch.lkmc.bangnidraw.engine.core

/** Converts averaged premultiplied readback bytes into an opaque brush color. */
object ColorSample {

    fun opaqueArgb(
        redTotal: Long,
        greenTotal: Long,
        blueTotal: Long,
        alphaTotal: Long,
    ): Int? {
        if (alphaTotal <= 0L) return null

        val red = unpremultiply(redTotal, alphaTotal)
        val green = unpremultiply(greenTotal, alphaTotal)
        val blue = unpremultiply(blueTotal, alphaTotal)
        return Composite.argb(CHANNEL_MAX, red, green, blue)
    }

    private fun unpremultiply(channelTotal: Long, alphaTotal: Long): Int =
        ((channelTotal * CHANNEL_MAX + alphaTotal / 2L) / alphaTotal)
            .toInt()
            .coerceIn(0, CHANNEL_MAX)

    private const val CHANNEL_MAX = 255
}
