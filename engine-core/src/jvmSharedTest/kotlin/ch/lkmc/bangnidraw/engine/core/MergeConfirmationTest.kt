package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `LayerStack.mergeDown` rewrites the surviving layer's props to Normal at
 * 100 % with no alpha lock, and its own comment names alpha lock as "the one
 * prop a merge silently changes". Silent is what it was: the panel asked for
 * confirmation on blend mode alone, so a painter using alpha lock to protect
 * a silhouette merged and then painted straight through the protected area
 * with nothing having warned them.
 */
class MergeConfirmationTest {

    @Test
    fun `a plain merge asks nothing`() {
        assertEquals(emptySet(), MergeConfirmation.changes(props(), props()))
    }

    @Test
    fun `either partner's blend mode is worth asking about`() {
        assertEquals(
            setOf(MergeConfirmation.Change.BLEND_MODE),
            MergeConfirmation.changes(props(blendMode = BlendMode.MULTIPLY), props()),
        )
        assertEquals(
            setOf(MergeConfirmation.Change.BLEND_MODE),
            MergeConfirmation.changes(props(), props(blendMode = BlendMode.SCREEN)),
        )
    }

    @Test
    fun `the surviving layer's alpha lock is worth asking about`() {
        assertEquals(
            setOf(MergeConfirmation.Change.ALPHA_LOCK),
            MergeConfirmation.changes(props(), props(alphaLock = true)),
        )
    }

    @Test
    fun `the disappearing layer's alpha lock is not`() {
        // Only the bottom layer survives, and only its props are rewritten;
        // warning about a flag on a layer that ceases to exist would train
        // the user to dismiss the dialog.
        assertEquals(
            emptySet(),
            MergeConfirmation.changes(props(alphaLock = true), props()),
        )
    }

    @Test
    fun `both at once are reported together`() {
        val changes = MergeConfirmation.changes(
            props(blendMode = BlendMode.MULTIPLY),
            props(alphaLock = true),
        )

        assertEquals(
            setOf(MergeConfirmation.Change.BLEND_MODE, MergeConfirmation.Change.ALPHA_LOCK),
            changes,
        )
    }

    @Test
    fun `the props a merge changes without asking are exactly these two`() {
        // The guard against this list going stale as mergeDown's rewrite
        // grows: opacity and visible are the merge's whole point, and locked
        // is a no-op because mergeDown refuses when either partner is locked.
        // Anything else appearing in that reset is a decision a user should
        // hear about, and belongs here.
        assertEquals(
            listOf("BLEND_MODE", "ALPHA_LOCK"),
            MergeConfirmation.Change.entries.map { it.name },
        )
        assertTrue(
            MergeConfirmation.changes(props(), props(opacity = 0.5f)).isEmpty(),
            "opacity is what the user asked for by merging",
        )
    }

    private fun props(
        blendMode: BlendMode = BlendMode.NORMAL,
        alphaLock: Boolean = false,
        opacity: Float = 1f,
    ) = LayerProps(
        id = LayerId("l1"),
        name = "layer",
        alphaLock = alphaLock,
        opacity = opacity,
        blendMode = blendMode,
    )
}
