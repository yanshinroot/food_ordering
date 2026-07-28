package com.foodorder.staff.storage

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scenario 5: secure credential migration. */
class LegacyKeyMigratorTest {
    @Test
    fun `no legacy key means nothing to migrate`() = runTest {
        val store = InMemoryDeviceCredentialStore()
        var cleared = false
        val outcome = LegacyKeyMigrator.migrate(
            legacyServerUrl = "", legacyKey = "", role = "cashier",
            validate = { _, _ -> KeyValidation.VALID },
            store = store, clearLegacyKey = { cleared = true },
        )
        assertEquals(MigrationOutcome.NO_LEGACY_KEY, outcome)
        assertNull(store.read())
        assertTrue(!cleared)
    }

    @Test
    fun `a valid legacy key is written to encrypted storage then the plaintext is cleared`() = runTest {
        val store = InMemoryDeviceCredentialStore()
        var cleared = false
        val outcome = LegacyKeyMigrator.migrate(
            legacyServerUrl = "https://odoo.example.com", legacyKey = "old-plaintext-key", role = "cashier",
            validate = { _, _ -> KeyValidation.VALID },
            store = store, clearLegacyKey = { cleared = true }, nowMillis = { 42L },
        )
        assertEquals(MigrationOutcome.MIGRATED, outcome)
        assertEquals("old-plaintext-key", store.read()?.deviceKey)
        assertEquals("cashier", store.read()?.role)
        assertTrue("plaintext must be cleared only after the encrypted write is confirmed", cleared)
    }

    /** A revoked/rotated key must not be migrated, and — critically — must
     *  not be left silently treated as valid; the plaintext value is inert
     *  either way, so nothing needs to be deleted for this to be safe. */
    @Test
    fun `an invalid legacy key is not migrated`() = runTest {
        val store = InMemoryDeviceCredentialStore()
        var cleared = false
        val outcome = LegacyKeyMigrator.migrate(
            legacyServerUrl = "https://odoo.example.com", legacyKey = "revoked-key", role = "kitchen",
            validate = { _, _ -> KeyValidation.INVALID },
            store = store, clearLegacyKey = { cleared = true },
        )
        assertEquals(MigrationOutcome.INVALID_KEY, outcome)
        assertNull(store.read())
        assertTrue(!cleared)
    }

    @Test
    fun `a network error during validation defers migration without touching anything`() = runTest {
        val store = InMemoryDeviceCredentialStore()
        var cleared = false
        val outcome = LegacyKeyMigrator.migrate(
            legacyServerUrl = "https://odoo.example.com", legacyKey = "some-key", role = "cashier",
            validate = { _, _ -> KeyValidation.UNKNOWN_NETWORK_ERROR },
            store = store, clearLegacyKey = { cleared = true },
        )
        assertEquals(MigrationOutcome.DEFERRED_NETWORK_ERROR, outcome)
        assertNull(store.read())
        assertTrue(!cleared)
    }

    @Test
    fun `an unverified encrypted write does not clear the plaintext value`() = runTest {
        // A store whose save() silently fails to persist (read-back returns
        // something else) must never trigger clearing the only remaining
        // valid copy of the key.
        val brokenStore = object : DeviceCredentialStore {
            override fun read(): DeviceIdentity? = null
            override fun save(identity: DeviceIdentity) { /* pretend this failed to persist */ }
            override fun clear() {}
        }
        var cleared = false
        val outcome = LegacyKeyMigrator.migrate(
            legacyServerUrl = "https://odoo.example.com", legacyKey = "some-key", role = "cashier",
            validate = { _, _ -> KeyValidation.VALID },
            store = brokenStore, clearLegacyKey = { cleared = true },
        )
        assertEquals(MigrationOutcome.ENCRYPTED_WRITE_UNVERIFIED, outcome)
        assertTrue(!cleared)
    }
}
