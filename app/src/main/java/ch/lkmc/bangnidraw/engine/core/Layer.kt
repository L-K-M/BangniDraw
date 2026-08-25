package ch.lkmc.bangnidraw.engine.core

import kotlinx.serialization.Serializable

/**
 * A layer's identity. The value is a UUID string and also names
 * `layers/<layerId>/` on disk; ids are never reused, never derived from an
 * index (`docs/plan/05-layers.md` §1).
 */
@JvmInline
value class LayerId(val value: String) {
    init {
        // This value becomes a directory name, so it must be one path segment
        // and nothing else. Enforced here rather than only at the store because
        // the type's own KDoc promises it, and an id arrives from `project.json`
        // — a file that can be hand-edited or shipped between devices.
        //
        // Deliberately a path-segment floor, not a UUID regex: the security
        // property is "cannot escape the project folder", and ids in tests and
        // future fixtures need not be UUIDs to satisfy it.
        require(
            value.isNotEmpty() && value != "." && value != ".." &&
                value.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }
        ) {
            "layer id must be a single safe path segment, was \"$value\""
        }
    }
}

/**
 * Everything about a layer except its pixels — the part that goes into
 * `project.json` and into pixel-free history entries.
 *
 * [opacity] is *validated* to 0..1 on construction — construction refuses a
 * value outside it (NaN and both infinities included) rather than quietly
 * repairing it, so drift can never be stored. [withOpacity] is the clamping
 * setter the UI uses, and `LayerRecord.toProps` clamps at the deserialization
 * boundary, where a corrupt file must degrade rather than throw
 * (`docs/plan/06-document-and-persistence.md` §4).
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
        // `in 0f..1f` is false for NaN and for both infinities, so this one
        // check covers every value that would otherwise reach a GL uniform or
        // serialize as an invalid JSON float.
        require(opacity in 0f..1f) { "opacity of ${id.value} must be in 0f..1f, was $opacity" }
    }

    /** A copy with [opacity] clamped into range — the only way the UI should set it. */
    fun withOpacity(value: Float): LayerProps = copy(opacity = sanitizeOpacity(value))

    fun toRecord(): LayerRecord =
        LayerRecord(id.value, name, visible, opacity, blendMode.name, alphaLock, locked)

    companion object {
        /**
         * Every opacity that reaches [LayerProps] from outside goes through
         * here, so the clamping setter and the deserialization boundary cannot
         * drift apart.
         *
         * `coerceIn` alone is not enough: both its comparisons are false for
         * `NaN`, so it returns `NaN` unchanged and construction would then
         * refuse it — turning one corrupt field into a failed document open,
         * which `docs/plan/06-document-and-persistence.md` §4 forbids. A
         * corrupt opacity degrades to fully visible rather than to invisible,
         * because a layer that vanished would read as lost work.
         */
        fun sanitizeOpacity(value: Float): Float =
            if (value.isNaN()) 1f else value.coerceIn(0f, 1f)
    }
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
        opacity = LayerProps.sanitizeOpacity(opacity),
        blendMode = BlendMode.fromNameOrNormal(blend),
        alphaLock = alphaLock,
        locked = locked,
    )
}
