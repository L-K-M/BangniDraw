package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.TileKey

/** Thread-safe before-image capture for one direct-to-layer stroke. */
class RmwHistoryCapture {

    data class Snapshot(
        val layer: LayerId,
        val keys: List<TileKey>,
        val mirrorBefore: Map<Pair<LayerId, TileKey>, ByteArray>,
    )

    private data class Open(
        val layer: LayerId,
        val keys: LinkedHashSet<TileKey> = LinkedHashSet(),
        val mirrorBefore: LinkedHashMap<Pair<LayerId, TileKey>, ByteArray> = LinkedHashMap(),
    )

    private var open: Open? = null

    @Synchronized
    fun begin(layer: LayerId) {
        open = Open(layer)
    }

    @Synchronized
    fun touch(
        layer: LayerId,
        keys: List<Pair<LayerId, TileKey>>,
        mirrorBefore: Map<Pair<LayerId, TileKey>, ByteArray>,
    ): Boolean {
        val current = open ?: return false
        if (current.layer != layer || keys.any { it.first != layer }) return false

        for (key in keys) {
            if (!current.keys.add(key.second)) continue
            mirrorBefore[key]?.let { current.mirrorBefore[key] = it.copyOf() }
        }
        return true
    }

    @Synchronized
    fun finish(layer: LayerId): Snapshot? {
        val current = open ?: return null
        if (current.layer != layer) return null
        open = null
        return Snapshot(
            current.layer,
            current.keys.toList(),
            current.mirrorBefore.toMap(),
        )
    }

    /** Drops GPU-only before-images when their GL context no longer exists. */
    @Synchronized
    fun reset() {
        open = null
    }
}
