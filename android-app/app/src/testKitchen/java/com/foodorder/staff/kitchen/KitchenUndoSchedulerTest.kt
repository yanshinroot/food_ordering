package com.foodorder.staff.kitchen

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scenario 16: Kitchen 4-second undo hold. */
class KitchenUndoSchedulerTest {
    @Test
    fun `the action is not sent before the hold window elapses`() = runTest {
        val scheduler = KitchenUndoScheduler(holdMillis = 4_000)
        var fired = false
        scheduler.schedule(this) { fired = true }
        assertTrue(scheduler.isPending)
        advanceTimeBy(3_000)
        assertFalse("must not fire before the hold window elapses", fired)
    }

    @Test
    fun `the action fires once the hold window elapses`() = runTest {
        val scheduler = KitchenUndoScheduler(holdMillis = 4_000)
        var fired = false
        scheduler.schedule(this) { fired = true }
        advanceTimeBy(4_100)
        assertTrue(fired)
        assertFalse(scheduler.isPending)
    }

    @Test
    fun `undo cancels the pending send`() = runTest {
        val scheduler = KitchenUndoScheduler(holdMillis = 4_000)
        var fired = false
        scheduler.schedule(this) { fired = true }
        advanceTimeBy(1_000)
        scheduler.undo()
        assertFalse(scheduler.isPending)
        advanceTimeBy(5_000)
        assertFalse("undo must permanently cancel this hold, not just pause it", fired)
    }

    @Test
    fun `a second schedule replaces the first pending action`() = runTest {
        val scheduler = KitchenUndoScheduler(holdMillis = 4_000)
        var firstFired = false
        var secondFired = false
        scheduler.schedule(this) { firstFired = true }
        advanceTimeBy(1_000)
        scheduler.schedule(this) { secondFired = true }
        advanceTimeBy(4_100)
        assertFalse("the superseded first hold must never fire", firstFired)
        assertTrue(secondFired)
    }

    @Test
    fun `undo after the action already fired is a safe no-op`() = runTest {
        val scheduler = KitchenUndoScheduler(holdMillis = 4_000)
        var fired = false
        scheduler.schedule(this) { fired = true }
        advanceTimeBy(4_100)
        assertTrue(fired)
        scheduler.undo() // must not throw, must not un-fire the already-sent action
        assertFalse(scheduler.isPending)
    }
}
