package com.foodorder.staff.storage

/** Result of asking the server whether a legacy plaintext key still works. */
enum class KeyValidation { VALID, INVALID, UNKNOWN_NETWORK_ERROR }

enum class MigrationOutcome {
    MIGRATED,
    NO_LEGACY_KEY,
    /** Key was rejected by the server (401) — it was revoked/rotated since
     *  the plaintext value was written. The plaintext value is left alone
     *  (it's dead either way) and the app proceeds to the pairing screen. */
    INVALID_KEY,
    /** Couldn't reach the server to check. Nothing is deleted; migration is
     *  retried on the next launch. */
    DEFERRED_NETWORK_ERROR,
    /** The encrypted write didn't read back correctly; treated the same as
     *  a network error — plaintext is preserved, retry next launch. */
    ENCRYPTED_WRITE_UNVERIFIED,
}

/**
 * One-time migration of a pre-pairing, plaintext-SharedPreferences device
 * key into [DeviceCredentialStore]. Steps, matching the required order
 * exactly: (1) read the old plaintext value — only during migration, never
 * again after; (2) write it into encrypted storage; (3) read it back to
 * confirm the encrypted write actually took; (4) only then delete the
 * plaintext value; (5) the plaintext value is never recreated afterwards
 * (nothing in this class, or anywhere else in the app, ever writes to the
 * legacy pref keys again).
 *
 * The network validation step is injected as [validate] so the decision
 * logic here has no Android/network dependency and is directly unit
 * testable with a fake.
 */
object LegacyKeyMigrator {
    suspend fun migrate(
        legacyServerUrl: String,
        legacyKey: String,
        role: String,
        validate: suspend (serverUrl: String, key: String) -> KeyValidation,
        store: DeviceCredentialStore,
        clearLegacyKey: () -> Unit,
        nowMillis: () -> Long = { System.currentTimeMillis() },
    ): MigrationOutcome {
        if (legacyKey.isBlank() || legacyServerUrl.isBlank()) return MigrationOutcome.NO_LEGACY_KEY

        return when (validate(legacyServerUrl, legacyKey)) {
            KeyValidation.INVALID -> MigrationOutcome.INVALID_KEY
            KeyValidation.UNKNOWN_NETWORK_ERROR -> MigrationOutcome.DEFERRED_NETWORK_ERROR
            KeyValidation.VALID -> {
                store.save(
                    DeviceIdentity(
                        // No device id is available from the legacy flow (it never
                        // called pairing/claim) — -1 marks "migrated, id unknown".
                        // Nothing in the app relies on deviceId for correctness.
                        deviceId = -1,
                        deviceKey = legacyKey,
                        role = role,
                        serverUrl = legacyServerUrl,
                        deviceName = "Migrated device",
                        pairedAtMillis = nowMillis(),
                    )
                )
                val readBack = store.read()
                if (readBack != null && readBack.deviceKey == legacyKey) {
                    clearLegacyKey()
                    MigrationOutcome.MIGRATED
                } else {
                    MigrationOutcome.ENCRYPTED_WRITE_UNVERIFIED
                }
            }
        }
    }
}
