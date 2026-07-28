package com.foodorder.staff.cashier

import androidx.compose.runtime.Composable
import com.foodorder.staff.AppEnvironment
import com.foodorder.staff.RoleConfig

object CashierRoleConfig : RoleConfig {
    override val role: String = "cashier"
    override val displayName: String = "Food Cashier"

    @Composable
    override fun Home(env: AppEnvironment) {
        CashierHomeScreen(env)
    }
}
