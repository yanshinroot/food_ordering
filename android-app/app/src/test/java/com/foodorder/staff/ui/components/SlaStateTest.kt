package com.foodorder.staff.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Scenario 15: Kitchen state flow (SLA classification). */
class SlaStateTest {
    @Test
    fun `below warn threshold is normal`() {
        assertEquals(SlaState.NORMAL, slaStateFor(elapsedMinutes = 5, warnMinutes = 10, lateMinutes = 20))
    }

    @Test
    fun `at warn threshold is approaching`() {
        assertEquals(SlaState.APPROACHING, slaStateFor(elapsedMinutes = 10, warnMinutes = 10, lateMinutes = 20))
    }

    @Test
    fun `between warn and late is approaching`() {
        assertEquals(SlaState.APPROACHING, slaStateFor(elapsedMinutes = 15, warnMinutes = 10, lateMinutes = 20))
    }

    @Test
    fun `at late threshold is overdue`() {
        assertEquals(SlaState.OVERDUE, slaStateFor(elapsedMinutes = 20, warnMinutes = 10, lateMinutes = 20))
    }

    @Test
    fun `well past late threshold is overdue`() {
        assertEquals(SlaState.OVERDUE, slaStateFor(elapsedMinutes = 999, warnMinutes = 10, lateMinutes = 20))
    }
}
