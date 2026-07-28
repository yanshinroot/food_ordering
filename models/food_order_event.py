from odoo import fields, models


class FoodOrderEvent(models.Model):
    _name = "food.order.event"
    _description = "Food Order Audit Event"
    _order = "create_date desc, id desc"

    order_id = fields.Many2one("sale.order", required=True, ondelete="cascade", index=True)
    event_type = fields.Selection(
        [
            ("created", "Order Created"),
            ("accepted", "Accepted"),
            ("preparing", "Preparing"),
            ("ready", "Ready"),
            ("paid", "Paid"),
            ("completed", "Completed"),
            ("cancelled", "Cancelled"),
            ("refund_initiated", "Refund Initiated"),
            ("refunded", "Refunded"),
            ("printed", "Printed"),
            ("print_failed", "Print Failed"),
            ("reprint_requested", "Reprint Requested"),
            ("cash_session_opened", "Cash Session Opened"),
            ("cash_session_closed", "Cash Session Closed"),
        ],
        required=True,
        index=True,
    )
    previous_status = fields.Char()
    new_status = fields.Char()
    reason = fields.Char()
    actor_user_id = fields.Many2one("res.users")
    actor_device_id = fields.Many2one("food.printer.device")

    def _log(self, order, event_type, previous_status=False, new_status=False, reason=False,
             actor_user=None, actor_device=None):
        """Write an immutable audit row. Never pass secrets in `reason`."""
        self.sudo().create({
            "order_id": order.id,
            "event_type": event_type,
            "previous_status": previous_status or "",
            "new_status": new_status or "",
            "reason": (reason or "")[:500],
            "actor_user_id": actor_user.id if actor_user else False,
            "actor_device_id": actor_device.id if actor_device else False,
        })
