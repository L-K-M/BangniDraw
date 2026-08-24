package ch.lkmc.bangnidraw.engine.core

import kotlinx.serialization.Serializable

/**
 * A layer's identity. The value is a UUID string and also names
 * `layers/<layerId>/` on disk; ids are never reused, never derived from an
 * index (`docs/plan/05-layers.md` §1).
 */
@JvmInline
value class LayerId(val value: String)

/**
 * Everything about a layer except its pixels — the part that goes into
 * `project.json` and into pixel-free history entries.
 *
 * [opacity] is clamped to 0..1 on construction so no caller can store drift.
 */
data class LayerProps(
    val id: LayerId,
    val name: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val alphaLock: Boolean = false,
    val locked: Boolean = false,
) {
    init {
        require(!opacity.isNaN()) { "opacity of ${id.value} is NaN" }
    }

    /** A copy with [opacity] clamped into range — the only way the UI should set it. */
    fun withOpacity(value: Float): LayerProps = copy(opacity = value.coerceIn(0f, 1f))

    fun toRecord(): LayerRecord =
        LayerRecord(id.value, name, visible, opacity, blendMode.name, alphaLock, locked)
}

/**
 * A layer: its properties plus the sparse set of tile keys that exist for it.
 * No pixels — those live in `TilePool` (GPU) and `TileStore` (disk).
 */
data class Layer(val props: LayerProps, val tiles: Set<TileKey> = emptySet()) {
    val id: LayerId get() = props.id
}

/**
 * The serialised form of [LayerProps] (`docs/plan/06-document-and-persistence.md`
 * §3), used by `project.json` and by every history entry that stores a layer.
 *
 * [blend] is a string rather than the enum for the reason 06 §3 gives: a mode
 * added by a later version must degrade to `NORMAL` with a log line, not make
 * an older reader throw.
 */
@Serializable
data class LayerRecord(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val blend: String = BlendMode.NORMAL.name,
    val alphaLock: Boolean = false,
    val locked: Boolean = false,
) {
    fun toProps(): LayerProps = LayerProps(
        id = LayerId(id),
        name = name,
        visible = visible,
        opacity = opacity.coerceIn(0f, 1f),
        blendMode = BlendMode.fromNameOrNormal(blend),
        alphaLock = alphaLock,
        locked = locked,
    )
}
