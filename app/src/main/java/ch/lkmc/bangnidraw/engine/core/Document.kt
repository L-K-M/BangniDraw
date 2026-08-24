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
    val historyCursor: Int = 0,
    val galleryUri: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** The tile geometry of this canvas. Built once; not part of `equals`. */
    val grid: TileGrid = TileGrid(width, height)

    init {
        require(width in MIN_EDGE..MAX_EDGE && height in MIN_EDGE..MAX_EDGE) {
            "canvas ${width}x$height is outside $MIN_EDGE..$MAX_EDGE per side"
        }
        // dpi divides in every px -> mm/inch conversion export and the UI do.
        require(dpi > 0) { "dpi must be positive, was $dpi" }
        require(grid.tileCount <= TileGrid.MAX_TILES) {
            "canvas ${width}x$height needs ${grid.tileCount} tiles, over the ${TileGrid.MAX_TILES} the format allows"
        }
    }

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
