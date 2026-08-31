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
    fun `readCompact strips the spaces the needles omit`() {
        assertEquals(
            "Box{IconButton(onClick={menuOpen=true})",
            ContractTestSources.canonicalize("Box { IconButton(onClick = { menuOpen = true }) ")
                .replace(" ", ""),
        )
    }
}
