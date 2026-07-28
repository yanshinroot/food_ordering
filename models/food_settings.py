import hashlib

from odoo import api, fields, models


class FoodSettings(models.TransientModel):
    _name = "food.settings"
    _description = "Food Ordering Settings"

    own_cup_discount = fields.Float(string="Own-cup Discount Amount", default=500.0)
    require_cash_session = fields.Boolean(
        string="Require an open cash session before collecting cash payments", default=True
    )
    allow_cancel_from_ready = fields.Boolean(
        string="Allow cancelling orders that are already Ready", default=True
    )
    manager_pin = fields.Char(
        string="New Manager PIN",
        help="Leave empty to keep the current PIN unchanged. Used to authorize "
             "refunds/cancellations of paid orders from Android/API devices "
             "that have no Odoo login.",
    )
    manager_pin_set = fields.Boolean(string="A manager PIN is currently set", readonly=True)

    @api.model
    def default_get(self, field_names):
        values = super().default_get(field_names)
        Param = self.env["ir.config_parameter"].sudo()
        values["own_cup_discount"] = float(Param.get_param("food_ordering.own_cup_discount", "500"))
        values["require_cash_session"] = Param.get_param("food_ordering.require_cash_session", "1") not in ("0", "false", "False")
        values["allow_cancel_from_ready"] = Param.get_param("food_ordering.allow_cancel_from_ready", "1") not in ("0", "false", "False")
        values["manager_pin_set"] = bool(Param.get_param("food_ordering.manager_pin_hash", ""))
        return values

    def action_apply(self):
        self.ensure_one()
        Param = self.env["ir.config_parameter"].sudo()
        Param.set_param("food_ordering.own_cup_discount", str(self.own_cup_discount))
        Param.set_param("food_ordering.require_cash_session", "1" if self.require_cash_session else "0")
        Param.set_param("food_ordering.allow_cancel_from_ready", "1" if self.allow_cancel_from_ready else "0")
        if self.manager_pin:
            Param.set_param(
                "food_ordering.manager_pin_hash",
                hashlib.sha256(self.manager_pin.encode("utf-8")).hexdigest(),
            )
        return {"type": "ir.actions.act_window_close"}
