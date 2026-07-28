import json

from werkzeug.wrappers import Response

from odoo import fields, http
from odoo.exceptions import UserError, ValidationError
from odoo.http import request

from . import food_common as fc
from ..models.food_order import StaleVersionError


def json_response(data, status=200):
    return Response(
        json.dumps(data, ensure_ascii=False, default=str),
        status=status,
        content_type="application/json; charset=utf-8",
    )


class FoodStaffController(http.Controller):
    def _staff_product_image(self, product):
        starter_images = {
            request.env.ref("food_ordering.starter_americano").id: "americano.jpg",
            request.env.ref("food_ordering.starter_milk_tea").id: "milk-tea.jpg",
            request.env.ref("food_ordering.starter_chicken_rice").id: "chicken-rice.jpg",
            request.env.ref("food_ordering.starter_avocado_smoothie").id: "avocado-smoothie.jpg",
        }
        filename = starter_images.get(product.product_tmpl_id.id)
        if filename:
            return "/food_ordering/static/src/img/products/%s" % filename
        return "/web/image/product.product/%s/image_256" % product.id

    def _tax_included_price(self, product):
        if not product.taxes_id:
            return product.lst_price
        return product.taxes_id.compute_all(
            product.lst_price, currency=product.currency_id, quantity=1, product=product,
        )["total_included"]

    def _allowed(self, role):
        group = {
            "cashier": "food_ordering.group_food_cashier",
            "kitchen": "food_ordering.group_food_kitchen",
        }.get(role)
        return bool(group and request.env.user.has_group(group))

    def _is_manager(self):
        return request.env.user.has_group("food_ordering.group_food_manager")

    def _run_action(self, callback):
        """Runs a state-changing callback, normalizing StaleVersionError to
        a distinct error_code the UI can react to (reload just that order)
        separately from an ordinary rejected action."""
        try:
            callback()
        except StaleVersionError as error:
            return json_response({"error": str(error), "error_code": "stale_version"}, 409)
        except UserError as error:
            return json_response({"error": str(error)}, 409)
        return None

    def _serialize(self, order):
        now = fields.Datetime.now()
        created = order.create_date or now
        elapsed = max(0, int((now - created).total_seconds() // 60))
        print_jobs = {job.target: job.state for job in order.food_print_job_ids}
        return {
            "id": order.id,
            "number": order.name,
            "status": order.food_status,
            "state_version": order.food_state_version,
            "source": order.food_order_source,
            "created_at": fields.Datetime.to_string(created),
            "updated_at": fields.Datetime.to_string(order.write_date),
            "accepted_at": fields.Datetime.to_string(order.food_accepted_at) if order.food_accepted_at else False,
            "ready_at": fields.Datetime.to_string(order.food_ready_at) if order.food_ready_at else False,
            "elapsed_minutes": elapsed,
            "customer": order.food_customer_name,
            "phone": order.food_phone,
            "department": order.food_department,
            "floor": order.food_floor,
            "note": order.food_note or "",
            "cancel_reason": order.food_cancel_reason or "",
            "total": "%.0f %s" % (order.amount_total, order.currency_id.name),
            "amount_total": order.amount_total,
            "currency": order.currency_id.name,
            "payment_status": order.food_payment_status,
            "payment_method": order.food_payment_method or "",
            "payment_reference": order.food_payment_reference or "",
            "amount_received": order.food_amount_received,
            "change_amount": order.food_change_amount,
            "promotion": order.food_promotion_id.name or "",
            "print_jobs": print_jobs,
            "lines": [
                {
                    "name": line.product_id.display_name or line.name,
                    "quantity": line.product_uom_qty,
                    "note": line.food_line_note or "",
                    "own_cup": line.food_own_cup,
                    "own_cup_quantity": line.food_own_cup_quantity,
                    "modifiers": line.food_modifier_summary or "",
                    "modifier_details": [
                        {"group": m.group_name, "option": m.option_name, "price_extra": m.price_extra}
                        for m in line.food_modifier_ids
                    ],
                }
                for line in order.order_line.filtered(
                    lambda item: not item.display_type and item.price_unit >= 0
                )
            ],
        }

    def _orders(self, include_recent=False, since_version=None):
        domain = [("food_order", "=", True)]
        active_domain = domain + [("food_status", "in", ["pending", "accepted", "preparing", "ready"])]
        orders = request.env["sale.order"].sudo().search(active_domain, order="create_date asc", limit=150)
        if include_recent:
            recent_cutoff = fields.Datetime.subtract(fields.Datetime.now(), hours=12)
            recent = request.env["sale.order"].sudo().search(
                domain + [
                    ("food_status", "in", ["completed", "cancelled"]),
                    ("write_date", ">=", recent_cutoff),
                ],
                order="write_date desc", limit=50,
            )
            orders = orders | recent
        if since_version is not None:
            orders = orders.filtered(lambda order: order.food_state_version > since_version)
        return orders

    @http.route("/food/staff", type="http", auth="user", website=True)
    def staff_home(self, **kwargs):
        if self._allowed("cashier"):
            return request.redirect("/food/cashier")
        if self._allowed("kitchen"):
            return request.redirect("/food/kitchen")
        return Response("Food staff access is required.", status=403)

    @http.route("/food/<string:role>", type="http", auth="user", website=True)
    def staff_screen(self, role, **kwargs):
        if role not in ("cashier", "kitchen"):
            return request.not_found()
        if not self._allowed(role):
            return Response("You do not have access to this screen.", status=403)
        orders = [self._serialize(order) for order in self._orders(include_recent=(role == "cashier"))]
        session = request.env["food.cash.session"].sudo().search(
            [("state", "=", "open"), ("opened_by_user_id", "=", request.env.user.id)], limit=1
        )
        sla_warn = request.env["ir.config_parameter"].sudo().get_param("food_ordering.kitchen_sla_warn_minutes", "10")
        sla_late = request.env["ir.config_parameter"].sudo().get_param("food_ordering.kitchen_sla_late_minutes", "20")
        return request.render(
            "food_ordering.food_staff_dashboard",
            {
                "role": role,
                "orders": orders,
                "can_switch": self._allowed("cashier") and self._allowed("kitchen"),
                "is_manager": self._is_manager(),
                "cash_session": session,
                "sla_warn_minutes": sla_warn,
                "sla_late_minutes": sla_late,
            },
        )

    @http.route("/food/staff/orders", type="http", auth="user", methods=["GET"])
    def staff_orders(self, role="cashier", **kwargs):
        if not self._allowed(role):
            return json_response({"error": "forbidden"}, 403)
        since_version = kwargs.get("since_version")
        try:
            since_version = int(since_version) if since_version not in (None, "") else None
        except ValueError:
            since_version = None
        include_recent = role == "cashier" and str(kwargs.get("include_recent", "")).lower() in ("1", "true")
        orders = self._orders(include_recent=include_recent, since_version=since_version)
        return json_response({"orders": [self._serialize(order) for order in orders]})

    @http.route("/food/staff/orders/<int:order_id>/events", type="http", auth="user", methods=["GET"])
    def order_events(self, order_id, role="cashier", **kwargs):
        if not self._allowed(role):
            return json_response({"error": "forbidden"}, 403)
        order = request.env["sale.order"].sudo().browse(order_id).exists()
        if not order or not order.food_order:
            return json_response({"error": "not_found"}, 404)
        events = request.env["food.order.event"].sudo().search(
            [("order_id", "=", order.id)], order="create_date desc", limit=100
        )
        return json_response({"events": [{
            "event_type": event.event_type,
            "previous_status": event.previous_status or "",
            "new_status": event.new_status or "",
            "reason": event.reason or "",
            "actor": event.actor_user_id.name or (event.actor_device_id.name if event.actor_device_id else ""),
            "created_at": fields.Datetime.to_string(event.create_date),
        } for event in events]})

    @http.route("/food/staff/products", type="http", auth="user", methods=["GET"])
    def staff_products(self, **kwargs):
        if not self._allowed("cashier"):
            return json_response({"error": "forbidden"}, 403)
        products = request.env["product.product"].sudo().search(
            [("sale_ok", "=", True), ("product_tmpl_id.food_available_online", "=", True)],
            order="name asc",
        )
        promotion = request.env["food.promotion"].sudo()._get_active_banner()
        return json_response({
            "own_cup_discount": request.env["sale.order"]._food_own_cup_discount_amount(),
            "promotion_banner": ({
                "headline": promotion.banner_headline or promotion.name,
                "subtext": promotion.banner_subtext or "",
            } if promotion else None),
            "products": [{
                "id": product.id,
                "name": product.display_name,
                # Tax-inclusive: the walk-in cart total must match what the
                # server will actually charge (same taxes a normal order
                # line gets), so cash collected always covers amount_total.
                # tax_rate lets the client apply the same rate to modifier
                # extras (which are untaxed amounts folded into the line
                # before tax, same as the server does).
                "price": self._tax_included_price(product),
                "tax_rate": round((self._tax_included_price(product) / product.lst_price) - 1, 6) if product.lst_price else 0.0,
                "price_label": "%.0f %s" % (product.lst_price, product.currency_id.name),
                "currency": product.currency_id.name,
                "image_url": self._staff_product_image(product),
                "category": "drinks" if product.product_tmpl_id.food_is_beverage else "meals",
                "own_cup_eligible": product.product_tmpl_id.food_own_cup_eligible,
                "modifier_groups": [
                    {
                        "id": group.id, "name": group.name, "selection_type": group.selection_type,
                        "required": group.required, "min_selection": group._effective_min(),
                        "max_selection": group._effective_max(),
                        "options": [
                            {"id": option.id, "name": option.name, "price_extra": option.price_extra}
                            for option in group.option_ids if option.active
                        ],
                    }
                    for group in product.product_tmpl_id.food_modifier_group_ids
                ],
            } for product in products]
        })

    @http.route("/food/staff/printers", type="http", auth="user", methods=["GET"])
    def staff_printers(self, role="cashier", **kwargs):
        if not self._allowed(role):
            return json_response({"error": "forbidden"}, 403)
        devices = request.env["food.printer.device"].sudo().search([
            ("target", "=", role), ("active", "=", True),
        ])
        now = fields.Datetime.now()
        cutoff = fields.Datetime.subtract(now, seconds=30)
        Job = request.env["food.print.job"].sudo()
        queued = Job.search_count([
            ("target", "=", role), ("state", "in", ["queued", "claimed"]),
        ])
        failed = Job.search_count([
            ("target", "=", role), ("state", "in", ["failed", "dead_letter"]),
        ])
        online = devices.filtered(lambda device: device.last_seen_at and device.last_seen_at >= cutoff)
        return json_response({
            "state": "online" if online else "offline",
            "online_devices": len(online),
            "device_count": len(devices),
            "queued": queued,
            "failed": failed,
            "last_seen_at": max((device.last_seen_at for device in devices if device.last_seen_at), default=False),
        })

    @http.route("/food/staff/print/jobs/<int:job_id>/retry", type="http", auth="user", methods=["POST"])
    def staff_retry_print(self, job_id, role="cashier", **post):
        if not self._allowed(role):
            return json_response({"error": "forbidden"}, 403)
        job = request.env["food.print.job"].sudo().browse(job_id).exists()
        if not job or job.target != role or job.state not in ("failed", "dead_letter"):
            return json_response({"error": "not_found"}, 404)
        job.action_retry()
        return json_response({"ok": True})

    @http.route("/food/staff/walkin", type="http", auth="user", methods=["POST"])
    def create_walkin(self, **post):
        if not self._allowed("cashier"):
            return json_response({"error": "forbidden"}, 403)
        try:
            items = json.loads(post.get("items") or "[]")
        except (TypeError, ValueError):
            return json_response({"error": "Invalid cart."}, 400)
        if not items:
            return json_response({"error": "Add at least one item."}, 400)
        idempotency_key = post.get("idempotency_key") or fc.idempotency_key()
        order = None
        created = False
        try:
            order, created = request.env["sale.order"]._food_create_guest_order(
                name=post.get("customer_name") or "Walk-in Customer",
                phone=post.get("phone") or "Walk-in",
                department="Counter", floor="Walk-in", note=post.get("order_note", ""),
                items=items, source="cashier", idempotency_key=idempotency_key,
            )
            if created:
                order.action_food_record_payment(
                    "cash", post.get("amount_received") or 0, False, actor_user=request.env.user,
                )
                order.action_food_accept(actor_user=request.env.user)
        except (UserError, ValidationError) as error:
            # A walk-in draft has no customer waiting on a tracking link, so
            # a half-created order (created but not payable/acceptable, e.g.
            # no open cash session) must not linger in the queue — remove it
            # and let the cashier retry cleanly instead of leaving a stray
            # unpaid "pending" order for someone else to find later.
            if order and created:
                order.unlink()
            return json_response({"error": str(error)}, 409)
        return json_response({"order": self._serialize(order)}, 201 if created else 200)

    @http.route("/food/staff/orders/<int:order_id>/payment", type="http", auth="user", methods=["POST"])
    def collect_payment(self, order_id, **post):
        if not self._allowed("cashier"):
            return json_response({"error": "forbidden"}, 403)
        order = request.env["sale.order"].sudo().browse(order_id).exists()
        if not order or not order.food_order:
            return json_response({"error": "not_found"}, 404)
        error_response = self._run_action(lambda: order.action_food_record_payment(
            "cash", post.get("amount_received") or 0, False, actor_user=request.env.user,
            request_id=post.get("request_id") or None,
            expected_version=post.get("expected_version") or None,
        ))
        if error_response:
            return error_response
        return json_response({"order": self._serialize(order)})

    @http.route("/food/staff/orders/<int:order_id>/refund", type="http", auth="user", methods=["POST"])
    def refund_order(self, order_id, **post):
        if not self._allowed("cashier"):
            return json_response({"error": "forbidden"}, 403)
        order = request.env["sale.order"].sudo().browse(order_id).exists()
        if not order or not order.food_order:
            return json_response({"error": "not_found"}, 404)
        error_response = self._run_action(lambda: order.action_food_refund(
            post.get("amount") or order.amount_total, post.get("reason"),
            actor_user=request.env.user, expected_version=post.get("expected_version") or None,
        ))
        if error_response:
            return error_response
        return json_response({"order": self._serialize(order)})

    @http.route(
        "/food/staff/orders/<int:order_id>/<string:action>",
        type="http",
        auth="user",
        methods=["POST"],
    )
    def staff_action(self, order_id, action, role="cashier", **kwargs):
        if not self._allowed(role):
            return json_response({"error": "forbidden"}, 403)
        order = request.env["sale.order"].sudo().browse(order_id).exists()
        if not order or not order.food_order:
            return json_response({"error": "not_found"}, 404)
        request_id = kwargs.get("request_id") or None
        expected_version = kwargs.get("expected_version") or None
        actions = {
            "accept": ("cashier", order.action_food_accept),
            "complete": ("cashier", order.action_food_complete),
            "prepare": ("kitchen", order.action_food_prepare),
            "ready": ("kitchen", order.action_food_ready),
        }
        if action == "cancel":
            error_response = self._run_action(lambda: order.action_food_cancel(
                reason=kwargs.get("reason"), actor_user=request.env.user,
                request_id=request_id, expected_version=expected_version,
            ))
            if error_response:
                return error_response
            return json_response({"order": self._serialize(order)})
        if action == "reprint":
            error_response = self._run_action(lambda: order.action_food_reprint(role, actor_user=request.env.user))
            if error_response:
                return error_response
            return json_response({"order": self._serialize(order)})
        if action not in actions or actions[action][0] != role:
            return json_response({"error": "action_not_allowed"}, 403)
        error_response = self._run_action(lambda: actions[action][1](
            actor_user=request.env.user, request_id=request_id, expected_version=expected_version,
        ))
        if error_response:
            return error_response
        return json_response({"order": self._serialize(order)})

    # ------------------------------------------------------------------
    # Cashier shift / cash session
    # ------------------------------------------------------------------
    def _serialize_session(self, session):
        return {
            "id": session.id, "opening_cash": session.opening_cash,
            "sale_total": session.sale_total, "refund_total": session.refund_total,
            "cash_in_total": session.cash_in_total, "cash_out_total": session.cash_out_total,
            "closing_cash_expected": session.closing_cash_expected,
            "opened_at": fields.Datetime.to_string(session.opened_at),
            "opened_by": session.opened_by_user_id.name or "",
        }

    @http.route("/food/staff/session/current", type="http", auth="user", methods=["GET"])
    def session_current(self, **kwargs):
        if not self._allowed("cashier"):
            return json_response({"error": "forbidden"}, 403)
        session = request.env["food.cash.session"].sudo().search(
            [("state", "=", "open"), ("opened_by_user_id", "=", request.env.user.id)], limit=1
        )
        if not session:
            return json_response({"session": None})
        return json_response({"session": self._serialize_session(session)})

    @http.route("/food/staff/session/open", type="http", auth="user", methods=["POST"])
    def session_open(self, **post):
        if not self._allowed("cashier"):
            return json_response({"error": "forbidden"}, 403)
        existing = request.env["food.cash.session"].sudo().search(
            [("state", "=", "open"), ("opened_by_user_id", "=", request.env.user.id)], limit=1
        )
        if existing:
            return json_response({"error": "A cash session is already open."}, 409)
        try:
            opening_cash = float(post.get("opening_cash") or 0)
        except (TypeError, ValueError):
            opening_cash = 0.0
        session = request.env["food.cash.session"].sudo().create({
            "opened_by_user_id": request.env.user.id, "opening_cash": opening_cash,
        })
        return json_response({"session": self._serialize_session(session)}, 201)

    @http.route("/food/staff/session/movement", type="http", auth="user", methods=["POST"])
    def session_movement(self, **post):
        if not self._allowed("cashier"):
            return json_response({"error": "forbidden"}, 403)
        session = request.env["food.cash.session"].sudo().search(
            [("state", "=", "open"), ("opened_by_user_id", "=", request.env.user.id)], limit=1
        )
        if not session:
            return json_response({"error": "No open cash session."}, 404)
        movement_type = post.get("movement_type")
        if movement_type not in ("cash_in", "cash_out"):
            return json_response({"error": "Invalid movement type."}, 400)
        try:
            amount = float(post.get("amount") or 0)
        except (TypeError, ValueError):
            amount = 0.0
        if amount <= 0:
            return json_response({"error": "Enter an amount greater than zero."}, 400)
        signed_amount = amount if movement_type == "cash_in" else -amount
        request.env["food.cash.movement"].sudo().create({
            "session_id": session.id, "movement_type": movement_type, "amount": signed_amount,
            "reason": (post.get("reason") or "")[:200],
        })
        return json_response({"session": self._serialize_session(session)})

    @http.route("/food/staff/session/close", type="http", auth="user", methods=["POST"])
    def session_close(self, **post):
        if not self._allowed("cashier"):
            return json_response({"error": "forbidden"}, 403)
        session = request.env["food.cash.session"].sudo().search(
            [("state", "=", "open"), ("opened_by_user_id", "=", request.env.user.id)], limit=1
        )
        if not session:
            return json_response({"error": "No open cash session."}, 404)
        try:
            closing_actual = float(post.get("closing_cash_actual") or 0)
        except (TypeError, ValueError):
            closing_actual = 0.0
        session.action_close(closing_actual, closed_by_user=request.env.user)
        return json_response({
            "session_id": session.id, "expected": session.closing_cash_expected,
            "actual": session.closing_cash_actual, "difference": session.difference,
        })
