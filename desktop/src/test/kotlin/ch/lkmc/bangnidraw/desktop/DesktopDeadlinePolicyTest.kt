package ch.lkmc.bangnidraw.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDeadlinePolicyTest {

    @Test
    fun `deadline delay rounds up and never becomes negative`() {
        assertEquals(0, DesktopDeadlinePolicy.delayMillis(nowNs = 10, deadlineNs = 9))
        assertEquals(1, DesktopDeadlinePolicy.delayMillis(nowNs = 10, deadlineNs = 11))
        assertEquals(
            2,
            DesktopDeadlinePolicy.delayMillis(nowNs = 0, deadlineNs = 1_000_001),
        )
    }
}
