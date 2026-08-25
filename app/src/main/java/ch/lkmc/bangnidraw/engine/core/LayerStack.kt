package ch.lkmc.bangnidraw.engine.core

/** Source of fresh layer ids. Injected so tests get reproducible sequences. */
fun interface IdSource {
    fun newId(): LayerId
}

/**
 * The pixel work an operation leaves for the GL thread
 * (`docs/plan/05-layers.md` §3.3). The model is updated first; the pixels
 * follow within the same frame.
 */
sealed interface PixelOp {
    data class Copy(val src: LayerId, val dst: LayerId, val keys: Set<TileKey>) : PixelOp
    /**
     * [keys] are every tile the merge rewrites. [bottomProps] is the lower
     * layer's state *before* the merge reset it: the model already holds the
     * reset props by the time the GL thread runs this, so the op has to carry
     * what the pixels must be composited with.
     */
    data class Merge(
        val top: LayerId,
        val topProps: LayerProps,
        val bottom: LayerId,
        val bottomProps: LayerProps,
        val keys: Set<TileKey>,
    ) : PixelOp

    data class Clear(val layer: LayerId) : PixelOp
    data class Delete(val layer: LayerId) : PixelOp

    /**
     * Composite [order] — the **visible** layers, bottom to top, over
     * transparent (`docs/plan/05-layers.md` §4.4) — into a fresh layer
     * [result].
     *
     * The op is total over the layer set even though [order] is not: after a
     * flatten the stack is exactly `[result]`, so the renderer frees **every**
     * layer texture that is not [result], including the hidden layers this op
     * never names. It is derivable from the op alone and needs no
     * reconciliation against the model — but it has to be said, because
     * "free what `order` lists" leaks a hidden layer's tiles on every flatten
     * and undo would not notice (the entry snapshots all layers either way).
     */
    data class Flatten(val order: List<LayerProps>, val result: LayerId) : PixelOp

    /** Undo/redo upload; a `null` payload means "delete that tile". */
    data class Restore(val layer: LayerId, val tiles: Map<TileKey, ByteArray?>) : PixelOp
}

/** The new stack, the pixel work it implies, and the entry that inverts it. */
data class StackEdit(val stack: LayerStack, val pixels: PixelOp?, val entry: HistoryEntry)

/** Why an operation did nothing. Values, never exceptions: each one is a UI hint. */
enum class Refusal { LAST_LAYER, AT_CAP, LOCKED, HIDDEN_PARTNER, NO_LAYER_BELOW, NOOP }

sealed interface StackResult {
    data class Ok(val edit: StackEdit) : StackResult
    data class Refused(val reason: Refusal) : StackResult
}

/**
 * The ordered stack of layers, bottom (index 0) to top, with pure operations
 * that each return a new stack plus the pixel work and the history entry
 * (`docs/plan/05-layers.md` §3). Nothing here mutates; nothing here knows
 * about the GPU, the disk or the screen.
 *
 * **Lock protects pixels and existence, not arrangement** (`docs/plan/05-layers.md`
 * §1): `delete`, `clear`, `mergeDown` and `flatten` refuse a locked layer,
 * while rename, opacity, visibility, blend mode, alpha lock and `move` stay
 * allowed on one. That asymmetry is deliberate, not an oversight — the panel
 * in step 6 must not assume the model refuses every edit to a locked layer.
 *
 * [nextName] only ever grows along a chain of operations, so a default name is
 * never reused while a document stays open. Keeping that true across undo and
 * across a reopen is *not* something this type can do on its own: no
 * `HistoryEntry` carries the counter and `ProjectFile`
 * (`docs/plan/06-document-and-persistence.md` §3) has no field for it, so the
 * journal must preserve it when it lands in roadmap step 3 — see AGENTS.md,
 * "Deviations discovered while building".
 */
data class LayerStack(
    val layers: List<Layer>,
    val activeIndex: Int,
    val nextName: Int,
) {
    init {
        require(layers.isNotEmpty()) { "a document always has at least one layer" }
        require(activeIndex in layers.indices) {
            "activeIndex $activeIndex is outside 0..${layers.size - 1}"
        }
        require(layers.distinctBy { it.id.value }.size == layers.size) {
            "layer ids must be unique within a stack"
        }
    }

    val active: Layer get() = layers[activeIndex]
    val size: Int get() = layers.size

    fun indexOf(id: LayerId): Int = layers.indexOfFirst { it.id == id }

    /** Selection is a view concern, never an edit: it is not journaled. */
    fun select(index: Int): LayerStack =
        if (index == activeIndex || index !in layers.indices) this else copy(activeIndex = index)

    // ---------------------------------------------------------------- structure

    /** A new empty layer directly above the active one; it becomes active. */
    fun add(ids: IdSource, maxLayers: Int): StackResult {
        if (layers.size >= maxLayers) return StackResult.Refused(Refusal.AT_CAP)
        val index = activeIndex + 1
        val props = LayerProps(id = ids.newId(), name = defaultName(nextName))
        val next = copy(
            layers = layers.toMutableList().apply { add(index, Layer(props)) },
            activeIndex = index,
            nextName = nextName + 1,
        )
        return ok(
            next,
            null,
            HistoryEntry.LayerAdd(
                activeBefore = active.id,
                activeAfter = props.id,
                layer = props.toRecord(),
                index = index,
            ),
        )
    }

    /** A copy of [index] directly above it; the copy becomes active. */
    fun duplicate(index: Int, ids: IdSource, maxLayers: Int): StackResult {
        val source = layers.getOrNull(index) ?: return StackResult.Refused(Refusal.NOOP)
        if (layers.size >= maxLayers) return StackResult.Refused(Refusal.AT_CAP)
        val props = source.props.copy(
            id = ids.newId(),
            name = duplicateName(source.props.name),
            locked = false,
        )
        val at = index + 1
        val next = copy(
            layers = layers.toMutableList().apply { add(at, Layer(props, source.tiles)) },
            activeIndex = at,
        )
        return ok(
            next,
            PixelOp.Copy(source.id, props.id, source.tiles),
            HistoryEntry.LayerDuplicate(
                activeBefore = active.id,
                activeAfter = props.id,
                sourceId = source.id,
                copy = props.toRecord(),
                index = at,
            ),
        )
    }

    /**
     * Removes [index]. The layer *below* becomes active when the active layer
     * itself was deleted; otherwise the previously active layer stays active
     * even though its index shifts.
     */
    fun delete(index: Int): StackResult {
        val victim = layers.getOrNull(index) ?: return StackResult.Refused(Refusal.NOOP)
        if (layers.size <= 1) return StackResult.Refused(Refusal.LAST_LAYER)
        if (victim.props.locked) return StackResult.Refused(Refusal.LOCKED)
        val remaining = layers.toMutableList().apply { removeAt(index) }
        val activeAfterIndex =
            if (index == activeIndex) maxOf(index - 1, 0)
            else remaining.indexOfFirst { it.id == active.id }
        val next = copy(layers = remaining, activeIndex = activeAfterIndex)
        return ok(
            next,
            PixelOp.Delete(victim.id),
            HistoryEntry.LayerDelete(
                activeBefore = active.id,
                activeAfter = next.active.id,
                layer = victim.props.toRecord(),
                index = index,
                tiles = victim.tiles.sortedBy { it.packed },
            ),
        )
    }

    /** Moves [from] so that it ends up at index [to]; the moved layer becomes active. */
    fun move(from: Int, to: Int): StackResult {
        if (from !in layers.indices || to !in layers.indices) return StackResult.Refused(Refusal.NOOP)
        if (from == to) return StackResult.Refused(Refusal.NOOP)
        val moved = layers[from]
        val reordered = layers.toMutableList().apply {
            removeAt(from)
            add(to, moved)
        }
        val next = copy(layers = reordered, activeIndex = to)
        return ok(
            next,
            null,
            HistoryEntry.LayerReorder(
                activeBefore = active.id,
                activeAfter = moved.id,
                layerId = moved.id,
                fromIndex = from,
                toIndex = to,
            ),
        )
    }

    /**
     * Merges [index] into the layer below it. The result keeps the lower
     * layer's id and name and is reset to Normal at 100 % — the appearance
     * rules and the confirmation the panel owes the user are
     * `docs/plan/05-layers.md` §4.1.
     */
    fun mergeDown(index: Int): StackResult {
        val top = layers.getOrNull(index) ?: return StackResult.Refused(Refusal.NOOP)
        if (index == 0) return StackResult.Refused(Refusal.NO_LAYER_BELOW)
        val bottom = layers[index - 1]
        if (top.props.locked || bottom.props.locked) return StackResult.Refused(Refusal.LOCKED)
        if (!top.props.visible || !bottom.props.visible) {
            return StackResult.Refused(Refusal.HIDDEN_PARTNER)
        }
        val mergedProps = bottom.props.copy(
            opacity = 1f,
            blendMode = BlendMode.NORMAL,
            alphaLock = false,
            visible = true,
            locked = false,
        )
        // Which of the bottom layer's tiles the merge has to rewrite.
        //
        // The result is Normal at 100 %, so any tile whose appearance depended
        // on the bottom layer's opacity must be re-composited — including the
        // tiles the top layer never covers, which would otherwise jump from
        // 50 % to fully opaque. That is what makes 05-layers.md §4.1's promise
        // ("a normal bottom at *any* opacity merges exactly") true.
        //
        // Blend mode does not force the rewrite: a bottom-only tile is
        // composited over transparent, and over transparent every mode reduces
        // to source-over (pinned by CompositeTest), so its stored pixels are
        // already right. Opacity alone decides, and the common bottom-at-100 %
        // merge still rewrites only the shared tiles, as 05 §4.1 and 06 §5.2
        // describe.
        //
        // That argument is about tile *contents* and does not make the reset
        // itself appearance-preserving: `mergedProps` drops the bottom layer's
        // blend mode, so anything painted *below* it now sees the merged layer
        // composited source-over instead of multiplied/screened into. No amount
        // of rewriting fixes that — a blend can only be baked against a known
        // backdrop — which is exactly why 05 §4.1 scopes its "merges exactly"
        // promise to a *normal* bottom and has the panel confirm whenever
        // either partner's mode is not NORMAL.
        val bakesWholeBottom = bottom.props.opacity != 1f
        val rewritten = if (bakesWholeBottom) bottom.tiles + top.tiles else top.tiles
        val merged = Layer(mergedProps, bottom.tiles + top.tiles)
        val next = copy(
            layers = layers.toMutableList().apply {
                removeAt(index)
                set(index - 1, merged)
            },
            activeIndex = index - 1,
        )
        // Only the bottom tiles the merge actually overwrites — unlike
        // `upperTiles` two lines down, this is NOT the lower layer's full tile
        // set, and every other tile list on these entries is complete. Undo
        // must rebuild the lower set as (merged.tiles − upperTiles) ∪
        // lowerTiles; assigning lowerTiles directly would drop every
        // bottom-only tile from the model while its pixels survive in storage.
        val lowerTiles = if (bakesWholeBottom) bottom.tiles else bottom.tiles.intersect(top.tiles)
        return ok(
            next,
            PixelOp.Merge(top.id, top.props, bottom.id, bottom.props, rewritten),
            HistoryEntry.LayerMerge(
                activeBefore = active.id,
                activeAfter = merged.id,
                upper = top.props.toRecord(),
                upperIndex = index,
                upperTiles = top.tiles.sortedBy { it.packed },
                lower = bottom.props.toRecord(),
                lowerTiles = lowerTiles.sortedBy { it.packed },
            ),
        )
    }

    /** Composites every **visible** layer into one new layer; hidden layers are dropped. */
    fun flatten(ids: IdSource): StackResult {
        if (layers.size <= 1) return StackResult.Refused(Refusal.NOOP)
        if (layers.any { it.props.locked }) return StackResult.Refused(Refusal.LOCKED)
        val visible = layers.filter { it.props.visible }
        // Flattening with everything hidden would destroy every layer for a
        // guaranteed-blank result, and burn a history entry holding all of
        // their tiles to do it. That is a destructive no-op, which is exactly
        // what Refusal exists to turn into a hint.
        if (visible.isEmpty()) return StackResult.Refused(Refusal.NOOP)
        val props = LayerProps(id = ids.newId(), name = FLATTENED_NAME)
        val tiles = visible.flatMapTo(LinkedHashSet()) { it.tiles }
        val next = copy(layers = listOf(Layer(props, tiles)), activeIndex = 0)
        return ok(
            next,
            // Visible only: §4.4 says the flattened result is the composite
            // of the visible layers, so that is what `order` means. The
            // hidden layers are still dropped by this edit, and freeing their
            // textures is part of PixelOp.Flatten's own contract — see its
            // KDoc, which is where the renderer will read it.
            PixelOp.Flatten(visible.map { it.props }, props.id),
            HistoryEntry.Flatten(
                activeBefore = active.id,
                activeAfter = props.id,
                layers = layers.map { it.props.toRecord() },
                tilesPerLayer = layers.associate { l -> l.id to l.tiles.sortedBy { it.packed } },
                result = props.toRecord(),
            ),
        )
    }

    /** Frees a layer's pixels but keeps the layer, its props and the selection. */
    fun clear(index: Int): StackResult {
        val layer = layers.getOrNull(index) ?: return StackResult.Refused(Refusal.NOOP)
        if (layer.props.locked) return StackResult.Refused(Refusal.LOCKED)
        if (layer.tiles.isEmpty()) return StackResult.Refused(Refusal.NOOP)
        val next = copy(layers = layers.toMutableList().apply { set(index, layer.copy(tiles = emptySet())) })
        return ok(
            next,
            PixelOp.Clear(layer.id),
            HistoryEntry.LayerClear(
                activeBefore = active.id,
                activeAfter = active.id,
                layerId = layer.id,
                tiles = layer.tiles.sortedBy { it.packed },
            ),
        )
    }

    // --------------------------------------------------------------- properties

    fun rename(index: Int, name: String): StackResult = setProps(index) { it.copy(name = name) }

    fun setOpacity(index: Int, opacity: Float): StackResult =
        setProps(index) { it.withOpacity(opacity) }

    fun setVisible(index: Int, visible: Boolean): StackResult =
        setProps(index) { it.copy(visible = visible) }

    fun setBlendMode(index: Int, mode: BlendMode): StackResult =
        setProps(index) { it.copy(blendMode = mode) }

    fun setAlphaLock(index: Int, alphaLock: Boolean): StackResult =
        setProps(index) { it.copy(alphaLock = alphaLock) }

    fun setLocked(index: Int, locked: Boolean): StackResult =
        setProps(index) { it.copy(locked = locked) }

    private inline fun setProps(index: Int, edit: (LayerProps) -> LayerProps): StackResult {
        val layer = layers.getOrNull(index) ?: return StackResult.Refused(Refusal.NOOP)
        val after = edit(layer.props)
        if (after == layer.props) return StackResult.Refused(Refusal.NOOP)
        val next = copy(layers = layers.toMutableList().apply { set(index, layer.copy(props = after)) })
        return ok(
            next,
            null,
            HistoryEntry.LayerProps(
                activeBefore = active.id,
                activeAfter = active.id,
                layerId = layer.id,
                before = layer.props.toRecord(),
                after = after.toRecord(),
            ),
        )
    }

    private fun ok(stack: LayerStack, pixels: PixelOp?, entry: HistoryEntry): StackResult =
        StackResult.Ok(StackEdit(stack, pixels, entry))

    companion object {
        /**
         * Default and generated names are stored as resource *keys* plus their
         * argument, never as English text (`docs/plan/01-product.md` §8).
         *
         * The protocol is a **closed grammar**, not "resolve any `@string/`
         * token": a stored name is resolved only when the whole string is
         * [FLATTENED_NAME], or [DEFAULT_NAME_KEY] followed by one integer, or
         * some name followed by [COPY_SUFFIX_KEY] — where the prefix is itself
         * resolved by the same rule, so duplicating a duplicate stacks the
         * suffix and each one is stripped in turn. Everything else is shown
         * verbatim. A user-typed name therefore survives unless it *exactly*
         * matches one of the three forms: `"@string/app_name"` is not in the
         * grammar and displays as itself, while `"@string/layer_default 7"` is
         * indistinguishable from a generated name and does resolve as one —
         * which costs nothing, since it resolves to the text it already reads
         * as. The
         * resolver lands with the layer panel in roadmap step 6 and must
         * implement exactly this grammar.
         */
        const val DEFAULT_NAME_KEY = "@string/layer_default"
        const val COPY_SUFFIX_KEY = "@string/layer_copy_suffix"
        const val FLATTENED_NAME = "@string/layer_flattened"

        fun defaultName(n: Int): String = "$DEFAULT_NAME_KEY $n"

        fun duplicateName(name: String): String = "$name $COPY_SUFFIX_KEY"

        /** The stack a new document starts with: one empty layer named "Layer 1". */
        fun initial(ids: IdSource): LayerStack =
            LayerStack(
                layers = listOf(Layer(LayerProps(id = ids.newId(), name = defaultName(1)))),
                activeIndex = 0,
                nextName = 2,
            )
    }
}
