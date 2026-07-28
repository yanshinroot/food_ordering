package com.foodorder.staff

/**
 * Compiled only into the `kitchen` flavor — see the `cashier` source set's
 * copy of this same file for the full explanation of why this pattern is
 * what actually enforces "a Kitchen build cannot become a Cashier build".
 */
object RoleEntryPoint {
    val config: RoleConfig = com.foodorder.staff.kitchen.KitchenRoleConfig
}
