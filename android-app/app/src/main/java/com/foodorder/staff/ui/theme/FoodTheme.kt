package com.foodorder.staff.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Cashier and Kitchen intentionally use different primary colors (matching
 *  their distinct launcher icons) so the two apps are visually
 *  distinguishable at a glance — not just by name — per the requirement
 *  that the two builds have "visibly different application identity". */
enum class FoodRoleTheme(val primary: Color, val secondary: Color) {
    CASHIER(primary = Color(0xFF17653D), secondary = Color(0xFFF06A32)),
    KITCHEN(primary = Color(0xFFC5532B), secondary = Color(0xFF17653D)),
}

@Composable
fun FoodStaffTheme(role: FoodRoleTheme, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = role.primary,
            onPrimary = Color.White,
            secondary = role.secondary,
            background = Color(0xFFF5F7F5),
            surface = Color.White,
            onSurface = Color(0xFF111713),
        ),
        content = content,
    )
}
