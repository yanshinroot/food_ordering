import json
import secrets

from odoo import _, api, fields, models
from odoo.exceptions import UserError, ValidationError


class SaleOrder(models.Model):
    _inherit = "sale.order"

    food_order = fields.Boolean(string="Food Order", index=True, copy=False)
    food_status = fields.Selection(
        [
            ("pending", "Pending Cashier Acceptance"),
            ("accepted", "Accepted"),
            ("preparing", "Preparing"),
            ("ready", "Ready for Collection"),
            ("completed", "Completed"),
            ("cancelled", "Cancelled"),
        ],
        default="pending",
        index=True,
        tracking=True,
        copy=False,
    )
    food_order_source = fields.Selection(
        [("web", "Website"), ("cashier", "Cashier"), ("android", "Android")],
        default="web",
        required=True,
        copy=False,
    )
    food_customer_name = fields.Char(string="Customer Name", copy=False)
    food_phone = fields.Char(string="Phone Number", copy=False, index=True)
    food_department = fields.Char(string="Department", copy=False)
    food_floor = fields.Char(string="Floor", copy=False)
    food_note = fields.Text(string="Customer Note", copy=False)
    food_own_cup = fields.Boolean(string="Customer Uses Own Cup", copy=False)
    food_access_token = fields.Char(default=lambda self: secrets.token_urlsafe(24), copy=False, index=True)
    food_accepted_at = fields.Datetime(copy=False)
    food_ready_at = fields.Datetime(copy=False)
    food_completed_at = fields.Datetime(copy=False)
    food_payment_status = fields.Selection(
        [("unpaid", "Unpaid"), ("paid", "Paid"), ("refunded", "Refunded")],
        default="unpaid",
        required=True,
        copy=False,
        index=True,
    )
    food_payment_method = fields.Selection(
        [("cash", "Cash")], copy=False
    )
    food_payment_reference = fields.Char(string="Payment Reference", copy=False)
    food_amount_received = fields.Monetary(string="Amount Received", copy=False)
    food_change_amount = fields.Monetary(string="Change", copy=False)
    food_paid_at = fields.Datetime(copy=False)
    food_paid_by_id = fields.Many2one("res.users", string="Collected By", copy=False)
    food_print_job_ids = fields.One2many("food.print.job", "order_id", string="Print Jobs")

    @api.constrains(
        "food_order", "food_customer_name", "food_phone", "food_department", "food_floor"
    )
    def _check_food_customer_information(self):
        for order in self:
            if order.food_order and not all(
                [order.food_customer_name, order.food_phone, order.food_department, order.food_floor]
            ):
                raise ValidationError(
                    _("Food orders require customer name, phone, department and floor.")
                )

    def _food_receipt_payload(self, target):
        self.ensure_one()
        lines = []
        for line in self.order_line.filtered(lambda item: not item.display_type):
            lines.append(
                {
                    "name": line.product_id.display_name or line.name,
                    "quantity": line.product_uom_qty,
                    "unit_price": line.price_unit,
                    "subtotal": line.price_subtotal,
                    "own_cup_eligible": line.product_id.product_tmpl_id.food_own_cup_eligible,
                    "own_cup": line.food_own_cup,
                    "own_cup_quantity": line.food_own_cup_quantity,
                    "modifiers": line.food_modifier_summary or "",
                    "note": line.food_line_note or "",
                }
            )
        return {
            "order_id": self.id,
            "order_number": self.name,
            "target": target,
            "status": self.food_status,
            "source": self.food_order_source,
            "customer": {
                "name": self.food_customer_name,
                "phone": self.food_phone,
                "department": self.food_department,
                "floor": self.food_floor,
                "note": self.food_note or "",
                "own_cup": self.food_own_cup,
            },
            "lines": lines,
            "amount_total": self.amount_total,
            "currency": self.currency_id.name,
            "payment": {
                "status": self.food_payment_status,
                "method": self.food_payment_method or "",
                "reference": self.food_payment_reference or "",
                "received": self.food_amount_received,
                "change": self.food_change_amount,
            },
        }

    def action_food_record_payment(self, method, amount_received=0.0, reference=False):
        if method != "cash":
            raise UserError(_("Only cash payment is available."))
        for order in self:
            if not order.food_order or order.food_status == "cancelled":
                raise UserError(_("Payment cannot be collected for this order."))
            received = float(amount_received or 0.0)
            if received < order.amount_total:
                raise UserError(_("Cash received must cover the order total."))
            order.write({
                "food_payment_status": "paid",
                "food_payment_method": method,
                "food_payment_reference": False,
                "food_amount_received": received,
                "food_change_amount": max(0.0, received - order.amount_total),
                "food_paid_at": fields.Datetime.now(),
                "food_paid_by_id": self.env.user.id,
            })
        return True

    def _food_enqueue_prints(self):
        Job = self.env["food.print.job"]
        for order in self:
            for target in ("cashier", "kitchen"):
                existing = Job.search_count(
                    [("order_id", "=", order.id), ("target", "=", target)]
                )
                if not existing:
                    Job.create(
                        {
                            "order_id": order.id,
                            "target": target,
                            "payload": json.dumps(
                                order._food_receipt_payload(target), ensure_ascii=False
                            ),
                        }
                    )

    def action_food_reprint(self, target):
        if target not in ("cashier", "kitchen"):
            raise UserError(_("Unknown printer target."))
        Job = self.env["food.print.job"]
        stale_cutoff = fields.Datetime.subtract(fields.Datetime.now(), minutes=5)
        for order in self:
            if not order.food_order:
                raise UserError(_("This is not a food order."))
            job = Job.search(
                [("order_id", "=", order.id), ("target", "=", target)], limit=1
            )
            if job.state == "claimed" and job.claimed_at and job.claimed_at >= stale_cutoff:
                raise UserError(_("This receipt is currently being printed. Try again shortly."))
            values = {
                "payload": json.dumps(order._food_receipt_payload(target), ensure_ascii=False),
                "state": "queued",
                "device_id": False,
                "claimed_at": False,
                "printed_at": False,
                "error_message": False,
            }
            if job:
                job.write(values)
            else:
                Job.create({"order_id": order.id, "target": target, **values})
        return True

    def action_food_accept(self):
        for order in self:
            if not order.food_order or order.food_status != "pending":
                raise UserError(_("Only pending food orders can be accepted."))
            if order.state in ("draft", "sent"):
                order.action_confirm()
            order.write({"food_status": "accepted", "food_accepted_at": fields.Datetime.now()})
            order._food_enqueue_prints()
        return True
    def action_food_prepare(self):
        self.filtered(lambda order: order.food_status == "accepted").write(
            {"food_status": "preparing"}
        )
        return True

    def action_food_ready(self):
        self.filtered(lambda order: order.food_status == "preparing").write(
            {"food_status": "ready", "food_ready_at": fields.Datetime.now()}
        )
        return True

    def action_food_complete(self):
        unpaid = self.filtered(
            lambda order: order.food_status == "ready" and order.food_payment_status != "paid"
        )
        if unpaid:
            raise UserError(_("Collect payment before completing the order."))
        self.filtered(lambda order: order.food_status == "ready").write(
            {"food_status": "completed", "food_completed_at": fields.Datetime.now()}
        )
        return True

    def action_food_cancel(self):
        for order in self.filtered(lambda item: item.food_status not in ("completed", "cancelled")):
            if order.state not in ("cancel", "done"):
                order._action_cancel()
            order.food_status = "cancelled"
        return True


class SaleOrderLine(models.Model):
    _inherit = "sale.order.line"

    food_own_cup = fields.Boolean(string="Own Cup", copy=False)
    food_own_cup_quantity = fields.Integer(string="Own Cup Quantity", copy=False)
    food_modifier_summary = fields.Char(string="Modifiers", copy=False)
    food_line_note = fields.Char(string="Item Note", copy=False)

    @api.constrains("food_own_cup_quantity", "product_uom_qty")
    def _check_food_own_cup_quantity(self):
        for line in self:
            if line.food_own_cup_quantity < 0 or line.food_own_cup_quantity > line.product_uom_qty:
                raise ValidationError(_("Own cup quantity must be between zero and the product quantity."))
