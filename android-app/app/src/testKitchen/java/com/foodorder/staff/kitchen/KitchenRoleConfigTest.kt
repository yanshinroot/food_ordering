package com.foodorder.staff.kitchen

import org.junit.Assert.assertEquals
import org.junit.Test

/** Scenario 23 (Kitchen half). */
class KitchenRoleConfigTest {
    @Test
    fun `kitchen flavor role matches the server's kitchen target string`() {
        assertEquals("kitchen", KitchenRoleConfig.role)
    }
}
