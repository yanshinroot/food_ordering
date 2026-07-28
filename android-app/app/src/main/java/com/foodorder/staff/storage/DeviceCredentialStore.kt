package com.foodorder.staff.storage

/** Everything the app needs to know about the device it is running as,
 *  once paired. Never put any part of this in savedInstanceState or a
 *  navigation argument — it lives only in [DeviceCredentialStore]. */
data class DeviceIdentity(
    val deviceId: Int,
    val deviceKey: String,
    val role: String,
    val serverUrl: String,
    val deviceName: String,
    val pairedAtMillis: Long,
)

/** Abstraction over where the paired device's secret lives, so the rest of
 *  the app (and unit tests) never talk to EncryptedSharedPreferences /
 *  Android Keystore directly. */
interface DeviceCredentialStore {
    fun read(): DeviceIdentity?
    fun save(identity: DeviceIdentity)
    fun clear()
}

/** In-memory implementation used by unit tests and previews — carries no
 *  Android framework dependency at all. */
class InMemoryDeviceCredentialStore(initial: DeviceIdentity? = null) : DeviceCredentialStore {
    private var current: DeviceIdentity? = initial
    override fun read(): DeviceIdentity? = current
    override fun save(identity: DeviceIdentity) { current = identity }
    override fun clear() { current = null }
}
