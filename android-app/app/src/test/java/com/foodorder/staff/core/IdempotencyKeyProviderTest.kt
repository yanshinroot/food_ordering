package com.foodorder.staff.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IdempotencyKeyProviderTest {
    private fun provider(): IdempotencyKeyProvider {
        var counter = 0
        return IdempotencyKeyProvider(uuidFactory = { "key-${counter++}" }, nowMillis = { 1_000L })
    }

    /** Scenario 8: idempotency key reuse on retry. */
    @Test
    fun `same logical action id reuses the same key`() {
        val provider = provider()
        val first = provider.keyFor("accept:42")
        val second = provider.keyFor("accept:42")
        assertEquals(first, second)
    }

    /** Scenario 9: new idempotency key for a new logical action. */
    @Test
    fun `different logical action ids get different keys`() {
        val provider = provider()
        val accept = provider.keyFor("accept:42")
        val prepare = provider.keyFor("prepare:42")
        assertNotEquals(accept, prepare)
    }

    @Test
    fun `retiring an action id mints a fresh key on the next call`() {
        val provider = provider()
        val first = provider.keyFor("accept:42")
        provider.retire("accept:42")
        val second = provider.keyFor("accept:42")
        assertNotEquals(first, second)
    }

    @Test
    fun `expired entries are not reused`() {
        var now = 0L
        val provider = IdempotencyKeyProvider(uuidFactory = { "k-$now" }, nowMillis = { now }, ttlMillis = 100L)
        val first = provider.keyFor("accept:1")
        now = 200L
        val second = provider.keyFor("accept:1")
        assertNotEquals(first, second)
    }
}
