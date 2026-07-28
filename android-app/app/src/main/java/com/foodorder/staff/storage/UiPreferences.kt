package com.foodorder.staff.storage

import android.content.Context
import com.foodorder.staff.PrinterProtocol
import com.foodorder.staff.PrinterTransport

/** Non-sensitive, device-local UI preferences (printer wiring, sound
 *  toggle). Deliberately kept in plain (unencrypted) SharedPreferences and
 *  in a separate store from [EncryptedDeviceStore] — none of this is a
 *  secret, and mixing it into the encrypted file would just make every
 *  settings tweak pay the Keystore cost for no benefit. */
class UiPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("food-order-ui", Context.MODE_PRIVATE)

    var networkPrinter: String
        get() = prefs.getString("printer", "") ?: ""
        set(value) = prefs.edit().putString("printer", value).apply()

    var usbDeviceId: Int
        get() = prefs.getInt("usbDeviceId", -1)
        set(value) = prefs.edit().putInt("usbDeviceId", value).apply()

    var bluetoothAddress: String
        get() = prefs.getString("bluetoothAddress", "") ?: ""
        set(value) = prefs.edit().putString("bluetoothAddress", value).apply()

    /** Explicit transport choice. Defaults are inferred from whatever was
     *  already configured before this setting existed (a network address
     *  or a chosen USB device), so upgrading doesn't silently drop an
     *  existing working setup. */
    var transport: PrinterTransport
        get() {
            val stored = prefs.getString("transport", null)
            if (stored != null) return runCatching { PrinterTransport.valueOf(stored) }.getOrDefault(PrinterTransport.NETWORK)
            return when {
                networkPrinter.isNotBlank() -> PrinterTransport.NETWORK
                usbDeviceId >= 0 -> PrinterTransport.USB
                else -> PrinterTransport.NETWORK
            }
        }
        set(value) = prefs.edit().putString("transport", value.name).apply()

    var protocol: PrinterProtocol
        get() = runCatching { PrinterProtocol.valueOf(prefs.getString("protocol", PrinterProtocol.ESC_POS.name)!!) }
            .getOrDefault(PrinterProtocol.ESC_POS)
        set(value) = prefs.edit().putString("protocol", value.name).apply()

    var labelWidthMm: Int
        get() = prefs.getInt("labelWidthMm", com.foodorder.staff.DEFAULT_LABEL_WIDTH_MM)
        set(value) = prefs.edit().putInt("labelWidthMm", value).apply()

    var textScale: Int
        get() = prefs.getInt("textScale", com.foodorder.staff.DEFAULT_TEXT_SCALE)
        set(value) = prefs.edit().putInt("textScale", value).apply()

    var tearMarginMm: Int
        get() = prefs.getInt("tearMarginMm", com.foodorder.staff.DEFAULT_TEAR_MARGIN_MM)
        set(value) = prefs.edit().putInt("tearMarginMm", value).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean("soundEnabled", true)
        set(value) = prefs.edit().putBoolean("soundEnabled", value).apply()

    /** Stable per-install identifier sent as `device_uuid` on pairing —
     *  generated once and reused, not tied to any hardware identifier. */
    val deviceUuid: String
        get() {
            val existing = prefs.getString("deviceUuid", null)
            if (existing != null) return existing
            val fresh = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("deviceUuid", fresh).apply()
            return fresh
        }

    /** Last server URL the user typed on the pairing screen, so re-opening
     *  it (e.g. after a network error) doesn't start from a blank field.
     *  Not a secret — the paired server URL itself lives in
     *  DeviceCredentialStore once pairing succeeds. */
    var lastServerUrl: String
        get() = prefs.getString("lastServerUrl", "") ?: ""
        set(value) = prefs.edit().putString("lastServerUrl", value).apply()

    /** Read-only accessors onto the legacy plaintext keys, used only by
     *  [LegacyKeyMigrator] — never written to again after migration. */
    fun legacyServerUrl(): String = prefs.getString("server", "") ?: ""
    fun legacyCashierKey(): String = prefs.getString("cashierKey", prefs.getString("key", "")) ?: ""
    fun legacyKitchenKey(): String = prefs.getString("kitchenKey", "") ?: ""

    fun clearLegacyKeys() {
        prefs.edit().remove("cashierKey").remove("kitchenKey").remove("key").apply()
    }
}
