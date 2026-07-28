package com.foodorder.staff.core

import kotlin.math.min
import kotlin.math.pow

/**
 * Capped exponential backoff, pure function so it is trivially unit
 * testable without touching the network or a coroutine scheduler.
 */
object RetryPolicy {
    const val MAX_ATTEMPTS = 4
    private const val BASE_DELAY_MS = 500L
    private const val MAX_DELAY_MS = 8_000L

    /** attempt is 1-based (the delay to wait *before* this attempt). */
    fun delayMillisFor(attempt: Int): Long {
        require(attempt >= 1)
        val raw = BASE_DELAY_MS * 2.0.pow(attempt - 1)
        return min(raw, MAX_DELAY_MS.toDouble()).toLong()
    }

    fun shouldRetry(attempt: Int, error: ApiError): Boolean {
        if (attempt >= MAX_ATTEMPTS) return false
        return error.isSafeToAutoRetry
    }
}
