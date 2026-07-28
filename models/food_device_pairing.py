import secrets
import string

from odoo import api, fields, models
from odoo.exceptions import UserError

CODE_ALPHABET = string.ascii_uppercase + string.digits
CODE_LENGTH = 8
PAIRING_TTL_MINUTES = 5


class FoodDevicePairing(models.Model):
    _name = "food.device.pairing"
    _description = "Food Device Pairing Code"
    _order = "create_date desc"

    code = fields.Char(required=True, index=True, copy=False)
    role = fields.Selection(
        [("cashier", "Cashier"), ("kitchen", "Kitchen")], required=True
    )
    device_name = fields.Char()
    branch = fields.Char()
    expires_at = fields.Datetime(required=True)
    used = fields.Boolean(default=False, copy=False)
    used_at = fields.Datetime(copy=False)
    device_id = fields.Many2one("food.printer.device", copy=False, ondelete="set null")
    created_by_id = fields.Many2one(
        "res.users", default=lambda self: self.env.user, readonly=True
    )

    _code_unique = models.Constraint("UNIQUE(code)", "Pairing code must be unique.")

    @api.model
    def _generate_code(self):
        for _attempt in range(10):
            code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))
            if not self.search_count([("code", "=", code)]):
                return code
        # Astronomically unlikely, but never loop forever.
        return secrets.token_hex(6).upper()

    @api.model_create_multi
    def create(self, vals_list):
        for vals in vals_list:
            if not vals.get("code"):
                vals["code"] = self._generate_code()
            if not vals.get("expires_at"):
                vals["expires_at"] = fields.Datetime.add(
                    fields.Datetime.now(), minutes=PAIRING_TTL_MINUTES
                )
        return super().create(vals_list)

    def action_regenerate(self):
        for pairing in self:
            pairing.write({
                "code": self._generate_code(),
                "expires_at": fields.Datetime.add(fields.Datetime.now(), minutes=PAIRING_TTL_MINUTES),
                "used": False,
                "used_at": False,
                "device_id": False,
            })
        return True

    def _claim(self, device_name, device_uuid, app_version):
        """Redeem this single-use pairing code for a hashed device
        credential. Returns (device, raw_key); raw_key is never stored."""
        self.ensure_one()
        now = fields.Datetime.now()
        if self.used:
            raise UserError("This pairing code has already been used.")
        if now > self.expires_at:
            raise UserError("This pairing code has expired.")
        Device = self.env["food.printer.device"].sudo()
        raw_key = secrets.token_urlsafe(32)
        device = Device.create({
            "name": device_name or self.device_name or ("%s Device" % self.role.title()),
            "target": self.role,
            "device_uuid": device_uuid or "",
            "app_version": app_version or "",
            "branch": self.branch or "",
        })
        device._set_device_key(raw_key)
        self.sudo().write({"used": True, "used_at": now, "device_id": device.id})
        return device, raw_key
