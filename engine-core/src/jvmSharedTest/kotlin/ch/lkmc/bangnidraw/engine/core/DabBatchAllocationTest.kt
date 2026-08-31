package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertTrue

class DabBatchAllocationTest {

    @Test
    fun `adding dabs allocates nothing after warmup`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        assertTrue(
            bean is com.sun.management.ThreadMXBean && bean.isThreadAllocatedMemorySupported,
            "this JVM cannot measure per-thread allocation",
        )
        val counter = bean as com.sun.management.ThreadMXBean
        counter.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val batch = DabBatch(capacity = DAB_COUNT)

        repeat(WARMUP_ROUNDS) {
            batch.clear()
            addDabs(batch)
        }
        batch.clear()

        val before = counter.getThreadAllocatedBytes(thread)
        addDabs(batch)
        val allocated = counter.getThreadAllocatedBytes(thread) - before

        assertTrue(
            allocated <= ALLOCATION_BUDGET_BYTES,
            "DabBatch.add allocated $allocated bytes for $DAB_COUNT dabs",
        )
    }

    private fun addDabs(batch: DabBatch) {
        repeat(DAB_COUNT) { index ->
            batch.add(
                x = index.toFloat(),
                y = index.toFloat(),
                radius = 4f,
                flow = 1f,
                hardness = 1f,
                angle = 0f,
                aspect = 1f,
                seed = 0f,
            )
        }
    }

    private companion object {
        const val DAB_COUNT = 1_024
        const val WARMUP_ROUNDS = 100
        const val ALLOCATION_BUDGET_BYTES = 256L
    }
}
