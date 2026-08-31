package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The normalization the source-contract suite is built on, tested directly.
 *
 * Every one of those tests pins behavior by matching source text, so the
 * matcher's own blind spots decide which mechanical reformats can false-fail
 * a behavioral pin. That makes this worth pinning rather than assuming.
 */
class ContractTestSourcesTest {

    @Test
    fun `the wrap Kotlin's style guide produces folds onto the single-line spelling`() {
        val wrapped = """
            finishCheckpoint(
                snapshot,
                thumbnailResult,
            )
        """.trimIndent()

        assertEquals(
            "finishCheckpoint(snapshot, thumbnailResult)",
            ContractTestSources.canonicalize(wrapped).trim(),
        )
    }

    @Test
    fun `already single-line calls are left exactly as they are`() {
        val flat = "finishCheckpoint(snapshot, thumbnailResult)"

        assertEquals(flat, ContractTestSources.canonicalize(flat))
    }

    @Test
    fun `collapsing whitespace alone would not have been enough`() {
        // The regression this replaced: the old normalization left
        // "finishCheckpoint( snapshot, thumbnailResult, )", which the needle
        // above does not match. Stated as a test so the reason the extra
        // steps exist cannot be optimized away as redundant.
        val wrapped = "finishCheckpoint(\n    snapshot,\n    thumbnailResult,\n)"
        val collapsedOnly = wrapped.replace(Regex("\\s+"), " ")

        assertTrue("finishCheckpoint(snapshot, thumbnailResult)" !in collapsedOnly)
        assertTrue("finishCheckpoint(snapshot, thumbnailResult)" in ContractTestSources.canonicalize(wrapped))
    }

    @Test
    fun `compact strips the spaces the space-free needles omit`() {
        // Through the shared helper `readCompact` delegates to, not a copy of
        // its body: a pin that re-implements the code it pins stays green
        // while the path its callers use drifts away from it.
        assertEquals(
            "Box{IconButton(onClick={menuOpen=true})",
            ContractTestSources.compact("Box { IconButton(onClick = { menuOpen = true }) "),
        )
    }

    @Test
    fun `compact also drops the trailing comma spaces cannot`() {
        // The half stripping spaces would miss on its own, and the reason
        // readCompact canonicalizes first instead of only removing spaces.
        assertEquals(
            "finishCheckpoint(snapshot,thumbnailResult)",
            ContractTestSources.compact("finishCheckpoint(\n    snapshot,\n    thumbnailResult,\n)"),
        )
    }
}
