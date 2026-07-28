"""Preserve existing device authentication across the V2 upgrade.

Runs BEFORE the new module code is loaded, while the legacy plaintext
`device_key` column still exists and before Odoo's normal obsolete-field
cleanup would drop it. Hashing it here (instead of in a `_register_hook`,
which runs too late — after that column is already gone) means every
already-deployed Android app / print agent keeps authenticating with the
exact same raw key it always had; only the server-side storage changes
from plaintext to a SHA-256 hash.

Safe to run multiple times (restartable): the target column is created
with IF NOT EXISTS and only rows missing a hash are touched.
"""
import hashlib
import logging

_logger = logging.getLogger(__name__)


def migrate(cr, version):
    cr.execute(
        """
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'food_printer_device' AND column_name = 'device_key'
        """
    )
    if not cr.fetchone():
        return  # Fresh install, nothing to migrate.

    cr.execute(
        """
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'food_printer_device' AND column_name = 'device_key_hash'
        """
    )
    if not cr.fetchone():
        cr.execute("ALTER TABLE food_printer_device ADD COLUMN device_key_hash varchar")

    cr.execute(
        """
            SELECT id, device_key FROM food_printer_device
             WHERE device_key IS NOT NULL AND device_key != ''
               AND (device_key_hash IS NULL OR device_key_hash = '')
        """
    )
    rows = cr.fetchall()
    for device_id, raw_key in rows:
        cr.execute(
            "UPDATE food_printer_device SET device_key_hash = %s WHERE id = %s",
            (hashlib.sha256(raw_key.encode("utf-8")).hexdigest(), device_id),
        )
    if rows:
        _logger.info(
            "food_ordering pre-migrate: hashed %s existing device key(s) ahead of the "
            "plaintext column being dropped.", len(rows),
        )
