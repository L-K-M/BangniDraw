package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionReleaseGateTest {

    @Test
    fun `readback requested during release waits for renderer cleanup`() {
        val events = mutableListOf<String>()
        val gate = SessionReleaseGate<Int>()
        assertEquals(ReleaseStart.STARTED, gate.beginRelease())

        val request = gate.requestReadback(
            dispatch = { events += "dispatch" },
            afterRelease = { events += "complete:$it" },
        )
        assertEquals(ReadbackRequest.DEFERRED, request)
        assertEquals(emptyList(), events)

        gate.completeRelease(7)
        assertEquals(listOf("complete:7"), events)
    }

    @Test
    fun `active readback is queued before release starts`() {
        val events = mutableListOf<String>()
        val gate = SessionReleaseGate<Int>()

        val request = gate.requestReadback(
            dispatch = { events += "dispatch" },
            afterRelease = { events += "complete:$it" },
        )
        assertEquals(ReadbackRequest.DISPATCHED, request)
        assertEquals(ReleaseStart.STARTED, gate.beginRelease())

        gate.completeRelease(7)
        assertEquals(listOf("dispatch"), events)
    }

    @Test
    fun `released session reports a completed drain`() {
        val events = mutableListOf<String>()
        val gate = SessionReleaseGate<Int>()
        gate.beginRelease()
        gate.completeRelease(9)

        val request = gate.requestReadback(
            dispatch = { events += "dispatch" },
            afterRelease = { events += "complete:$it" },
        )
        assertEquals(ReadbackRequest.RELEASED, request)
        assertEquals(listOf("complete:9"), events)
    }

    @Test
    fun `teardown waiter survives an active session`() {
        val results = mutableListOf<Int>()
        val gate = SessionReleaseGate<Int>()

        gate.afterRelease(results::add)
        assertEquals(emptyList(), results)

        gate.beginRelease()
        gate.completeRelease(5)
        assertEquals(listOf(5), results)
    }
}
