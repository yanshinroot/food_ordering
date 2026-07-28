package com.foodorder.staff.kitchen

import androidx.compose.runtime.Composable
import com.foodorder.staff.AppEnvironment
import com.foodorder.staff.RoleConfig

object KitchenRoleConfig : RoleConfig {
    override val role: String = "kitchen"
    override val displayName: String = "Food Kitchen"

    @Composable
    override fun Home(env: AppEnvironment) {
        KitchenHomeScreen(env)
    }
}
