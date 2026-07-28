package com.foodorder.staff.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Adds FLAG_KEEP_SCREEN_ON only while the composable calling this is in
 * composition, and always removes it on dispose — so only the Kitchen
 * operational screen keeps the display awake, and the flag is released the
 * moment that screen is left (navigating to Setup, backgrounding the app,
 * pairing screen, etc.), rather than the whole app forcing the screen on
 * unconditionally the way the previous single-screen app did.
 */
@Composable
fun KeepScreenOn(activity: ComponentActivity, enabled: Boolean) {
    DisposableEffect(enabled) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
