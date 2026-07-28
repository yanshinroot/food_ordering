package com.foodorder.staff.session

import com.foodorder.staff.core.ApiError
import com.foodorder.staff.storage.DeviceIdentity
import com.foodorder.staff.storage.InMemoryDeviceCredentialStore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private fun sampleIdentity() = DeviceIdentity(
    deviceId = 7, deviceKey = "secret-key", role = "cashier",
    serverUrl = "https://odoo.example.com", deviceName = "Front counter", pairedAtMillis = 0L,
)

class DeviceSessionManagerTest {
    /** Scenario 7: revoked-device handling — a 401 from any screen must
     *  drop the app back to the pairing screen (identity becomes null). */
    @Test
    fun `an unauthorized error clears the paired identity`() {
        val store = InMemoryDeviceCredentialStore(sampleIdentity())
        val manager = DeviceSessionManager(store)
        assertNotNull(manager.identity.value)

        manager.reactTo(ApiError.Unauthorized("device key rejected"))

        assertNull(manager.identity.value)
        assertNull(store.read())
    }

    @Test
    fun `a non-auth error does not clear the paired identity`() {
        val store = InMemoryDeviceCredentialStore(sampleIdentity())
        val manager = DeviceSessionManager(store)

        manager.reactTo(ApiError.Network("offline"))

        assertNotNull(manager.identity.value)
        assertNotNull(store.read())
    }

    /** Scenario 6: secure credential clearing — explicit device reset. */
    @Test
    fun `resetDevice clears the store and the observed identity`() {
        val store = InMemoryDeviceCredentialStore(sampleIdentity())
        val manager = DeviceSessionManager(store)

        manager.resetDevice()

        assertNull(manager.identity.value)
        assertNull(store.read())
    }

    @Test
    fun `onPaired persists and immediately reflects the new identity`() {
        val store = InMemoryDeviceCredentialStore(initial = null)
        val manager = DeviceSessionManager(store)
        assertNull(manager.identity.value)

        manager.onPaired(sampleIdentity())

        assertNotNull(manager.identity.value)
        assertNotNull(store.read())
    }
}
