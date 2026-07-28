import hashlib
import secrets

from odoo import _, api, fields, models
from odoo.exceptions import UserError


def _hash_key(raw_key):
    return hashlib.sha256((raw_key or "").encode("utf-8")).hexdigest()


class FoodPrinterDevice(models.Model):
    _name = "food.printer.device"
    _description = "Food Staff/Printer Device"
    _order = "name"

    name = fields.Char(required=True)
    # The raw secret is never stored. It only exists in memory for the
    # single response where it is generated (rotation or pairing claim).
    device_key_hash = fields.Char(index=True, copy=False)
    target = fields.Selection(
        [("cashier", "Cashier"), ("kitchen", "Kitchen")], required=True
    )
    device_uuid = fields.Char(help="Stable identifier reported by the Android install.")
    app_version = fields.Char()
    branch = fields.Char(help="Optional branch/location label.")
    printer_type = fields.Selection(
        [("network", "Network ESC/POS"), ("usb", "USB/OS Printer")],
        default="network",
        required=True,
    )
    printer_address = fields.Char(help="Example: 192.168.1.50:9100 or an OS printer name")
    paper_width_mm = fields.Selection(
        [("58", "58mm"), ("80", "80mm")], default="80", required=True
    )
    cutter_enabled = fields.Boolean(default=True)
    encoding = fields.Selection(
        [("utf-8", "UTF-8"), ("cp874", "CP874 (Thai/Latin, no Myanmar)"), ("ascii", "ASCII-safe fallback")],
        default="utf-8",
        required=True,
        help="Myanmar Unicode glyphs require a font/firmware that supports them; "
             "see docs/PRINTING_SETUP.md for fallback options.",
    )
    active = fields.Boolean(default=True)
    revoked_at = fields.Datetime(copy=False)
    revoked_reason = fields.Char(copy=False)
    last_seen_at = fields.Datetime(readonly=True)
    last_ip = fields.Char(readonly=True)
    connection_state = fields.Selection(
        [("online", "Online"), ("offline", "Offline")],
        compute="_compute_print_health",
    )
    queued_jobs = fields.Integer(compute="_compute_print_health")
    failed_jobs = fields.Integer(compute="_compute_print_health")

    # Existing plaintext device_key values are hashed by
    # migrations/19.0.11.0.0/pre-migrate.py, which runs before this column
    # is dropped — a _register_hook here would run too late, since Odoo
    # drops obsolete field columns during module load, before hooks fire.

    def _compute_print_health(self):
        now = fields.Datetime.now()
        cutoff = fields.Datetime.subtract(now, seconds=30)
        Job = self.env["food.print.job"].sudo()
        for device in self:
            device.connection_state = "online" if device.last_seen_at and device.last_seen_at >= cutoff else "offline"
            device.queued_jobs = Job.search_count([
                ("target", "=", device.target), ("state", "in", ["queued", "claimed"])
            ])
            device.failed_jobs = Job.search_count([
                ("target", "=", device.target), ("state", "in", ["failed", "dead_letter"])
            ])

    def _set_device_key(self, raw_key):
        self.ensure_one()
        self.device_key_hash = _hash_key(raw_key)

    @api.model
    def _find_by_raw_key(self, raw_key):
        if not raw_key:
            return self.browse()
        return self.sudo().search(
            [("device_key_hash", "=", _hash_key(raw_key)), ("active", "=", True)], limit=1
        )

    def action_rotate_key(self):
        self.ensure_one()
        raw_key = secrets.token_urlsafe(32)
        self._set_device_key(raw_key)
        return {
            "type": "ir.actions.client",
            "tag": "display_notification",
            "params": {
                "title": _("New device key (shown once)"),
                "message": raw_key,
                "sticky": True,
            },
        }

    def action_revoke(self):
        for device in self:
            device.write({
                "active": False,
                "revoked_at": fields.Datetime.now(),
                "revoked_reason": "Revoked by %s" % self.env.user.name,
            })
        return True


class FoodPrintJob(models.Model):
    _name = "food.print.job"
    _description = "Food Print Job"
    _order = "create_date asc"

    order_id = fields.Many2one("sale.order", required=True, ondelete="cascade", index=True)
    target = fields.Selection(
        [("cashier", "Cashier"), ("kitchen", "Kitchen")], required=True, index=True
    )
    state = fields.Selection(
        [
            ("queued", "Queued"),
            ("claimed", "Claimed"),
            ("printed", "Printed"),
            ("failed", "Failed"),
            ("dead_letter", "Dead Letter"),
        ],
        default="queued",
        required=True,
        index=True,
    )
    payload = fields.Text(required=True)
    template_version = fields.Integer(default=1)
    device_id = fields.Many2one("food.printer.device", ondelete="set null")
    claimed_at = fields.Datetime()
    printed_at = fields.Datetime()
    attempts = fields.Integer(default=0)
    max_attempts = fields.Integer(default=5)
    error_message = fields.Text()
    is_reprint = fields.Boolean(default=False)

    _order_target_unique = models.Constraint(
        "UNIQUE(order_id, target)", "Each order can have one print job per target."
    )

    def action_retry(self):
        for job in self:
            if job.attempts >= job.max_attempts and job.state == "dead_letter":
                job.attempts = 0
            job.write({
                "state": "queued",
                "device_id": False,
                "claimed_at": False,
                "error_message": False,
            })
        return True

    def _mark_failed(self, error_message):
        for job in self:
            state = "dead_letter" if job.attempts >= job.max_attempts else "failed"
            job.write({"state": state, "error_message": error_message or ""})

    def action_ack(self, success, error_message=None, actor_device=None):
        self.ensure_one()
        if success:
            self.write({"state": "printed", "printed_at": fields.Datetime.now(), "error_message": False})
            event_type, reason = "printed", self.target
        else:
            self._mark_failed(error_message)
            event_type, reason = "print_failed", (error_message or "")[:200]
        self.env["food.order.event"]._log(
            self.order_id, event_type, reason=reason, actor_device=actor_device
        )
        return True

    @api.autovacuum
    def _reset_stale_claims(self):
        cutoff = fields.Datetime.subtract(fields.Datetime.now(), minutes=5)
        self.search([("state", "=", "claimed"), ("claimed_at", "<", cutoff)]).write(
            {"state": "queued", "device_id": False, "claimed_at": False}
        )

    @api.autovacuum
    def _dead_letter_exhausted_jobs(self):
        self.search([("state", "=", "failed"), ("attempts", ">=", 1)]).filtered(
            lambda job: job.attempts >= job.max_attempts
        ).write({"state": "dead_letter"})
