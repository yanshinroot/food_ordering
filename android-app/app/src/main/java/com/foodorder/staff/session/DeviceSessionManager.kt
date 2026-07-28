package com.foodorder.staff.session

import androidx.compose.runtime.mutableStateOf
import com.foodorder.staff.core.ApiError
import com.foodorder.staff.storage.DeviceCredentialStore
import com.foodorder.staff.storage.DeviceIdentity

/**
 * Single source of truth for "what device are we, and are we still
 * paired". Every screen that calls the API funnels 401 responses back
 * through [reportUnauthorized] so a revoked device drops back to the
 * pairing screen from wherever it was, instead of failing silently or
 * looping on retries with a dead key.
 */
class DeviceSessionManager(private val store: DeviceCredentialStore) {
    val identity = mutableStateOf(store.read())

    fun refreshFromStore() {
        identity.value = store.read()
    }

    fun onPaired(newIdentity: DeviceIdentity) {
        store.save(newIdentity)
        identity.value = newIdentity
    }

    /** Call when any API response comes back as [ApiError.Unauthorized] —
     *  clears the dead credential and forces navigation back to pairing. */
    fun reportUnauthorized() {
        store.clear()
        identity.value = null
    }

    fun resetDevice() {
        store.clear()
        identity.value = null
    }

    fun reactTo(error: ApiError) {
        if (error is ApiError.Unauthorized) reportUnauthorized()
    }
}
