package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.data.shared.BangniCodec

/**
 * How a `.bangni` presents itself to the system pickers.
 *
 * There is no registered IANA type for this format, so the export picker is
 * told `application/octet-stream`: every file provider accepts it, and the
 * extension in the suggested name is what actually identifies the file. The
 * *open* picker asks for that plus `application/zip`, because some providers
 * sniff the container and would otherwise grey the file out.
 */
internal object BangniProjectFiles {

    const val MIME_TYPE = "application/octet-stream"

    val OPEN_MIME_TYPES = arrayOf(MIME_TYPE, "application/zip", "*/*")

    /** A sensible default name for the picker; the user can still rename it. */
    fun fileName(title: String): String =
        GalleryNames.sanitizeDisplayName(title, FALLBACK_STEM) + "." + BangniCodec.EXTENSION

    private const val FALLBACK_STEM = "painting"
}
