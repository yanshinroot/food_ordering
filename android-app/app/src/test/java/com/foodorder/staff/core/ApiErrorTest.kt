package com.foodorder.staff.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scenario 17: offline state — verifies which error kinds are safe to
 *  auto-retry (network/timeout/5xx) versus which must never be retried
 *  blindly (validation, auth, rate-limit, stale-version, 4xx conflicts). */
class ApiErrorTest {
    @Test
    fun `network and timeout errors are safe to auto retry`() {
        assertTrue(ApiError.Network("x").isSafeToAutoRetry)
        assertTrue(ApiError.Timeout("x").isSafeToAutoRetry)
    }

    @Test
    fun `server 5xx errors are safe to auto retry`() {
        assertTrue(ApiError.ServerError("x", 500).isSafeToAutoRetry)
        assertTrue(ApiError.ServerError("x", 503).isSafeToAutoRetry)
    }

    @Test
    fun `validation and auth errors are never auto retried`() {
        assertFalse(ApiError.Validation("x").isSafeToAutoRetry)
        assertFalse(ApiError.Unauthorized("x").isSafeToAutoRetry)
        assertFalse(ApiError.Forbidden("x").isSafeToAutoRetry)
        assertFalse(ApiError.RateLimited("x").isSafeToAutoRetry)
    }

    @Test
    fun `stale version and conflict errors are never auto retried`() {
        assertFalse(ApiError.StaleVersion("x", 1).isSafeToAutoRetry)
        assertFalse(ApiError.Conflict("x").isSafeToAutoRetry)
    }

    @Test
    fun `client 4xx server errors are not auto retried`() {
        assertFalse(ApiError.ServerError("x", 400).isSafeToAutoRetry)
    }
}
