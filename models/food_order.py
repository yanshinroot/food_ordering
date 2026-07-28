import hashlib
import json
import secrets

from psycopg2.errors import UniqueViolation

from odoo import _, api, fields, models
from odoo.exceptions import UserError, ValidationError

MAX_CART_LINES = 30
MAX_QTY_PER_LINE = 20
MAX_NOTE_LENGTH = 300
MAX_NAME_LENGTH = 120
MAX_PHONE_LENGTH = 32
MAX_FIELD_LENGTH = 120
MAX_MODIFIER_OPTIONS_PER_LINE = 20

class StaleVersionError(UserError):
    """Raised when a caller's expected_version no longer matches the order's
    current food_state_version — the screen showed stale data and the
    caller should refetch this one order rather than blindly retry."""


# pending -> accepted -> preparing -> ready -> completed.
# Cancellation is handled separately by _food_can_cancel (explicit + configurable).
ALLOWED_TRANSITIONS = {
    "pending": {"accepted"},
    "accepted": {"preparing"},
    "preparing": {"ready"},
    "ready": {"completed"},
    "completed": set(),
    "cancelled": set(),
}


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
    food_state_version = fields.Integer(default=1, copy=False)
    food_last_request_id = fields.Char(copy=False)
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
    food_idempotency_key = fields.Char(copy=False, index=True)
    food_accepted_at = fields.Datetime(copy=False)
    food_ready_at = fields.Datetime(copy=False)
    food_completed_at = fields.Datetime(copy=False)
    food_cancel_reason = fields.Char(copy=False)
    food_cancelled_by_id = fields.Many2one("res.users", copy=False)
    food_payment_status = fields.Selection(
        [
            ("unpaid", "Unpaid"),
            ("paid", "Paid"),
            ("refund_pending", "Refund Pending"),
            ("partially_refunded", "Partially Refunded"),
            ("refunded", "Refunded"),
        ],
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
    food_cash_session_id = fields.Many2one("food.cash.session", string="Cash Session", copy=False)
    food_payment_ids = fields.One2many("food.payment", "order_id", string="Payments")
    food_promotion_id = fields.Many2one("food.promotion", string="Applied Promotion", copy=False)
    food_event_ids = fields.One2many("food.order.event", "order_id", string="Order Events")
    food_print_job_ids = fields.One2many("food.print.job", "order_id", string="Print Jobs")

    _food_idempotency_key_unique = models.Constraint(
        "UNIQUE(food_idempotency_key)", "Duplicate submission for this idempotency key."
    )

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

    # ------------------------------------------------------------------
    # Shared creation service — every guest/staff order-creation path
    # (website checkout, JSON API, walk-in) funnels through here so
    # partner protection, modifier validation/pricing, own-cup discount
    # and promotion pricing are computed identically and only server-side.
    # ------------------------------------------------------------------
    @api.model
    def _food_own_cup_discount_amount(self):
        raw_value = self.env["ir.config_parameter"].sudo().get_param(
            "food_ordering.own_cup_discount", "500"
        )
        try:
            return max(0.0, float(raw_value))
        except ValueError:
            return 0.0

    @api.model
    def _food_create_guest_order(self, name, phone, department, floor, note, items, source,
                                  company=None, idempotency_key=None):
        """Returns (order, created). `created` is False when an existing
        order for the same idempotency_key was returned instead of a new
        one being made (safe retry, not a duplicate)."""
        if idempotency_key:
            existing = self.sudo().search(
                [("food_idempotency_key", "=", idempotency_key)], limit=1
            )
            if existing:
                return existing, False

        Product = self.env["product.product"].sudo()
        ModifierGroup = self.env["food.modifier.group"].sudo()
        normalized = []
        product_tmpl_ids = set()
        for item in list(items or [])[:MAX_CART_LINES]:
            try:
                product_id = int(item.get("product_id"))
            except (AttributeError, TypeError, ValueError):
                continue
            product = Product.browse(product_id).exists()
            if not product or not product.sale_ok or not product.product_tmpl_id.food_available_online:
                continue
            try:
                quantity = max(1, min(MAX_QTY_PER_LINE, int(item.get("quantity", 1) or 1)))
            except (TypeError, ValueError):
                quantity = 1
            own_cup_quantity = 0
            if product.product_tmpl_id.food_own_cup_eligible:
                try:
                    own_cup_quantity = max(0, min(quantity, int(item.get("own_cup_quantity", 0) or 0)))
                except (TypeError, ValueError):
                    own_cup_quantity = 0
            raw_modifier_ids = item.get("modifier_option_ids") or []
            modifier_ids = []
            for raw_id in list(raw_modifier_ids)[:MAX_MODIFIER_OPTIONS_PER_LINE]:
                try:
                    modifier_ids.append(int(raw_id))
                except (TypeError, ValueError):
                    continue
            extra_price, snapshot = ModifierGroup._validate_and_price(
                product.product_tmpl_id, modifier_ids
            )
            normalized.append({
                "product": product,
                "quantity": quantity,
                "own_cup_quantity": own_cup_quantity,
                "note": str(item.get("note", "") or "")[:MAX_NOTE_LENGTH],
                "modifier_snapshot": snapshot,
                "extra_price": extra_price,
            })
            product_tmpl_ids.add(product.product_tmpl_id.id)
        if not normalized:
            raise UserError(_("Your basket has no valid items."))

        Partner = self.env["res.partner"].sudo()
        phone = str(phone).strip()[:MAX_PHONE_LENGTH]
        name = str(name).strip()[:MAX_NAME_LENGTH]
        department = str(department).strip()[:MAX_FIELD_LENGTH]
        floor = str(floor).strip()[:MAX_FIELD_LENGTH]
        note = str(note or "").strip()[:MAX_NOTE_LENGTH]
        partner = Partner.search([("phone", "=", phone)], limit=1)
        if not partner:
            # Only set on create. An existing partner's name/comment is
            # never overwritten by a guest checkout — order-level fields
            # below are the display source of truth, so historical
            # partner records and past orders stay untouched.
            partner = Partner.create({
                "name": name, "phone": phone,
                "comment": "Department: %s\nFloor: %s" % (department, floor),
            })

        company = company or self.env.company
        order_vals = {
            "partner_id": partner.id,
            "pricelist_id": partner.property_product_pricelist.id,
            "company_id": company.id,
            "food_order": True,
            "food_status": "pending",
            "food_order_source": source,
            "food_customer_name": name,
            "food_phone": phone,
            "food_department": department,
            "food_floor": floor,
            "food_note": note,
        }
        if idempotency_key:
            order_vals["food_idempotency_key"] = idempotency_key
            # Two near-simultaneous retries with the same key can both pass
            # the existence check above; the unique constraint is the real
            # guard, so fall back to the row it protected instead of a 500.
            try:
                with self.env.cr.savepoint():
                    order = self.sudo().create(order_vals)
            except UniqueViolation:
                existing = self.sudo().search(
                    [("food_idempotency_key", "=", idempotency_key)], limit=1
                )
                if existing:
                    return existing, False
                raise
        else:
            order = self.sudo().create(order_vals)

        Line = self.env["sale.order.line"].sudo()
        LineModifier = self.env["food.order.line.modifier"].sudo()
        discount_quantity = 0
        order_subtotal = 0.0
        for entry in normalized:
            product = entry["product"]
            price_unit = product.lst_price + entry["extra_price"]
            line = Line.create({
                "order_id": order.id,
                "product_id": product.id,
                "product_uom_qty": entry["quantity"],
                "price_unit": price_unit,
                "food_own_cup": entry["own_cup_quantity"] > 0,
                "food_own_cup_quantity": entry["own_cup_quantity"],
                "food_line_note": entry["note"],
            })
            if entry["modifier_snapshot"]:
                LineModifier.create([
                    {"order_line_id": line.id, **snap} for snap in entry["modifier_snapshot"]
                ])
                line.food_modifier_summary = "; ".join(
                    "%s: %s" % (snap["group_name"], snap["option_name"])
                    for snap in entry["modifier_snapshot"]
                )
            discount_quantity += entry["own_cup_quantity"]
            order_subtotal += price_unit * entry["quantity"]

        if discount_quantity:
            order.food_own_cup = True
            discount_amount = self._food_own_cup_discount_amount()
            Line.create({
                "order_id": order.id,
                "product_id": self.env.ref("food_ordering.product_own_cup_discount").id,
                "product_uom_qty": discount_quantity,
                "price_unit": -discount_amount,
            })
            order_subtotal -= discount_amount * discount_quantity

        promotions = self.env["food.promotion"].sudo()._find_applicable(
            max(0.0, order_subtotal), list(product_tmpl_ids), phone
        )
        for promotion in promotions:
            if promotion.discount_type == "free_product" and promotion.free_product_id:
                Line.create({
                    "order_id": order.id,
                    "product_id": promotion.free_product_id.id,
                    "product_uom_qty": promotion.free_product_qty or 1,
                    "price_unit": 0.0,
                    "name": _("Promotion: %s") % promotion.name,
                })
            else:
                discount = promotion._discount_amount(order_subtotal)
                if discount > 0:
                    Line.create({
                        "order_id": order.id,
                        "product_id": self.env.ref("food_ordering.product_promotion_discount").id,
                        "product_uom_qty": 1,
                        "price_unit": -discount,
                        "name": _("Promotion: %s") % promotion.name,
                    })
            order.food_promotion_id = promotion.id

        self.env["food.order.event"]._log(order, "created", new_status="pending")
        return order, True

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
                    "modifier_details": [
                        {
                            "group": modifier.group_name,
                            "option": modifier.option_name,
                            "price_extra": modifier.price_extra,
                        }
                        for modifier in line.food_modifier_ids
                    ],
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
            "promotion": self.food_promotion_id.name or "",
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

    def action_food_reprint(self, target, actor_user=None, actor_device=None):
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
            if job and job.state == "claimed" and job.claimed_at and job.claimed_at >= stale_cutoff:
                raise UserError(_("This receipt is currently being printed. Try again shortly."))
            values = {
                "payload": json.dumps(order._food_receipt_payload(target), ensure_ascii=False),
                "state": "queued",
                "device_id": False,
                "claimed_at": False,
                "printed_at": False,
                "error_message": False,
                "is_reprint": True,
                "template_version": (job.template_version + 1) if job else 1,
            }
            if job:
                job.write(values)
            else:
                Job.create({"order_id": order.id, "target": target, **values})
            self.env["food.order.event"]._log(
                order, "reprint_requested", reason=target, actor_user=actor_user, actor_device=actor_device
            )
        return True

    # ------------------------------------------------------------------
    # Central state-transition service. Every caller (website, staff web
    # UI, Android/JSON API, automated jobs) goes through this so the
    # transition rules, row locking and audit trail are identical
    # everywhere instead of being reimplemented per surface.
    # ------------------------------------------------------------------
    def _food_can_cancel(self, current_status):
        if current_status in ("completed", "cancelled"):
            return False
        if current_status == "ready":
            allow = self.env["ir.config_parameter"].sudo().get_param(
                "food_ordering.allow_cancel_from_ready", "1"
            )
            return str(allow).lower() not in ("0", "false")
        return True

    def _food_transition(self, new_status, actor_user=None, actor_device=None, reason=None,
                          request_id=None, before_write=None, expected_version=None):
        self.ensure_one()
        # Odoo's ORM defers writes until flushed; without this, a prior
        # write() earlier in the same request (e.g. order creation, or a
        # previous transition) may not be visible yet to this raw SQL
        # read, making the lock/validation below see stale data.
        self.env.flush_all()
        self.env.cr.execute(
            "SELECT food_status, food_last_request_id, food_state_version FROM sale_order WHERE id = %s FOR UPDATE",
            [self.id],
        )
        row = self.env.cr.fetchone()
        if not row:
            raise UserError(_("Order not found."))
        current_status, last_request_id, current_version = row
        if request_id and last_request_id and request_id == last_request_id:
            return {"replayed": True, "status": current_status}
        if expected_version is not None and int(expected_version) != current_version:
            raise StaleVersionError(
                _("This order changed on the server (now at version %s). Refresh to see the latest status.")
                % current_version
            )

        if new_status == "cancelled":
            if not self._food_can_cancel(current_status):
                raise UserError(_("Order in status '%s' cannot be cancelled.") % current_status)
        else:
            if new_status not in ALLOWED_TRANSITIONS.get(current_status, set()):
                raise UserError(
                    _("Cannot move this order from '%s' to '%s'.") % (current_status, new_status)
                )
        if new_status == "completed" and self.food_payment_status not in ("paid", "partially_refunded"):
            raise UserError(_("Collect payment before completing the order."))

        # Runs only after the transition is confirmed valid and while the
        # row lock is still held, e.g. issuing a refund for a cancelled
        # paid order — so a rejected transition can never leave a
        # side-effect (like a refund) committed on its own.
        if before_write:
            before_write(current_status)

        values = {"food_status": new_status, "food_state_version": self.food_state_version + 1}
        if request_id:
            values["food_last_request_id"] = request_id
        if new_status == "accepted":
            values["food_accepted_at"] = fields.Datetime.now()
        elif new_status == "ready":
            values["food_ready_at"] = fields.Datetime.now()
        elif new_status == "completed":
            values["food_completed_at"] = fields.Datetime.now()
        elif new_status == "cancelled":
            values["food_cancel_reason"] = reason or ""
            values["food_cancelled_by_id"] = actor_user.id if actor_user else False
        self.write(values)
        self.env["food.order.event"]._log(
            self, new_status, previous_status=current_status, new_status=new_status,
            reason=reason, actor_user=actor_user, actor_device=actor_device,
        )
        return {"replayed": False, "status": new_status}

    def action_food_accept(self, actor_user=None, actor_device=None, request_id=None, expected_version=None):
        for order in self:
            if not order.food_order:
                raise UserError(_("This is not a food order."))
            result = order._food_transition(
                "accepted", actor_user=actor_user, actor_device=actor_device, request_id=request_id,
                expected_version=expected_version,
            )
            if not result["replayed"]:
                if order.state in ("draft", "sent"):
                    order.action_confirm()
                order._food_enqueue_prints()
        return True

    def action_food_prepare(self, actor_user=None, actor_device=None, request_id=None, expected_version=None):
        for order in self:
            order._food_transition(
                "preparing", actor_user=actor_user, actor_device=actor_device, request_id=request_id,
                expected_version=expected_version,
            )
        return True

    def action_food_ready(self, actor_user=None, actor_device=None, request_id=None, expected_version=None):
        for order in self:
            order._food_transition(
                "ready", actor_user=actor_user, actor_device=actor_device, request_id=request_id,
                expected_version=expected_version,
            )
        return True

    def action_food_complete(self, actor_user=None, actor_device=None, request_id=None, expected_version=None):
        for order in self:
            order._food_transition(
                "completed", actor_user=actor_user, actor_device=actor_device, request_id=request_id,
                expected_version=expected_version,
            )
        return True

    def action_food_cancel(self, reason=None, actor_user=None, actor_device=None, manager_pin=None,
                            request_id=None, expected_version=None):
        for order in self:
            def _refund_if_paid(current_status, order=order):
                if order.food_payment_status in ("paid", "partially_refunded"):
                    is_manager = bool(actor_user and actor_user.has_group("food_ordering.group_food_manager"))
                    if not is_manager and not order._food_verify_manager_pin(manager_pin):
                        raise UserError(_("Manager authorization is required to cancel a paid order."))
                    already_refunded = sum(
                        order.food_payment_ids.filtered(lambda p: p.payment_type == "refund").mapped("amount")
                    )
                    remaining = order.amount_total - already_refunded
                    if remaining > 0.01:
                        order._food_refund(
                            remaining, reason or _("Order cancelled"),
                            actor_user=actor_user, actor_device=actor_device,
                        )

            order._food_transition(
                "cancelled", actor_user=actor_user, actor_device=actor_device,
                reason=reason, request_id=request_id, before_write=_refund_if_paid,
                expected_version=expected_version,
            )
            if order.state not in ("cancel", "done"):
                order._action_cancel()
        return True

    # ------------------------------------------------------------------
    # Payment / cash session / refund
    # ------------------------------------------------------------------
    def _food_verify_manager_pin(self, pin):
        if not pin:
            return False
        stored_hash = self.env["ir.config_parameter"].sudo().get_param(
            "food_ordering.manager_pin_hash", ""
        )
        if not stored_hash:
            return False
        return hashlib.sha256(str(pin).encode("utf-8")).hexdigest() == stored_hash

    def _food_find_open_cash_session(self, actor_user=None, actor_device=None):
        Session = self.env["food.cash.session"].sudo()
        if actor_device:
            domain = [("state", "=", "open"), ("opened_by_device_id", "=", actor_device.id)]
        elif actor_user:
            domain = [("state", "=", "open"), ("opened_by_user_id", "=", actor_user.id)]
        else:
            return Session.browse()
        return Session.search(domain, limit=1, order="opened_at desc")

    def action_food_record_payment(self, method, amount_received=0.0, reference=False,
                                    actor_user=None, actor_device=None, request_id=None,
                                    expected_version=None):
        if method != "cash":
            raise UserError(_("Only cash payment is available."))
        for order in self:
            # Row-locked so two near-simultaneous payment requests for the
            # same order (e.g. a retried Android call racing the original)
            # cannot both slip past the "not already paid" check. Flush
            # first so this raw read sees any not-yet-flushed ORM writes.
            self.env.flush_all()
            self.env.cr.execute(
                "SELECT food_payment_status, food_last_request_id, food_state_version"
                "  FROM sale_order WHERE id = %s FOR UPDATE",
                [order.id],
            )
            locked_payment_status, locked_last_request_id, locked_version = self.env.cr.fetchone()
            if expected_version is not None and int(expected_version) != locked_version:
                raise StaleVersionError(
                    _("This order changed on the server (now at version %s). Refresh to see the latest status.")
                    % locked_version
                )
            if not order.food_order or order.food_status == "cancelled":
                raise UserError(_("Payment cannot be collected for this order."))
            if locked_payment_status == "paid":
                if request_id and request_id == locked_last_request_id:
                    continue
                raise UserError(_("This order is already paid."))
            received = float(amount_received or 0.0)
            if received < order.amount_total - 0.001:
                raise UserError(_("Cash received must cover the order total."))
            bypass = str(self.env["ir.config_parameter"].sudo().get_param(
                "food_ordering.require_cash_session", "1"
            )).lower() in ("0", "false")
            session = order._food_find_open_cash_session(actor_user, actor_device)
            if not session and not bypass:
                raise UserError(_("Open a cashier session before collecting cash payments."))
            change = max(0.0, received - order.amount_total)
            collector = actor_user or (self.env.user if not self.env.user._is_public() else None)
            payment = self.env["food.payment"].sudo().create({
                "order_id": order.id, "payment_type": "payment", "method": "cash",
                "amount": order.amount_total, "reference": reference or "",
                "cash_session_id": session.id if session else False,
                "created_by_user_id": collector.id if collector else False,
                "created_by_device_id": actor_device.id if actor_device else False,
            })
            if session:
                self.env["food.cash.movement"].sudo().create({
                    "session_id": session.id, "movement_type": "sale", "amount": order.amount_total,
                    "reason": "Order %s" % order.name, "order_id": order.id, "payment_id": payment.id,
                })
            order.write({
                "food_payment_status": "paid",
                "food_payment_method": method,
                "food_payment_reference": reference or False,
                "food_amount_received": received,
                "food_change_amount": change,
                "food_paid_at": fields.Datetime.now(),
                "food_paid_by_id": collector.id if collector else False,
                "food_cash_session_id": session.id if session else order.food_cash_session_id.id,
            })
            if request_id:
                order.food_last_request_id = request_id
            self.env["food.order.event"]._log(
                order, "paid", new_status="paid", actor_user=actor_user, actor_device=actor_device
            )
        return True

    def _food_refund(self, amount, reason, actor_user=None, actor_device=None):
        self.ensure_one()
        amount = min(float(amount or 0.0), self.amount_total)
        if amount <= 0:
            raise UserError(_("Refund amount must be positive."))
        session = self._food_find_open_cash_session(actor_user, actor_device) or self.food_cash_session_id
        payment = self.env["food.payment"].sudo().create({
            "order_id": self.id, "payment_type": "refund", "method": "cash", "amount": amount,
            "reference": reason or "", "cash_session_id": session.id if session else False,
            "created_by_user_id": actor_user.id if actor_user else False,
            "created_by_device_id": actor_device.id if actor_device else False,
        })
        if session:
            self.env["food.cash.movement"].sudo().create({
                "session_id": session.id, "movement_type": "refund", "amount": -amount,
                "reason": reason or ("Refund for %s" % self.name),
                "order_id": self.id, "payment_id": payment.id,
            })
        total_refunded = sum(
            self.food_payment_ids.filtered(lambda p: p.payment_type == "refund").mapped("amount")
        )
        new_status = "refunded" if total_refunded >= self.amount_total - 0.01 else "partially_refunded"
        self.write({"food_payment_status": new_status})
        self.env["food.order.event"]._log(
            self, "refunded", reason=reason, actor_user=actor_user, actor_device=actor_device
        )
        return payment

    def action_food_refund(self, amount, reason=None, manager_pin=None, actor_user=None, actor_device=None,
                            expected_version=None):
        self.ensure_one()
        if expected_version is not None:
            self.env.flush_all()
            self.env.cr.execute("SELECT food_state_version FROM sale_order WHERE id = %s FOR UPDATE", [self.id])
            locked_version = self.env.cr.fetchone()[0]
            if int(expected_version) != locked_version:
                raise StaleVersionError(
                    _("This order changed on the server (now at version %s). Refresh to see the latest status.")
                    % locked_version
                )
        if self.food_payment_status not in ("paid", "partially_refunded"):
            raise UserError(_("Only paid orders can be refunded."))
        is_manager = bool(actor_user and actor_user.has_group("food_ordering.group_food_manager"))
        if not is_manager and not self._food_verify_manager_pin(manager_pin):
            raise UserError(_("Manager authorization is required for a refund."))
        self.env["food.order.event"]._log(
            self, "refund_initiated", reason=reason, actor_user=actor_user, actor_device=actor_device
        )
        return self._food_refund(amount, reason, actor_user=actor_user, actor_device=actor_device)


class SaleOrderLine(models.Model):
    _inherit = "sale.order.line"

    food_own_cup = fields.Boolean(string="Own Cup", copy=False)
    food_own_cup_quantity = fields.Integer(string="Own Cup Quantity", copy=False)
    food_modifier_summary = fields.Char(string="Modifiers", copy=False)
    food_modifier_ids = fields.One2many("food.order.line.modifier", "order_line_id", string="Modifier Selections")
    food_line_note = fields.Char(string="Item Note", copy=False)

    @api.constrains("food_own_cup_quantity", "product_uom_qty")
    def _check_food_own_cup_quantity(self):
        for line in self:
            if line.food_own_cup_quantity < 0 or line.food_own_cup_quantity > line.product_uom_qty:
                raise ValidationError(_("Own cup quantity must be between zero and the product quantity."))
