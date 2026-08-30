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
            .takeWholeCharacters(MAX_DISPLAY_CHARS)
            // A cap that lands on trailing whitespace would leave a name
            // Win32-style copies mangle (the LayerId lesson); trim again.
            .trim()
        return cleaned.ifEmpty { fallback }
    }

    /**
     * The reference variant's name: [base] (already sanitized) plus the
     * localized [suffix], which carries any leading space itself. The base
     * is shortened first so the cut falls on it — the suffix is what tells
     * the two rows apart. A suffix at least as long as the cap is kept
     * whole, so in that extreme the pair exceeds [MAX_DISPLAY_CHARS]; no
     * locale approaches it (" (with reference)" is 17 of 80).
     */
    fun withReferenceSuffix(base: String, suffix: String): String {
        val room = MAX_DISPLAY_CHARS - suffix.length
        if (room <= 0) return base + suffix

        return base.takeWholeCharacters(room).trimEnd() + suffix
    }

    /**
     * [String.take] that never splits a surrogate pair. Both caps count
     * UTF-16 units, and a cut landing inside a supplementary-plane character
     * — an emoji, rare CJK, exactly what a child titles a painting — would
     * hand MediaStore an unpaired surrogate that UTF-8 encoding turns into a
     * replacement character. Dropping the dangling high surrogate keeps the
     * promise the class KDoc makes: CJK titles pass through.
     */
    private fun String.takeWholeCharacters(count: Int): String {
        // String.take semantics for the degenerate cap: empty, never a
        // negative index. Unreachable through today's callers (both guard
        // their counts), kept so a future caller cannot crash sanitization.
        if (count <= 0) return ""
        if (length <= count) return this
        val end = if (Character.isHighSurrogate(this[count - 1])) count - 1 else count
        return substring(0, end)
    }
}
