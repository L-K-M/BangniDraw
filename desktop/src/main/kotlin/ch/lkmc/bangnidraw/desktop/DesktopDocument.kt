package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.PixelOp
import ch.lkmc.bangnidraw.engine.core.SandwichPolicy
import ch.lkmc.bangnidraw.engine.core.TileKey

/**
 * One undoable edit of the desktop document.
 *
 * Android journals an inverse-describing [HistoryEntry] plus tile sidecars on
 * disk, and replays it through `LayerHistory`. The desktop document lives
 * entirely in memory and [LayerStack] is an immutable value, so a step can
 * simply hold **both** stacks: undo is "publish [stackBefore]", redo is
 * "publish [stackAfter]". The two `Layer` lists share their elements, so the
 * only real cost is the pixel payload.
 *
 * That payload is the tiles whose *contents* differ across the edit, per
 * layer, keyed identically on both sides so each direction is the other's
 * exact inverse. A `null` value means "no tile there", which is what
 * [PixelOp.Restore] already encodes.
 */
internal class DesktopUndoStep(
    val stackBefore: LayerStack,
    val stackAfter: LayerStack,
    val pixelsBefore: Map<LayerId, Map<TileKey, ByteArray?>>,
    val pixelsAfter: Map<LayerId, Map<TileKey, ByteArray?>>,
    /** The paper colour either side of the edit; `null` when it did not move. */
    val paperBefore: Int? = null,
    val paperAfter: Int? = null,
) {
    val bytes: Long = pixelsBefore.pixelBytes() + pixelsAfter.pixelBytes()

    fun stackFor(direction: HistoryDirection): LayerStack = when (direction) {
        HistoryDirection.Undo -> stackBefore
        HistoryDirection.Redo -> stackAfter
    }

    fun pixelsFor(direction: HistoryDirection): Map<LayerId, Map<TileKey, ByteArray?>> =
        when (direction) {
            HistoryDirection.Undo -> pixelsBefore
            HistoryDirection.Redo -> pixelsAfter
        }

    fun paperFor(direction: HistoryDirection): Int? = when (direction) {
        HistoryDirection.Undo -> paperBefore
        HistoryDirection.Redo -> paperAfter
    }

    private fun Map<LayerId, Map<TileKey, ByteArray?>>.pixelBytes(): Long =
        values.sumOf { tiles -> tiles.values.sumOf { it?.size?.toLong() ?: 0L } }
}

/** How a step's stored pixels become renderer work. */
internal object DesktopUndoOps {

    /**
     * The pixel work that turns the stack [from] into the stack [to] with
     * [pixels] as the target contents.
     *
     * A layer that leaves the stack takes its textures with it, and one that
     * (re)joins gets its tiles uploaded. Both are ordinary [PixelOp]s, so the
     * renderer's own all-or-nothing prepare/commit applies: a step that cannot
     * be prepared leaves the document exactly as it was.
     */
    fun ops(
        from: LayerStack,
        to: LayerStack,
        pixels: Map<LayerId, Map<TileKey, ByteArray?>>,
    ): List<PixelOp> {
        val live = to.layers.mapTo(HashSet()) { it.id }
        val ops = ArrayList<PixelOp>()
        for (layer in from.layers) {
            if (layer.id !in live) ops += PixelOp.Delete(layer.id)
        }
        for ((layerId, tiles) in pixels) {
            if (layerId in live && tiles.isNotEmpty()) ops += PixelOp.Restore(layerId, tiles)
        }
        return ops
    }
}

/**
 * Which layers an edit can have moved pixels on, and which cache halves it
 * stales. Both are derived from the [PixelOp] and the entry rather than passed
 * in by every call site, so a new panel action cannot forget one of them.
 */
internal object DesktopStackEdits {

    /**
     * Every layer whose tiles may differ across the edit — the set the
     * journal snapshots before and after. It is deliberately a superset:
     * capturing a tile that did not change costs bytes, missing one that did
     * makes undo lossy.
     */
    fun touchedLayers(before: LayerStack, after: LayerStack, pixels: PixelOp?): Set<LayerId> {
        val touched = LinkedHashSet<LayerId>()
        when (pixels) {
            null -> Unit
            is PixelOp.Copy -> touched += pixels.dst
            is PixelOp.Merge -> {
                touched += pixels.top
                touched += pixels.bottom
            }
            is PixelOp.Clear -> touched += pixels.layer
            is PixelOp.Delete -> touched += pixels.layer
            is PixelOp.Restore -> touched += pixels.layer
            // A flatten consumes the whole stack, hidden layers included.
            is PixelOp.Flatten -> {
                before.layers.forEach { touched += it.id }
                touched += pixels.result
            }
        }
        // Anything that appears or disappears, whether or not the op names it.
        val beforeIds = before.layers.mapTo(HashSet()) { it.id }
        val afterIds = after.layers.mapTo(HashSet()) { it.id }
        before.layers.forEach { if (it.id !in afterIds) touched += it.id }
        after.layers.forEach { if (it.id !in beforeIds) touched += it.id }
        return touched
    }

    /** Every tile key either side of the edit lists for [layer]. */
    fun keysFor(before: LayerStack, after: LayerStack, layer: LayerId): Set<TileKey> {
        val keys = LinkedHashSet<TileKey>()
        before.layers.firstOrNull { it.id == layer }?.let { keys += it.tiles }
        after.layers.firstOrNull { it.id == layer }?.let { keys += it.tiles }
        return keys
    }

    /**
     * The sandwich half (or halves) the edit stales. `05-layers.md` §8 is the
     * normative table and [SandwichPolicy] already implements it; this only
     * names which row an entry belongs to. Indices are the ones *before* the
     * operation, as [SandwichPolicy.Op] documents.
     */
    fun invalidation(entry: HistoryEntry, before: LayerStack): SandwichPolicy.Op = when (entry) {
        is HistoryEntry.LayerAdd -> SandwichPolicy.Op.Add
        is HistoryEntry.LayerDuplicate ->
            SandwichPolicy.Op.Duplicate(before.indexOf(entry.sourceId).coerceAtLeast(0))
        is HistoryEntry.LayerDelete -> SandwichPolicy.Op.Delete(entry.index)
        is HistoryEntry.LayerReorder -> SandwichPolicy.Op.Move(entry.fromIndex, entry.toIndex)
        is HistoryEntry.LayerMerge -> SandwichPolicy.Op.MergeDown(entry.upperIndex)
        is HistoryEntry.LayerClear ->
            SandwichPolicy.Op.Clear(before.indexOf(entry.layerId).coerceAtLeast(0))
        is HistoryEntry.Flatten -> SandwichPolicy.Op.Flatten
        is HistoryEntry.LayerProps ->
            if (compositesFrom(entry)) {
                SandwichPolicy.Op.SetCompositingProperty(
                    before.indexOf(entry.layerId).coerceAtLeast(0),
                )
            } else {
                SandwichPolicy.Op.SetInertProperty
            }
        // Pixel-only entries never reach this path on desktop, but a wrong
        // answer here is invisible (§8's whole point), so the fallback is the
        // conservative one rather than the cheap one.
        else -> SandwichPolicy.Op.UndoRedo
    }

    /**
     * Whether a props edit changes what a composite reads. Alpha lock, lock
     * and rename do not; visibility, opacity and blend mode do.
     */
    private fun compositesFrom(entry: HistoryEntry.LayerProps): Boolean =
        entry.before.visible != entry.after.visible ||
            entry.before.opacity != entry.after.opacity ||
            entry.before.blend != entry.after.blend
}

/** The tile bookkeeping a committed stroke leaves on the model. */
internal object DesktopStrokeTiles {

    /**
     * [stack] with [keys] added to [layer]'s tile set.
     *
     * The renderer prepares `Copy`, `Merge` and `Flatten` against the exact
     * tile sets the model reports (`prepareCopy` compares them with `!=`), so
     * a stroke that forgets to widen them makes the *next* structural edit
     * refuse rather than failing where the mistake was.
     */
    fun withCommitted(stack: LayerStack, layer: LayerId, keys: Collection<TileKey>): LayerStack {
        val index = stack.indexOf(layer)
        if (index < 0 || keys.isEmpty()) return stack
        val current = stack.layers[index]
        val tiles = current.tiles + keys
        if (tiles.size == current.tiles.size) return stack
        return stack.copy(
            layers = stack.layers.toMutableList().apply {
                set(index, Layer(current.props, tiles))
            },
        )
    }
}
