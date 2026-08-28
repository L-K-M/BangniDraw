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

    /**
     * The reference variant's name: [base] (already sanitized) plus the
     * localized [suffix], which carries any leading space itself. The base
     * is shortened first so the pair stays within [MAX_DISPLAY_CHARS] and
     * the suffix — the part that tells the two rows apart — is never the
     * part that gets cut.
     */
    fun withReferenceSuffix(base: String, suffix: String): String {
        val room = MAX_DISPLAY_CHARS - suffix.length
        if (room <= 0) return base + suffix

        return base.take(room).trimEnd() + suffix
    }
}
