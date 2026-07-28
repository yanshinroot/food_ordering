package com.foodorder.staff.pairing

import com.foodorder.staff.core.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingErrorsTest {
    /** Scenario 3: pairing already-used-code error. */
    @Test
    fun `already-used conflict produces a distinct clear message`() {
        val message = describePairingError(ApiError.Conflict("This pairing code has already been used."))
        assertTrue(message.contains("already been used", ignoreCase = true))
    }

    /** Scenario 2: pairing expired-code error. */
    @Test
    fun `expired conflict produces a distinct clear message`() {
        val message = describePairingError(ApiError.Conflict("This pairing code has expired."))
        assertTrue(message.contains("expired", ignoreCase = true))
    }

    @Test
    fun `an unknown code (404) never shows the raw server slug`() {
        val message = describePairingError(ApiError.NotFound("invalid_code"))
        assertTrue(message.isNotBlank())
        assertTrue("must not leak the raw slug", !message.contains("invalid_code"))
    }

    @Test
    fun `network and timeout errors get a connectivity-specific message`() {
        assertTrue(describePairingError(ApiError.Network("x")).contains("server", ignoreCase = true))
        assertTrue(describePairingError(ApiError.Timeout("x")).contains("time", ignoreCase = true))
    }

    @Test
    fun `rate limiting is explained rather than shown as a generic failure`() {
        val message = describePairingError(ApiError.RateLimited("rate_limited"))
        assertTrue(message.contains("attempts", ignoreCase = true) || message.contains("moment", ignoreCase = true))
    }

    /** Scenario 4: flavor/role mismatch — this device's fixed build role
     *  must never silently accept credentials for the other role. */
    @Test
    fun `role mismatch names both the assigned and expected roles`() {
        val message = describeRoleMismatch(expectedRole = "kitchen", actualRole = "cashier")
        assertEquals(
            "This code is for the Cashier app. This device is running the Kitchen app — " +
                "ask your manager for a Kitchen pairing code instead.",
            message,
        )
    }
}
