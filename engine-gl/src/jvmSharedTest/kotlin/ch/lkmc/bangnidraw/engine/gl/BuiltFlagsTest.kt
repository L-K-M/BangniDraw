package ch.lkmc.bangnidraw.engine.gl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The invariant [SandwichCache.BuiltFlags] exists to protect: flags and
 * count move together, so completeness flips exactly when every index is
 * built. Count drift upward would clear a stale half with tiles unbuilt
 * (stale composites rendered); drift downward would keep it stale forever
 * (per-frame rebuild walks).
 */
class BuiltFlagsTest {

    @Test
    fun `toggles keep count and completeness in sync`() {
        val flags = SandwichCache.BuiltFlags(2)

        flags.markBuilt(0)
        flags.markBuilt(0) // double-mark must not double-count
        assertTrue(flags.isBuilt(0))
        assertFalse(flags.isComplete)

        flags.markBuilt(1)
        assertTrue(flags.isComplete)

        flags.unbuild(1)
        flags.unbuild(1) // unbuild of unbuilt must not go negative
        assertFalse(flags.isComplete)
        flags.markBuilt(1)
        assertTrue(flags.isComplete, "a negative count would need two rebuilds here")
    }

    @Test
    fun `reset clears flags and count together`() {
        val flags = SandwichCache.BuiltFlags(1)
        flags.markBuilt(0)
        assertTrue(flags.isComplete)

        flags.reset()

        assertFalse(flags.isBuilt(0))
        assertFalse(flags.isComplete)
        flags.markBuilt(0)
        assertTrue(flags.isComplete, "a stale count after reset would poison completeness")
    }
}
