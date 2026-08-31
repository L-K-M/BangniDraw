package ch.lkmc.bangnidraw.ui.canvas

import java.io.File

/**
 * Reads repository source files for the contract tests, and canonicalizes
 * their formatting so the pins built on them stay behavioral.
 *
 * The reason these helpers live here rather than in each test's companion:
 * a source-contract test pins *what the code does* by matching its text, so
 * every formatting difference the matcher cannot absorb is a false failure
 * waiting for the next reformat. Collapsing runs of whitespace — the first
 * thing every one of these tests did — absorbs wraps *between* whole tokens
 * and nothing else. It does not absorb the wrap Kotlin's own style guide
 * produces, which breaks after the open paren and adds a trailing comma:
 *
 *     finishCheckpoint(
 *         snapshot,
 *         thumbnailResult,
 *     )
 *
 * collapses to `finishCheckpoint( snapshot, thumbnailResult, )`, which no
 * sanely-spelled needle matches. So the canonicalization goes one step
 * further and removes the whitespace that hugs `(` and `)` along with a
 * trailing comma, which maps that spelling back onto the single-line one and
 * leaves single-line code untouched.
 *
 * The one rule this imposes on needles: **do not depend on a trailing
 * comma**, because canonicalization removes it. Terminate on the `)`
 * instead, which is what a wrapped and an unwrapped call have in common.
 */
internal object ContractTestSources {

    /** The file verbatim, for needles that must see the original text. */
    fun read(path: String): String = File(repositoryRoot(), path).readText()

    /**
     * Canonicalized, for needles spelled with single spaces — the usual
     * choice: `finishCheckpoint(snapshot, thumbnailResult)`.
     */
    fun readNormalized(path: String): String = canonicalize(read(path))

    /**
     * Canonicalized and then stripped of every remaining space, for needles
     * spelled without any: `Box{IconButton(onClick={menuOpen=true})`. Immune
     * to interior wrapping by construction, at the cost of needles that read
     * less like the code they pin.
     */
    fun readCompact(path: String): String = canonicalize(read(path)).replace(" ", "")

    /** Visible for its own test; the two readers above are the entry points. */
    fun canonicalize(source: String): String = source
        .replace(WHITESPACE, " ")
        .replace(AFTER_OPEN_PAREN, "(")
        .replace(BEFORE_CLOSE_PAREN, ")")
        .replace(TRAILING_COMMA, ")")

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: error("cannot locate repository root from $workingDirectory")
    }

    private const val USER_DIRECTORY_PROPERTY = "user.dir"
    private const val ROOT_MARKER = "settings.gradle.kts"
    private const val APP_DIRECTORY = "app/src/main"
    private val WHITESPACE = Regex("\\s+")
    private val AFTER_OPEN_PAREN = Regex("\\(\\s+")
    private val BEFORE_CLOSE_PAREN = Regex("\\s+\\)")
    private val TRAILING_COMMA = Regex(",\\s*\\)")
}
