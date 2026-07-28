package com.foodorder.staff

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.foodorder.staff.core.ApiResult
import com.foodorder.staff.core.IdempotencyKeyProvider
import com.foodorder.staff.net.FoodApiClient
import com.foodorder.staff.pairing.PairingScreen
import com.foodorder.staff.pairing.PairingViewModel
import com.foodorder.staff.printer.PrintQueueManager
import com.foodorder.staff.session.DeviceSessionManager
import com.foodorder.staff.storage.DeviceIdentity
import com.foodorder.staff.storage.EncryptedDeviceStore
import com.foodorder.staff.storage.KeyValidation
import com.foodorder.staff.storage.LegacyKeyMigrator
import com.foodorder.staff.storage.MigrationOutcome
import com.foodorder.staff.storage.UiPreferences
import com.foodorder.staff.ui.theme.FoodRoleTheme
import com.foodorder.staff.ui.theme.FoodStaffTheme

/**
 * Single shared Activity for both flavors — which screens it can actually
 * reach is decided at compile time by which [RoleConfig] implementation
 * got compiled in (see RoleEntryPoint in each flavor's source set), not by
 * anything read at runtime. There is deliberately no UI anywhere in this
 * app that lets a paired device switch which role it acts as; the only way
 * to change role is: a manager revokes the device in Odoo, then this app
 * re-pairs against a differently-scoped code.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val credentialStore = EncryptedDeviceStore(this)
        val uiPreferences = UiPreferences(this)
        val sessionManager = DeviceSessionManager(credentialStore)
        val roleConfig = RoleEntryPoint.config

        setContent {
            FoodStaffTheme(role = if (roleConfig.role == "kitchen") FoodRoleTheme.KITCHEN else FoodRoleTheme.CASHIER) {
                // targetSdk 35 enforces edge-to-edge by default, so content
                // needs explicit system-bar insets or it renders underneath
                // the status/navigation bars — required reading for both the
                // pairing screen and every role Home screen since they all
                // render inside this one root.
                Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    FoodStaffRoot(this@MainActivity, roleConfig, sessionManager, uiPreferences, credentialStore)
                }
            }
        }
    }
}

@Composable
private fun FoodStaffRoot(
    activity: MainActivity,
    roleConfig: RoleConfig,
    sessionManager: DeviceSessionManager,
    uiPreferences: UiPreferences,
    credentialStore: com.foodorder.staff.storage.DeviceCredentialStore,
) {
    var migrationChecked by remember { mutableStateOf(false) }

    // One-time legacy-plaintext-key migration attempt, only relevant the
    // very first time this build runs after the credential-storage change;
    // once identity is non-null (or migration has been attempted this
    // launch) this effect does nothing on subsequent recompositions.
    LaunchedEffect(Unit) {
        if (sessionManager.identity.value == null) {
            val legacyServerUrl = uiPreferences.legacyServerUrl()
            val legacyKey = if (roleConfig.role == "cashier") uiPreferences.legacyCashierKey() else uiPreferences.legacyKitchenKey()
            val outcome = LegacyKeyMigrator.migrate(
                legacyServerUrl = legacyServerUrl,
                legacyKey = legacyKey,
                role = roleConfig.role,
                validate = { serverUrl, key -> validateLegacyKey(serverUrl, key) },
                store = credentialStore,
                clearLegacyKey = { uiPreferences.clearLegacyKeys() },
            )
            if (outcome == MigrationOutcome.MIGRATED) sessionManager.refreshFromStore()
        }
        migrationChecked = true
    }

    val identity = sessionManager.identity.value
    if (!migrationChecked) return

    if (identity == null || identity.role != roleConfig.role) {
        val pairingViewModel = remember(roleConfig.role) { PairingViewModel(roleConfig.role) }
        PairingScreen(
            expectedRoleLabel = roleConfig.displayName.removePrefix("Food ").ifBlank { roleConfig.role },
            viewModel = pairingViewModel,
            initialServerUrl = uiPreferences.lastServerUrl.ifBlank { identity?.serverUrl ?: "" },
            deviceUuid = uiPreferences.deviceUuid,
            appVersion = "${BuildConfig.VERSION_NAME} (${Build.MODEL})",
            defaultDeviceName = "${roleConfig.displayName} - ${Build.MODEL}",
            onServerUrlChanged = { uiPreferences.lastServerUrl = it },
            onPaired = { newIdentity -> sessionManager.onPaired(newIdentity) },
        )
        return
    }

    val apiClient = remember(identity.deviceKey, identity.serverUrl) {
        FoodApiClient({ identity.serverUrl }, { identity.deviceKey })
    }
    val printQueue = remember(apiClient) { PrintQueueManager(activity, apiClient, uiPreferences) }
    val idempotency = remember { IdempotencyKeyProvider() }
    val environment = remember(identity, apiClient, printQueue) {
        AppEnvironment(activity, identity, apiClient, printQueue, uiPreferences, sessionManager, idempotency)
    }
    roleConfig.Home(environment)
}

private suspend fun validateLegacyKey(serverUrl: String, key: String): KeyValidation {
    val client = FoodApiClient({ serverUrl }, { key })
    return when (val result = client.orders("active")) {
        is ApiResult.Success -> KeyValidation.VALID
        is ApiResult.Failure -> if (result.error is com.foodorder.staff.core.ApiError.Unauthorized) {
            KeyValidation.INVALID
        } else {
            KeyValidation.UNKNOWN_NETWORK_ERROR
        }
    }
}
