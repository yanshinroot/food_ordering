package com.foodorder.staff.poll

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePollerTest {
    /** Scenario 19: polling start/stop lifecycle — start is idempotent
     *  (guards against a duplicate loop), and stop actually halts ticking. */
    @Test
    fun `starting twice does not create a second loop`() = runTest {
        val poller = AdaptivePoller(baseIntervalMs = 1_000)
        var tickCount = 0
        poller.start(this) { tickCount++; true }
        poller.start(this) { tickCount++; true } // no-op: already running
        advanceTimeBy(3_500)
        // A single loop ticking every 1s for 3.5s fires 4 times (t=0,1000,2000,3000);
        // a duplicate loop would roughly double that count.
        assertTrue("expected a single loop's tick count, got $tickCount", tickCount in 3..5)
        poller.stop()
    }

    @Test
    fun `stop halts further ticks`() = runTest {
        val poller = AdaptivePoller(baseIntervalMs = 1_000)
        var tickCount = 0
        poller.start(this) { tickCount++; true }
        advanceTimeBy(1_500)
        val countAtStop = tickCount
        poller.stop()
        assertFalse(poller.isRunning)
        advanceTimeBy(5_000)
        assertEquals(countAtStop, tickCount)
    }

    /** Scenario 18: online recovery — after failures back the interval off,
     *  a single success snaps it straight back to the base interval rather
     *  than a slow ramp-down. */
    @Test
    fun `a success after failures resets the interval to base`() = runTest {
        val poller = AdaptivePoller(baseIntervalMs = 1_000, maxIntervalMs = 8_000)
        var callNumber = 0
        val callTimestamps = mutableListOf<Long>()
        poller.start(this) {
            callNumber++
            callTimestamps.add(currentTime)
            // Fail the first two ticks (backs off to 2000ms, then 4000ms),
            // then succeed on the third and every one after.
            callNumber >= 3
        }
        advanceTimeBy(20_000)
        poller.stop()
        // First tick at t=0 (fails), second at t=1000 (backed off wait, fails),
        // third at t=1000+2000=3000 (succeeds) — interval then resets to 1000ms
        // for the next tick at t=4000, not a continued backoff to 8000ms.
        assertTrue(callTimestamps.size >= 5)
        val gapAfterRecovery = callTimestamps[4] - callTimestamps[3]
        assertEquals(1_000L, gapAfterRecovery)
    }

    @Test
    fun `refreshNow ticks immediately without waiting for the pending delay`() = runTest {
        val poller = AdaptivePoller(baseIntervalMs = 5_000)
        var tickCount = 0
        poller.start(this) { tickCount++; true }
        advanceTimeBy(100) // still well within the first 5s wait
        val beforeRefresh = tickCount
        poller.refreshNow(this) { tickCount++; true }
        advanceTimeBy(100)
        assertEquals(beforeRefresh + 1, tickCount)
        poller.stop()
    }
}
