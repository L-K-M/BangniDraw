package ch.lkmc.bangnidraw.engine.core

/**
 * Which half of the sandwich cache an edit invalidates
 * (`docs/plan/05-layers.md` §8, the normative table; `03-canvas-engine.md` §4).
 *
 * Pure, because getting this wrong is invisible: a half left valid when it
 * should be stale shows a *correct-looking* canvas built from the wrong
 * layers, and only a screenshot comparison would catch it. §15 asks for a
 * pure-JVM twin for exactly this kind of decision, and here it means the
 * normative table can be pinned row by row without a GL context.
 *
 * A stale flag is per **side, not per tile**: the per-tile rebuild is lazy and
 * viewport-bounded, so staleness costs at most one extra composite of the
 * visible tiles — roughly one frame — on user actions that already expect a
 * beat.
 */
object SandwichPolicy {

    /** Which halves an operation invalidates. */
    data class Stale(val below: Boolean, val above: Boolean) {
        val any: Boolean get() = below || above

        companion object {
            val NEITHER = Stale(below = false, above = false)
            val BOTH = Stale(below = true, above = true)
            val BELOW = Stale(below = true, above = false)
            val ABOVE = Stale(below = false, above = true)
        }
    }

    /**
     * An edit, as far as the cache is concerned. Indices are **before** the
     * operation, which is what makes `a` meaningful for the ones that change
     * which layer is active.
     */
    sealed interface Op {
        /** The active layer changed to [index]. */
        data class Select(val index: Int) : Op

        /** Visibility, opacity or blend mode of [index] — properties that composite. */
        data class SetCompositingProperty(val index: Int) : Op

        /** Alpha-lock, lock, rename — properties no composite reads. */
        data object SetInertProperty : Op

        data class Move(val from: Int, val to: Int) : Op
        data object Add : Op
        data class Duplicate(val index: Int) : Op
        data class Delete(val index: Int) : Op
        data class MergeDown(val index: Int) : Op
        data class Clear(val index: Int) : Op
        data object Flatten : Op
        data object PaperColor : Op
        data object TracingReference : Op
        data object UndoRedo : Op

        /** A stroke merged into the active layer. */
        data object StrokeCommit : Op

        /** Pixels of [index] changed without the stack changing (a tile upload, a fill). */
        data class PixelEdit(val index: Int) : Op
    }

    /**
     * The table of `05-layers.md` §8, for an operation applied while
     * [activeIndex] is active.
     *
     * The governing principle behind most rows: **any change of which layer is
     * active stales both sides**, because both halves' membership is defined
     * relative to it. `add` and `duplicate(active)` are the special cases —
     * §3.1 makes the new layer active, but it is empty and sits directly on
     * the old active, so `above` is provably unchanged and only `below` pays.
     * That matters because `add` is the most frequent structural operation.
     */
    fun stale(op: Op, activeIndex: Int): Stale = when (op) {
        // Membership of both sides changed.
        is Op.Select -> if (op.index == activeIndex) Stale.NEITHER else Stale.BOTH

        // The active layer is composited live BETWEEN the two halves, so its
        // own opacity and mode are uniforms of that pass, not cached pixels.
        is Op.SetCompositingProperty -> sideContaining(op.index, activeIndex)

        Op.SetInertProperty -> Stale.NEITHER

        // Both halves, for any move that moves anything.
        //
        // `05-layers.md` §8 used to say a move of the ACTIVE layer stales only
        // "the side it crossed into", and this transcribed it. That rationale
        // tracks the moved layer — which is in neither half, before or after —
        // and forgets the layers it crosses, every one of which leaves one half
        // and joins the other. In `[L0, L1(active), L2]`, `move(1, 0)` puts L0
        // into `above`: `below` correctly rebuilds to empty, `above` keeps its
        // stale composite of L2 alone, and **L0 is in neither half and vanishes
        // from the canvas** until some unrelated edit stales `above`. Even an
        // adjacent move swaps one layer across. The table has been corrected;
        // this comment stays because the wrong rule is the intuitive one.
        //
        // A move of a non-active layer stales both anyway: §3.1 makes the moved
        // layer active, which redefines both memberships.
        is Op.Move ->
            if (op.from == activeIndex && op.to == activeIndex) Stale.NEITHER
            else Stale.BOTH

        Op.Add -> Stale.BELOW

        is Op.Duplicate ->
            if (op.index == activeIndex) Stale.BELOW else Stale.BOTH

        is Op.Delete ->
            if (op.index == activeIndex) Stale.BOTH else sideContaining(op.index, activeIndex)

        is Op.MergeDown -> when {
            // The bottom absorbed the active layer; the merged layer is the
            // new active and `above` is untouched.
            op.index == activeIndex -> Stale.BELOW
            // Merging the layer above INTO the active one: the active layer's
            // pixels change (a live pass reads them) and the above set shrinks.
            op.index - 1 == activeIndex -> Stale.ABOVE
            else -> Stale.BOTH
        }

        is Op.Clear ->
            if (op.index == activeIndex) Stale.NEITHER else sideContaining(op.index, activeIndex)

        Op.Flatten -> Stale.BOTH

        // The paper is baked into `below` (03 §4): a non-normal bottom layer
        // over a transparent backdrop would degenerate to source-over, so a
        // paper-less cache drawn over the paper diverges from the direct
        // composite and from flatten.
        Op.PaperColor -> Stale.BELOW

        // The reference is composited above paper and below every paint layer.
        Op.TracingReference -> Stale.BELOW

        // An undo entry can be anything. Two stale flags cost one rebuild of
        // the visible tiles, and undo is not a per-frame event.
        Op.UndoRedo -> Stale.BOTH

        // The stroke buffer merges into the active layer only, which the live
        // pass reads directly.
        Op.StrokeCommit -> Stale.NEITHER

        is Op.PixelEdit ->
            if (op.index == activeIndex) Stale.NEITHER else sideContaining(op.index, activeIndex)
    }

    private fun sideContaining(index: Int, activeIndex: Int): Stale = when {
        index < activeIndex -> Stale.BELOW
        index > activeIndex -> Stale.ABOVE
        else -> Stale.NEITHER
    }

    /**
     * Whether the layers above the active one can be cached at all
     * (`03-canvas-engine.md` §4).
     *
     * `Above` is composited over **transparent**, and the result is drawn over
     * the active layer. Source-over is associative, so
     * `above OVER (active BLEND below)` is exact — but Multiply, Screen and
     * the rest are not associative with respect to the backdrop. So a single
     * non-normal layer above the active one makes the cache wrong rather than
     * merely stale, and those layers are composited individually per frame
     * instead: K extra passes for K layers above.
     *
     * Invisible layers do not contribute and so do not disqualify the cache —
     * hiding the one Multiply layer above you restores the fast path, which is
     * the behaviour a user would expect if they thought about it at all.
     */
    fun aboveIsCacheable(layersAbove: List<Layer>): Boolean =
        layersAbove.none { it.props.visible && it.props.blendMode != BlendMode.NORMAL }

    /** Below owns its backdrop, so its ping-pong accepts every blend mode. */
    fun belowIsCacheable(layersBelow: List<Layer>): Boolean = layersBelow.all {
        when (it.props.blendMode) {
            BlendMode.NORMAL,
            BlendMode.MULTIPLY,
            BlendMode.SCREEN,
            BlendMode.OVERLAY,
            BlendMode.DARKEN,
            BlendMode.LIGHTEN,
            BlendMode.ADD,
            BlendMode.DIFFERENCE,
            -> true
        }
    }
}
