package com.foodorder.staff.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android Keystore-backed implementation. The AES256_GCM master key is
 * generated and held by the Keystore itself (never exported, never on
 * disk in the clear); EncryptedSharedPreferences uses it to encrypt both
 * the pref file's keys and values before they touch disk.
 *
 * This is the ONLY place in the app that reads or writes the device
 * secret. Nothing here is ever logged.
 */
class EncryptedDeviceStore(context: Context) : DeviceCredentialStore {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(): DeviceIdentity? {
        val deviceKey = prefs.getString(KEY_DEVICE_KEY, null) ?: return null
        val serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: return null
        val role = prefs.getString(KEY_ROLE, null) ?: return null
        return DeviceIdentity(
            deviceId = prefs.getInt(KEY_DEVICE_ID, -1),
            deviceKey = deviceKey,
            role = role,
            serverUrl = serverUrl,
            deviceName = prefs.getString(KEY_DEVICE_NAME, "") ?: "",
            pairedAtMillis = prefs.getLong(KEY_PAIRED_AT, 0L),
        )
    }

    override fun save(identity: DeviceIdentity) {
        prefs.edit()
            .putInt(KEY_DEVICE_ID, identity.deviceId)
            .putString(KEY_DEVICE_KEY, identity.deviceKey)
            .putString(KEY_ROLE, identity.role)
            .putString(KEY_SERVER_URL, identity.serverUrl)
            .putString(KEY_DEVICE_NAME, identity.deviceName)
            .putLong(KEY_PAIRED_AT, identity.pairedAtMillis)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "food_device_secure"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_ROLE = "role"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PAIRED_AT = "paired_at"
    }
}
