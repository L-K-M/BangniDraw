package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.data.shared.BangniCodec

/**
 * How a `.bangni` presents itself to the system pickers.
 *
 * There is no registered IANA type for this format, so the export picker is
 * told `application/octet-stream`: every file provider accepts it, and the
 * extension in the suggested name is what actually identifies the file.
 *
 * The *open* picker's list ends in `*&#47;*` and therefore filters nothing —
 * deliberately. Providers disagree about what a `.bangni` is (octet-stream,
 * `application/zip`, `application/x-zip-compressed`, or the extension's own
 * invented type), and a filter that guesses wrong greys out the user's own
 * file with no way to reach it. The first two entries stay because they are
 * what most providers report and they document the intent; the wildcard is
 * what makes the dialog reliable. Picking the wrong file is safe: the format
 * is recognized by content, and `BangniCodec.read` refuses anything else
 * without throwing.
 */
internal object BangniProjectFiles {

    const val MIME_TYPE = "application/octet-stream"

    val OPEN_MIME_TYPES = arrayOf(MIME_TYPE, "application/zip", "*/*")

    /** A sensible default name for the picker; the user can still rename it. */
    fun fileName(title: String): String =
        GalleryNames.sanitizeDisplayName(title, FALLBACK_STEM) + "." + BangniCodec.EXTENSION

    private const val FALLBACK_STEM = "painting"
}
