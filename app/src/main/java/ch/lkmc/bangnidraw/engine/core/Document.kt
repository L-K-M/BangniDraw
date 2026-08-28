package ch.lkmc.bangnidraw.engine.core

/**
 * A painting, as the engine sees it: a fixed pixel size, a paper colour, an
 * ordered stack of layers and where the undo cursor sits.
 *
 * Immutable — every edit replaces the value. The serialised form
 * (`project.json`) is a separate type in `data/` so the on-disk format can
 * change without touching the engine (`docs/plan/06-document-and-persistence.md`
 * §3).
 */
data class Document(
    val id: String,
    val title: String = "",
    val width: Int,
    val height: Int,
    val dpi: Int = DEFAULT_DPI,
    /** ARGB; alpha 0 means transparent paper. The paper is a document colour, not a layer. */
    val paperColor: Int,
    val stack: LayerStack,
    /** Optional tracing aid. It is never part of the paint stack or exports. */
    val tracingReference: TracingReference? = null,
    /**
     * How many journal entries are *applied*: `[oldestSeq, oldestSeq + cursor)`
     * are in effect and the rest are redoable
     * (`docs/plan/06-document-and-persistence.md` §5.2, which declares it
     * `Int`). A count, not a [HistoryEntry.seq] — those are `Long` and start at
     * 1, this starts at 0, so comparing the two directly is off by one as well
     * as a narrowing conversion.
     */
    val historyCursor: Int = 0,
    val galleryUri: String? = null,
    /** Epoch ms of the last gallery sync; 0 = never (06 §3). */
    val lastGallerySyncAt: Long = 0L,
    /**
     * The gallery row's `DATE_MODIFIED` (seconds) and `SIZE` as of our last
     * write — 06 §9.2's tamper check: a row whose values moved was edited by
     * another app and is abandoned, never overwritten. 0 = unknown, treated
     * as ours (folders written before the rule).
     */
    val galleryModifiedAt: Long = 0L,
    val galleryBytes: Long = 0L,
    /**
     * The reference variant's row state — the second gallery item kept while
     * a tracing image is visible (AGENTS.md). Shares [lastGallerySyncAt]:
     * the two rows are written by one sync, so one timestamp debounces both.
     */
    val referenceGalleryUri: String? = null,
    val referenceGalleryModifiedAt: Long = 0L,
    val referenceGalleryBytes: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    // Kotlin runs initialisers in declaration order, so this block has to come
    // before `grid`: otherwise a bad size reaches TileGrid's constructor first
    // and the caller sees its message instead of this one.
    init {
        require(width in MIN_EDGE..MAX_EDGE && height in MIN_EDGE..MAX_EDGE) {
            "canvas ${width}x$height is outside $MIN_EDGE..$MAX_EDGE per side"
        }
        // dpi divides in every px -> mm/inch conversion export and the UI do.
        require(dpi > 0) { "dpi must be positive, was $dpi" }
    }

    /**
     * The tile geometry of this canvas. Built once; not part of `equals`.
     *
     * The tile-count ceiling is not re-checked here: [TileGrid]'s own `init`
     * throws for a canvas over [TileGrid.MAX_TILES] tiles, so this
     * construction is where that refusal comes from.
     */
    val grid: TileGrid = TileGrid(width, height)

    val isPaperTransparent: Boolean get() = (paperColor ushr 24) == 0

    companion object {
        const val DEFAULT_DPI = 300

        /**
         * The format's per-side limits, owned by [TileGrid] because that is
         * the class whose packing they follow from. Aliased here so document
         * code reads naturally; the v1 UI ceiling is lower again.
         */
        const val MIN_EDGE = TileGrid.MIN_EDGE
        const val MAX_EDGE = TileGrid.MAX_EDGE
    }
}
