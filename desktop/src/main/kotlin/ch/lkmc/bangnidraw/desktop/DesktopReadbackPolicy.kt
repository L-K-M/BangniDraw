package ch.lkmc.bangnidraw.desktop

internal enum class ReadbackDrain {
    Complete,
    TimedOut,
}

internal enum class ReadbackDelivery {
    Complete,
    Incomplete,
}

/** Retries bounded GPU fence waits without accepting a stale CPU mirror. */
internal object DesktopReadbackPolicy {
    const val MAX_ATTEMPTS = 3

    fun drain(finish: () -> Int): ReadbackDrain {
        repeat(MAX_ATTEMPTS) {
            if (finish() == 0) return ReadbackDrain.Complete
        }

        return ReadbackDrain.TimedOut
    }

    fun delivery(
        keys: List<ch.lkmc.bangnidraw.engine.core.TileKey>,
        expectedRevision: Int,
        revisionOf: (ch.lkmc.bangnidraw.engine.core.TileKey) -> Int?,
    ): ReadbackDelivery {
        val complete = keys.all { key -> revisionOf(key) == expectedRevision }
        return if (complete) ReadbackDelivery.Complete else ReadbackDelivery.Incomplete
    }
}
