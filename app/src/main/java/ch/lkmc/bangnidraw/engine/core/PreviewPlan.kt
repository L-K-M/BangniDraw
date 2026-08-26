package ch.lkmc.bangnidraw.engine.core

/**
 * How `preview.frag`'s tiles group into draw calls
 * (`docs/plan/03-canvas-engine.md` §7.5, §15's rule).
 *
 * §7.5: the keys drawn are the union of the active layer's, the stroke
 * buffer's and the tail's present keys within the dirty rect, and
 * `CompositePass` "batches them by the `(layerPage, strokePage, tailPage)`
 * triple (one draw per distinct triple; in practice one or two)". Which tiles
 * may share a draw is a decision, so §15 puts it here with tests rather than
 * inside the GL class that issues the calls.
 *
 * **An absent tile is the interesting case, and it is why this is not simply
 * three equality checks.** A key reaches the preview if *any* of the three
 * textures has it: a stroke on blank canvas has a stroke tile and no layer
 * tile, which is the ordinary case on a new document. An absent tile is never
 * sampled — its slice is [ABSENT] and `merge.glsl`'s `fetchTile` returns
 * transparent — so it can join a run on any page, and it must not pin the run
 * to a page of its own. Treating absence as just another page number would
 * split one draw into three for no reason; treating it as *matching* would be
 * wrong the moment a real page follows it. It does neither: it joins, and
 * leaves the run's page unset for a later tile to fix.
 */
object PreviewPlan {

    /** No tile here, so no page and no sampling — `SliceHandle.NONE`'s page. */
    const val ABSENT = -1

    /**
     * Whether a tile on [tilePage] can join a run currently bound to [runPage].
     *
     * Symmetric in the two absences: an absent tile joins anything, and any
     * tile joins a run that has not been pinned to a page yet.
     */
    fun joins(runPage: Int, tilePage: Int): Boolean =
        tilePage == ABSENT || runPage == ABSENT || tilePage == runPage

    /**
     * The page a run is bound to after admitting a tile on [tilePage].
     *
     * The first real page wins and holds; an absent tile leaves it alone. Only
     * meaningful when [joins] returned true — this does not re-check.
     */
    fun adopt(runPage: Int, tilePage: Int): Int =
        if (runPage == ABSENT) tilePage else runPage

    /**
     * The exclusive end of the run of tiles starting at [from], given each
     * tile's three pages in parallel arrays.
     *
     * Always advances: index [from] joins an unpinned run by definition, so a
     * caller looping `i = runEnd(i, …)` terminates even if every tile has a
     * different triple.
     */
    fun runEnd(
        from: Int,
        count: Int,
        layerPage: IntArray,
        strokePage: IntArray,
        tailPage: IntArray,
    ): Int {
        if (from >= count) return from
        var layer = ABSENT
        var stroke = ABSENT
        var tail = ABSENT
        var i = from
        while (i < count) {
            if (!joins(layer, layerPage[i]) ||
                !joins(stroke, strokePage[i]) ||
                !joins(tail, tailPage[i])
            ) {
                break
            }
            layer = adopt(layer, layerPage[i])
            stroke = adopt(stroke, strokePage[i])
            tail = adopt(tail, tailPage[i])
            i++
        }
        return i
    }

    /**
     * The page each of the three textures is bound to for the run
     * `[from, end)`, written into [out] as `(layer, stroke, tail)`.
     *
     * [ABSENT] where the whole run had no tile in that texture — the caller
     * then has nothing to bind, and every fetch from it returns transparent.
     */
    fun runPages(
        from: Int,
        end: Int,
        layerPage: IntArray,
        strokePage: IntArray,
        tailPage: IntArray,
        out: IntArray,
    ) {
        out[0] = ABSENT
        out[1] = ABSENT
        out[2] = ABSENT
        for (i in from until end) {
            out[0] = adopt(out[0], layerPage[i])
            out[1] = adopt(out[1], strokePage[i])
            out[2] = adopt(out[2], tailPage[i])
        }
    }
}
