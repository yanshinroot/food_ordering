package com.foodorder.staff.sound

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scenario 20: duplicate-notification prevention. */
class AlertDedupeGuardTest {
    @Test
    fun `first observation never alerts even with existing orders`() {
        val guard = AlertDedupeGuard()
        assertFalse(guard.hasNewArrivals(setOf(1, 2, 3)))
    }

    @Test
    fun `a genuinely new id triggers an alert`() {
        val guard = AlertDedupeGuard()
        guard.hasNewArrivals(setOf(1, 2))
        assertTrue(guard.hasNewArrivals(setOf(1, 2, 3)))
    }

    @Test
    fun `polling the same set again does not re-alert`() {
        val guard = AlertDedupeGuard()
        guard.hasNewArrivals(setOf(1, 2))
        guard.hasNewArrivals(setOf(1, 2, 3))
        assertFalse(guard.hasNewArrivals(setOf(1, 2, 3)))
    }

    @Test
    fun `reset forgets history so the next set is treated as baseline`() {
        val guard = AlertDedupeGuard()
        guard.hasNewArrivals(setOf(1))
        guard.reset()
        assertFalse(guard.hasNewArrivals(setOf(1, 2)))
    }
}
