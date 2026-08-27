package ch.lkmc.bangnidraw.engine.core

/** Dirty, throttle, and in-flight state for panel thumbnail refreshes. */
internal class LayerThumbnailPolicy {
    data class Request(val layer: LayerId, val version: Long)

    private val versions = HashMap<LayerId, Long>()
    private val dirty = LinkedHashSet<LayerId>()
    private val inFlight = HashMap<LayerId, Request>()
    private val lastRequestMs = HashMap<LayerId, Long>()

    fun markDirty(layers: Collection<LayerId>) {
        for (layer in layers) {
            versions[layer] = (versions[layer] ?: 0L) + 1L
            dirty += layer
        }
    }

    fun due(nowMs: Long, panelOpen: Boolean, strokeInFlight: Boolean): List<Request> {
        if (!panelOpen || strokeInFlight) return emptyList()

        val due = ArrayList<Request>()
        for (layer in dirty) {
            if (layer in inFlight) continue
            val last = lastRequestMs[layer]
            if (last != null && nowMs - last < REFRESH_INTERVAL_MS) continue

            val request = Request(layer, versions.getValue(layer))
            inFlight[layer] = request
            lastRequestMs[layer] = nowMs
            due += request
        }
        return due
    }

    fun complete(request: Request): Boolean {
        if (inFlight[request.layer] != request) return false

        inFlight.remove(request.layer)
        if (versions[request.layer] != request.version) return false

        dirty.remove(request.layer)
        return true
    }

    fun fail(request: Request) {
        if (inFlight[request.layer] == request) inFlight.remove(request.layer)
    }

    fun retain(layers: Set<LayerId>) {
        dirty.retainAll(layers)
        inFlight.keys.retainAll(layers)
        versions.keys.retainAll(layers)
        lastRequestMs.keys.retainAll(layers)
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 500L

        fun changedLayers(before: LayerStack, after: LayerStack): List<LayerId> {
            val previous = before.layers.associateBy(Layer::id)

            return after.layers.filter { layer ->
                val old = previous[layer.id]
                old == null || old.tiles != layer.tiles || old.props.opacity != layer.props.opacity
            }.map(Layer::id)
        }
    }
}
