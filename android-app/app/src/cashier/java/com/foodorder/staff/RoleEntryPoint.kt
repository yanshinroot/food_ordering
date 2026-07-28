package com.foodorder.staff

/**
 * Compiled only into the `cashier` flavor. The `kitchen` flavor provides a
 * separate file with the exact same package + object name but a different
 * `config` — AGP only ever compiles one of the two into a given variant,
 * which is what makes the role a build-time fact rather than a runtime
 * toggle (see AppEnvironment.RoleConfig for the full rationale).
 */
object RoleEntryPoint {
    val config: RoleConfig = com.foodorder.staff.cashier.CashierRoleConfig
}
