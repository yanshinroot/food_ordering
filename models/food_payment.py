from odoo import api, fields, models
from odoo.exceptions import UserError


class FoodCashSession(models.Model):
    _name = "food.cash.session"
    _description = "Food Cashier Cash Session"
    _order = "opened_at desc"
    _rec_name = "display_name"

    display_name = fields.Char(compute="_compute_display_name")
    state = fields.Selection(
        [("open", "Open"), ("closed", "Closed")], default="open", required=True, index=True
    )
    opened_by_user_id = fields.Many2one("res.users")
    opened_by_device_id = fields.Many2one("food.printer.device")
    opened_at = fields.Datetime(default=fields.Datetime.now, required=True)
    opening_cash = fields.Monetary(default=0.0)
    closed_by_user_id = fields.Many2one("res.users")
    closed_by_device_id = fields.Many2one("food.printer.device")
    closed_at = fields.Datetime()
    closing_cash_expected = fields.Monetary(compute="_compute_closing_cash_expected")
    closing_cash_actual = fields.Monetary()
    difference = fields.Monetary(compute="_compute_closing_cash_expected")
    notes = fields.Text()
    currency_id = fields.Many2one(
        "res.currency", default=lambda self: self.env.company.currency_id
    )
    movement_ids = fields.One2many("food.cash.movement", "session_id")
    payment_ids = fields.One2many("food.payment", "cash_session_id")
    sale_total = fields.Monetary(compute="_compute_totals")
    refund_total = fields.Monetary(compute="_compute_totals")
    cash_in_total = fields.Monetary(compute="_compute_totals")
    cash_out_total = fields.Monetary(compute="_compute_totals")

    @api.depends("opened_by_user_id.name", "opened_by_device_id.name", "opened_at")
    def _compute_display_name(self):
        for session in self:
            who = session.opened_by_user_id.name or session.opened_by_device_id.name or "Unknown"
            session.display_name = "%s - %s" % (who, session.opened_at or "")

    @api.depends("movement_ids.amount", "movement_ids.movement_type")
    def _compute_totals(self):
        for session in self:
            sale_total = refund_total = cash_in_total = cash_out_total = 0.0
            for movement in session.movement_ids:
                if movement.movement_type == "sale":
                    sale_total += movement.amount
                elif movement.movement_type == "refund":
                    refund_total += -movement.amount
                elif movement.movement_type == "cash_in":
                    cash_in_total += movement.amount
                elif movement.movement_type == "cash_out":
                    cash_out_total += -movement.amount
            session.sale_total = sale_total
            session.refund_total = refund_total
            session.cash_in_total = cash_in_total
            session.cash_out_total = cash_out_total

    @api.depends("opening_cash", "movement_ids.amount", "closing_cash_actual")
    def _compute_closing_cash_expected(self):
        for session in self:
            expected = session.opening_cash + sum(session.movement_ids.mapped("amount"))
            session.closing_cash_expected = expected
            session.difference = session.closing_cash_actual - expected

    def action_close(self, closing_cash_actual, closed_by_user=None, closed_by_device=None):
        self.ensure_one()
        if self.state != "open":
            raise UserError("This cash session is already closed.")
        self.write({
            "state": "closed",
            "closing_cash_actual": closing_cash_actual,
            "closed_at": fields.Datetime.now(),
            "closed_by_user_id": closed_by_user.id if closed_by_user else False,
            "closed_by_device_id": closed_by_device.id if closed_by_device else False,
        })
        return True


class FoodCashMovement(models.Model):
    _name = "food.cash.movement"
    _description = "Food Cash Movement"
    _order = "create_date desc"

    session_id = fields.Many2one("food.cash.session", required=True, ondelete="cascade", index=True)
    movement_type = fields.Selection(
        [
            ("sale", "Cash Sale"),
            ("refund", "Refund"),
            ("cash_in", "Cash In"),
            ("cash_out", "Cash Out"),
        ],
        required=True,
    )
    amount = fields.Monetary(required=True, help="Positive increases the drawer, negative decreases it.")
    reason = fields.Char()
    order_id = fields.Many2one("sale.order")
    payment_id = fields.Many2one("food.payment")
    currency_id = fields.Many2one(related="session_id.currency_id")


class FoodPayment(models.Model):
    _name = "food.payment"
    _description = "Food Order Payment/Refund"
    _order = "create_date desc"

    order_id = fields.Many2one("sale.order", required=True, ondelete="cascade", index=True)
    payment_type = fields.Selection(
        [("payment", "Payment"), ("refund", "Refund")], required=True, default="payment", index=True
    )
    method = fields.Selection([("cash", "Cash")], required=True, default="cash")
    amount = fields.Monetary(required=True)
    reference = fields.Char()
    state = fields.Selection(
        [("done", "Done"), ("cancelled", "Cancelled")], default="done", required=True
    )
    cash_session_id = fields.Many2one("food.cash.session", index=True)
    created_by_user_id = fields.Many2one("res.users")
    created_by_device_id = fields.Many2one("food.printer.device")
    currency_id = fields.Many2one(
        "res.currency", default=lambda self: self.env.company.currency_id
    )
