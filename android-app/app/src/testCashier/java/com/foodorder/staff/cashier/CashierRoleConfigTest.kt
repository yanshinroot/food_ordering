package com.foodorder.staff.cashier

import org.junit.Assert.assertEquals
import org.junit.Test

/** Scenario 23 (Cashier half): the role string this build identifies as
 *  must exactly match what the server's device.target values are
 *  ("cashier"/"kitchen") — print jobs, actions, and pairing role-matching
 *  all key off this string. */
class CashierRoleConfigTest {
    @Test
    fun `cashier flavor role matches the server's cashier target string`() {
        assertEquals("cashier", CashierRoleConfig.role)
    }
}
