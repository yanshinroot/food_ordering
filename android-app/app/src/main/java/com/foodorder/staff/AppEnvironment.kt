package com.foodorder.staff

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import com.foodorder.staff.core.IdempotencyKeyProvider
import com.foodorder.staff.net.FoodApiClient
import com.foodorder.staff.printer.PrintQueueManager
import com.foodorder.staff.session.DeviceSessionManager
import com.foodorder.staff.storage.DeviceIdentity
import com.foodorder.staff.storage.UiPreferences

/** Everything a role's screens need, assembled once in MainActivity after
 *  pairing is confirmed and handed down — no screen reaches back into
 *  global/static state to get an API client or the device secret. */
class AppEnvironment(
    val activity: ComponentActivity,
    val identity: DeviceIdentity,
    val apiClient: FoodApiClient,
    val printQueue: PrintQueueManager,
    val uiPreferences: UiPreferences,
    val sessionManager: DeviceSessionManager,
    val idempotency: IdempotencyKeyProvider,
)

/** Implemented once per flavor (app/src/cashier and app/src/kitchen), both
 *  under the same package/class name so exactly one is compiled into any
 *  given build — this is what makes "a Cashier build cannot become a
 *  Kitchen build" a compile-time fact rather than a runtime toggle. See
 *  RoleEntryPoint in each flavor's source set. */
interface RoleConfig {
    /** Must match the `role`/`target` string the server uses ("cashier" or
     *  "kitchen") — compared against a pairing code's assigned role before
     *  enrollment is allowed to proceed. */
    val role: String
    val displayName: String

    @Composable
    fun Home(env: AppEnvironment)
}
