import secrets

from odoo import api, fields, models


class FoodPrinterDevice(models.Model):
    _name = "food.printer.device"
    _description = "Food Printer Device"
    _order = "name"

    name = fields.Char(required=True)
    device_key = fields.Char(required=True, default=lambda self: secrets.token_urlsafe(32), copy=False)
    target = fields.Selection(
        [("cashier", "Cashier"), ("kitchen", "Kitchen")], required=True
    )
    printer_type = fields.Selection(
        [("network", "Network ESC/POS"), ("usb", "USB/OS Printer")],
        default="network",
        required=True,
    )
    printer_address = fields.Char(help="Example: 192.168.1.50:9100 or an OS printer name")
    active = fields.Boolean(default=True)
    last_seen_at = fields.Datetime(readonly=True)
    connection_state = fields.Selection(
        [("online", "Online"), ("offline", "Offline")],
        compute="_compute_print_health",
    )
    queued_jobs = fields.Integer(compute="_compute_print_health")
    failed_jobs = fields.Integer(compute="_compute_print_health")

    _device_key_unique = models.Constraint(
        "UNIQUE(device_key)", "Printer device keys must be unique."
    )

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
                ("target", "=", device.target), ("state", "=", "failed")
            ])

    def action_rotate_key(self):
        for device in self:
            device.device_key = secrets.token_urlsafe(32)
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
        [("queued", "Queued"), ("claimed", "Claimed"), ("printed", "Printed"), ("failed", "Failed")],
        default="queued",
        required=True,
        index=True,
    )
    payload = fields.Text(required=True)
    device_id = fields.Many2one("food.printer.device", ondelete="set null")
    claimed_at = fields.Datetime()
    printed_at = fields.Datetime()
    attempts = fields.Integer(default=0)
    error_message = fields.Text()

    _order_target_unique = models.Constraint(
        "UNIQUE(order_id, target)", "Each order can have one print job per target."
    )

    def action_retry(self):
        self.write(
            {
                "state": "queued",
                "device_id": False,
                "claimed_at": False,
                "error_message": False,
            }
        )
        return True

    @api.autovacuum
    def _reset_stale_claims(self):
        cutoff = fields.Datetime.subtract(fields.Datetime.now(), minutes=5)
        self.search([("state", "=", "claimed"), ("claimed_at", "<", cutoff)]).write(
            {"state": "queued", "device_id": False, "claimed_at": False}
        )
