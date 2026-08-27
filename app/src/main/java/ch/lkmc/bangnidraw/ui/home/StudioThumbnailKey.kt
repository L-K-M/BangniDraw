package ch.lkmc.bangnidraw.ui.home

/** Same-path thumbnail rewrites advance the painting revision. */
internal data class StudioThumbnailKey(
    val path: String?,
    val revision: Long,
)
