package com.foodorder.staff.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foodorder.staff.core.ApiResult
import com.foodorder.staff.net.FoodApiClient
import com.foodorder.staff.storage.DeviceIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class PairingUiState {
    data object Idle : PairingUiState()
    data object Loading : PairingUiState()
    data class Error(val message: String, val retryable: Boolean = true) : PairingUiState()
    data class Paired(val identity: DeviceIdentity) : PairingUiState()
}

/**
 * Drives the enrollment screen for either flavor. [expectedRole] is fixed
 * per build (via the flavor's RoleConfig) and is enforced here in addition
 * to (never instead of) the server's own per-action role checks — see
 * AppEnvironment / RoleConfig for why BuildConfig alone is never treated
 * as authorization.
 */
class PairingViewModel(private val expectedRole: String) : ViewModel() {
    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state

    fun submit(serverUrl: String, code: String, deviceName: String, deviceUuid: String, appVersion: String) {
        val trimmedUrl = serverUrl.trim()
        val trimmedCode = code.trim()
        if (trimmedUrl.isBlank()) {
            _state.value = PairingUiState.Error("Enter the Odoo server URL.")
            return
        }
        if (trimmedCode.isBlank()) {
            _state.value = PairingUiState.Error("Enter the pairing code.")
            return
        }
        _state.value = PairingUiState.Loading
        viewModelScope.launch {
            val client = FoodApiClient({ trimmedUrl }, { "" })
            when (val result = client.claimPairing(trimmedUrl, trimmedCode, deviceName, deviceUuid, appVersion)) {
                is ApiResult.Success -> {
                    val claim = result.value
                    if (claim.role != expectedRole) {
                        _state.value = PairingUiState.Error(describeRoleMismatch(expectedRole, claim.role), retryable = true)
                        return@launch
                    }
                    _state.value = PairingUiState.Paired(
                        DeviceIdentity(
                            deviceId = claim.deviceId,
                            deviceKey = claim.deviceKey,
                            role = claim.role,
                            serverUrl = trimmedUrl,
                            deviceName = claim.name,
                            pairedAtMillis = System.currentTimeMillis(),
                        )
                    )
                }
                is ApiResult.Failure -> _state.value = PairingUiState.Error(describePairingError(result.error))
            }
        }
    }

    fun resetToIdle() {
        _state.value = PairingUiState.Idle
    }
}
