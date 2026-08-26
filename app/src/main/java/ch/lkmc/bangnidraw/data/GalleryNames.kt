package ch.lkmc.bangnidraw.data

/**
 * `DISPLAY_NAME` sanitization for the gallery mirror
 * (`docs/plan/06-document-and-persistence.md` §10): strip separators and
 * control characters, trim, cap at [MAX_DISPLAY_CHARS] characters, fall back
 * when nothing is left. CJK titles pass through — MediaStore stores UTF-8 and
 * the 帮你Draw folder name itself is the proof.
 */
internal object GalleryNames {

    const val MAX_DISPLAY_CHARS = 80

    /** The `.png`-less display name for [title], or [fallback] ("Sketch N"). */
    fun sanitizeDisplayName(title: String, fallback: String): String {
        val cleaned = title
            .asSequence()
            .filterNot { it == '/' || it == '\\' || it.isISOControl() }
            .joinToString("")
            .trim()
            .take(MAX_DISPLAY_CHARS)
            // A cap that lands on trailing whitespace would leave a name
            // Win32-style copies mangle (the LayerId lesson); trim again.
            .trim()
        return cleaned.ifEmpty { fallback }
    }
}
