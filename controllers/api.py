import json
import logging

from werkzeug.wrappers import Response

from odoo import fields, http
from odoo.exceptions import UserError, ValidationError
from odoo.http import request

from . import food_common as fc
from ..models.food_order import StaleVersionError

_logger = logging.getLogger(__name__)

ORDER_CREATE_IP_LIMIT = (20, 3600)      # 20 orders / hour / IP
ORDER_CREATE_PHONE_LIMIT = (10, 3600)   # 10 orders / hour / phone
PAIRING_CLAIM_IP_LIMIT = (10, 3600)


def json_response(data, status=200):
    return Response(
        json.dumps(data, ensure_ascii=False, default=str),
        status=status,
        content_type="application/json; charset=utf-8",
    )


class FoodOrderingAPI(http.Controller):
    def _rate_limited(self, bucket, identifier, limit_window):
        limit, window = limit_window
        return fc.rate_limited(bucket, identifier, limit, window)

    def _device(self):
        key = request.httprequest.headers.get("X-Device-Key", "")
        device = request.env["food.printer.device"].sudo()._find_by_raw_key(key)
        if not device:
            self._rate_limited("device_auth_fail", fc.client_ip(), (30, 3600))
            _logger.warning("food_ordering: device auth failed from ip=%s", fc.client_ip())
        return device

    def _run_action(self, callback):
        """Runs a state-changing callback, normalizing StaleVersionError to
        a distinct error_code the Android client can react to (refresh just
        that order) separately from an ordinary rejected action. Mirrors
        staff.py's _run_action so both the web and device-key surfaces give
        callers the same conflict-handling contract."""
        try:
            callback()
        except StaleVersionError as error:
            return json_response({"error": str(error), "error_code": "stale_version"}, 409)
        except UserError as error:
            return json_response({"error": str(error)}, 409)
        return None

    def _serialize_order(self, order):
        now = fields.Datetime.now()
        created = order.create_date or now
        return {
            "id": order.id,
            "number": order.name,
            "status": order.food_status,
            "state_version": order.food_state_version,
            "source": order.food_order_source,
            # {"cashier": "printed", "kitchen": "queued", ...} — same shape as
            # staff.py's _serialize, so the Android Cashier order card can show
            # per-target print status the way the web Cashier card already does.
            "print_jobs": {job.target: job.state for job in order.food_print_job_ids},
            "elapsed_minutes": max(0, int((now - created).total_seconds() // 60)),
            "payment_status": order.food_payment_status,
            "payment_method": order.food_payment_method or "",
            "payment_reference": order.food_payment_reference or "",
            "amount_received": order.food_amount_received,
            "change_amount": order.food_change_amount,
            "promotion": order.food_promotion_id.name or "",
            "accepted_at": fields.Datetime.to_string(order.food_accepted_at) if order.food_accepted_at else False,
            "ready_at": fields.Datetime.to_string(order.food_ready_at) if order.food_ready_at else False,
            "customer": {
                "name": order.food_customer_name,
                "phone": order.food_phone,
                "department": order.food_department,
                "floor": order.food_floor,
                "note": order.food_note or "",
                "own_cup": order.food_own_cup,
            },
            "lines": [
                {
                    "product_id": line.product_id.id,
                    "name": line.product_id.display_name or line.name,
                    "quantity": line.product_uom_qty,
                    "own_cup_quantity": line.food_own_cup_quantity,
                    "note": line.food_line_note or "",
                    "modifiers": line.food_modifier_summary or "",
                    "modifier_details": [
                        {"group": m.group_name, "option": m.option_name, "price_extra": m.price_extra}
                        for m in line.food_modifier_ids
                    ],
                    "subtotal": line.price_subtotal,
                }
                for line in order.order_line.filtered(
                    lambda item: not item.display_type and item.price_unit >= 0
                )
            ],
            "total": order.amount_total,
            "currency": order.currency_id.name,
            "created_at": order.create_date,
        }

    @http.route("/api/food/v1/catalog", type="http", auth="public", csrf=False, methods=["GET"])
    def catalog(self):
        products = request.env["product.product"].sudo().search(
            [("product_tmpl_id.food_available_online", "=", True), ("sale_ok", "=", True)]
        )
        return json_response(
            {
                "products": [
                    {
                        "id": product.id,
                        "name": product.display_name,
                        "price": product.lst_price,
                        "currency": request.env.company.currency_id.name,
                        "own_cup_eligible": product.product_tmpl_id.food_own_cup_eligible,
                        "modifier_groups": [
                            {
                                "id": group.id,
                                "name": group.name,
                                "selection_type": group.selection_type,
                                "required": group.required,
                                "min_selection": group._effective_min(),
                                "max_selection": group._effective_max(),
                                "options": [
                                    {"id": option.id, "name": option.name, "price_extra": option.price_extra}
                                    for option in group.option_ids if option.active
                                ],
                            }
                            for group in product.product_tmpl_id.food_modifier_group_ids
                        ],
                    }
                    for product in products
                ],
                "promotion_banner": self._promotion_banner(),
            }
        )

    def _promotion_banner(self):
        promotion = request.env["food.promotion"].sudo()._get_active_banner()
        if not promotion:
            return None
        return {
            "headline": promotion.banner_headline or promotion.name,
            "subtext": promotion.banner_subtext or "",
        }

    @http.route("/api/food/v1/orders", type="http", auth="public", csrf=False, methods=["POST"])
    def create_order(self):
        ip = fc.client_ip()
        if not self._rate_limited("order_create_ip", ip, ORDER_CREATE_IP_LIMIT):
            return json_response({"error": "rate_limited"}, 429)
        body = fc.body_json()
        if body is None:
            return json_response({"error": "request_too_large"}, 413)
        required = ("name", "phone", "department", "floor")
        if any(not str(body.get(field, "")).strip() for field in required):
            return json_response({"error": "missing_customer_information"}, 400)
        phone = str(body["phone"]).strip()[:32]
        if not self._rate_limited("order_create_phone", phone, ORDER_CREATE_PHONE_LIMIT):
            return json_response({"error": "rate_limited"}, 429)
        raw_items = body.get("items")
        if not isinstance(raw_items, list) or not raw_items:
            return json_response({"error": "empty_order"}, 400)

        idempotency_key = fc.idempotency_key(body)
        website = request.env["website"].sudo().get_current_website()
        try:
            order, created = request.env["sale.order"].sudo()._food_create_guest_order(
                name=body["name"], phone=body["phone"], department=body["department"],
                floor=body["floor"], note=body.get("note", ""), items=raw_items,
                source="android", company=website.company_id, idempotency_key=idempotency_key,
            )
        except (UserError, ValidationError) as error:
            return json_response({"error": str(error)}, 400)
        return json_response(
            {
                "order": self._serialize_order(order),
                "access_token": order.food_access_token,
                "status_url": "/api/food/v1/orders/%s" % order.food_access_token,
                "created": created,
            },
            201 if created else 200,
        )

    @http.route(
        "/api/food/v1/orders/<string:token>",
        type="http",
        auth="public",
        csrf=False,
        methods=["GET"],
    )
    def order_status(self, token):
        ip = fc.client_ip()
        if not self._rate_limited("order_status_ip", ip, (120, 3600)):
            return json_response({"error": "rate_limited"}, 429)
        order = request.env["sale.order"].sudo().search(
            [("food_order", "=", True), ("food_access_token", "=", token)], limit=1
        )
        if not order:
            return json_response({"error": "not_found"}, 404)
        return json_response({"order": self._serialize_order(order)})

    # ------------------------------------------------------------------
    # Device pairing (Phase 7/8 enrollment) — no Odoo account needed.
    # ------------------------------------------------------------------
    @http.route("/api/food/v1/pairing/claim", type="http", auth="public", csrf=False, methods=["POST"])
    def pairing_claim(self):
        ip = fc.client_ip()
        if not self._rate_limited("pairing_claim_ip", ip, PAIRING_CLAIM_IP_LIMIT):
            return json_response({"error": "rate_limited"}, 429)
        body = fc.body_json()
        if body is None:
            return json_response({"error": "request_too_large"}, 413)
        code = str(body.get("code", "")).strip().upper()[:16]
        if not code:
            return json_response({"error": "missing_code"}, 400)
        pairing = request.env["food.device.pairing"].sudo().search([("code", "=", code)], limit=1)
        if not pairing:
            self._rate_limited("pairing_claim_fail", ip, (10, 3600))
            return json_response({"error": "invalid_code"}, 404)
        try:
            device, raw_key = pairing._claim(
                device_name=str(body.get("device_name", ""))[:120],
                device_uuid=str(body.get("device_uuid", ""))[:120],
                app_version=str(body.get("app_version", ""))[:32],
            )
        except UserError as error:
            return json_response({"error": str(error)}, 409)
        return json_response({
            "device_id": device.id,
            "device_key": raw_key,
            "role": device.target,
            "name": device.name,
        }, 201)

    @http.route(
        "/api/food/v1/staff/orders", type="http", auth="public", csrf=False, methods=["GET"]
    )
    def staff_orders(self):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        requested_status = request.httprequest.args.get("status", "pending")
        allowed_statuses = {"pending", "accepted", "preparing", "ready", "completed", "cancelled"}
        domain = [("food_order", "=", True)]
        order_by = "create_date asc"
        limit = 100
        if requested_status == "active":
            domain.append(("food_status", "in", ["pending", "accepted", "preparing", "ready"]))
        elif requested_status == "recent":
            domain.append(("food_status", "in", ["completed", "cancelled"]))
            order_by = "create_date desc"
            limit = 50
        elif requested_status in allowed_statuses:
            domain.append(("food_status", "=", requested_status))
        else:
            return json_response({"error": "invalid_status"}, 400)
        since_version = request.httprequest.args.get("since_version")
        orders = request.env["sale.order"].sudo().search(domain, order=order_by, limit=limit)
        if since_version:
            try:
                since_version = int(since_version)
                orders = orders.filtered(lambda order: order.food_state_version > since_version)
            except ValueError:
                pass
        device.write({"last_seen_at": fields.Datetime.now(), "last_ip": fc.client_ip()})
        return json_response({"orders": [self._serialize_order(order) for order in orders]})

    @http.route(
        "/api/food/v1/staff/orders/<int:order_id>/<string:action>",
        type="http",
        auth="public",
        csrf=False,
        methods=["POST"],
    )
    def staff_order_action(self, order_id, action):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        order = request.env["sale.order"].sudo().browse(order_id).exists()
        if not order or not order.food_order:
            return json_response({"error": "not_found"}, 404)
        body = fc.body_json() or {}
        request_id = fc.request_id(body)
        expected_version = body.get("expected_version")
        if action == "reprint":
            error_response = self._run_action(
                lambda: order.action_food_reprint(device.target, actor_device=device)
            )
            if error_response:
                return error_response
            return json_response({"order": self._serialize_order(order)})
        if action == "refund":
            error_response = self._run_action(lambda: order.action_food_refund(
                body.get("amount") or order.amount_total, body.get("reason"),
                manager_pin=body.get("manager_pin"), actor_device=device,
                expected_version=expected_version,
            ))
            if error_response:
                return error_response
            return json_response({"order": self._serialize_order(order)})
        actions = {
            "accept": ("cashier", order.action_food_accept),
            "prepare": ("kitchen", order.action_food_prepare),
            "ready": ("kitchen", order.action_food_ready),
            "complete": ("cashier", order.action_food_complete),
            "cancel": ("cashier", order.action_food_cancel),
        }
        if action not in actions or device.target != actions[action][0]:
            return json_response({"error": "action_not_allowed"}, 403)
        if action == "cancel":
            error_response = self._run_action(lambda: actions[action][1](
                reason=body.get("reason"), manager_pin=body.get("manager_pin"),
                actor_device=device, request_id=request_id, expected_version=expected_version,
            ))
        else:
            error_response = self._run_action(lambda: actions[action][1](
                actor_device=device, request_id=request_id, expected_version=expected_version,
            ))
        if error_response:
            return error_response
        return json_response({"order": self._serialize_order(order)})

    @http.route(
        "/api/food/v1/staff/catalog", type="http", auth="public", csrf=False, methods=["GET"]
    )
    def staff_catalog(self):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        if device.target != "cashier":
            return json_response({"error": "action_not_allowed"}, 403)
        products = request.env["product.product"].sudo().search(
            [("sale_ok", "=", True), ("product_tmpl_id.food_available_online", "=", True)],
            order="name asc",
        )
        return json_response({
            "own_cup_discount": request.env["sale.order"]._food_own_cup_discount_amount(),
            "promotion_banner": self._promotion_banner(),
            "products": [{
                "id": product.id,
                "name": product.display_name,
                "price": product.lst_price,
                "currency": product.currency_id.name,
                "category": "drinks" if product.product_tmpl_id.food_is_beverage else "meals",
                "own_cup_eligible": product.product_tmpl_id.food_own_cup_eligible,
                "image_url": "/web/image/product.product/%s/image_256" % product.id,
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
            } for product in products],
        })

    @http.route(
        "/api/food/v1/staff/walkin", type="http", auth="public", csrf=False, methods=["POST"]
    )
    def staff_walkin(self):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        if device.target != "cashier":
            return json_response({"error": "action_not_allowed"}, 403)
        body = fc.body_json()
        if body is None:
            return json_response({"error": "request_too_large"}, 413)
        items = body.get("items") if isinstance(body.get("items"), list) else []
        if not items:
            return json_response({"error": "Add at least one item."}, 400)
        idempotency_key = fc.idempotency_key(body)
        order = None
        created = False
        try:
            order, created = request.env["sale.order"].sudo()._food_create_guest_order(
                name=body.get("customer_name") or "Walk-in Customer",
                phone=body.get("phone") or "Walk-in",
                department="Counter", floor="Walk-in", note=body.get("order_note", ""),
                items=items, source="cashier", idempotency_key=idempotency_key,
            )
            if created:
                order.action_food_record_payment(
                    "cash", body.get("amount_received") or 0, False, actor_device=device,
                )
                order.action_food_accept(actor_device=device)
        except (UserError, ValidationError) as error:
            # No customer is waiting on this walk-in draft; don't leave a
            # stray unpaid "pending" order behind when payment/accept fails
            # after creation (e.g. no open cash session).
            if order and created:
                order.unlink()
            return json_response({"error": str(error)}, 409)
        return json_response({"order": self._serialize_order(order)}, 201 if created else 200)

    @http.route(
        "/api/food/v1/staff/orders/<int:order_id>/payment",
        type="http", auth="public", csrf=False, methods=["POST"],
    )
    def staff_payment(self, order_id):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        if device.target != "cashier":
            return json_response({"error": "action_not_allowed"}, 403)
        order = request.env["sale.order"].sudo().browse(order_id).exists()
        if not order or not order.food_order:
            return json_response({"error": "not_found"}, 404)
        body = fc.body_json() or {}
        error_response = self._run_action(lambda: order.action_food_record_payment(
            "cash", body.get("amount_received") or 0, False,
            actor_device=device, request_id=fc.request_id(body),
            expected_version=body.get("expected_version"),
        ))
        if error_response:
            return error_response
        return json_response({"order": self._serialize_order(order)})

    @http.route("/api/food/v1/staff/config", type="http", auth="public", csrf=False, methods=["GET"])
    def staff_config(self):
        # Lets the Android app read the same SLA thresholds the Kitchen web
        # UI renders server-side (staff.py staff_screen) — there was
        # previously no JSON way to reach these ir.config_parameter values.
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        Params = request.env["ir.config_parameter"].sudo()
        return json_response({
            "kitchen_sla_warn_minutes": int(Params.get_param("food_ordering.kitchen_sla_warn_minutes", "10")),
            "kitchen_sla_late_minutes": int(Params.get_param("food_ordering.kitchen_sla_late_minutes", "20")),
        })

    @http.route(
        "/api/food/v1/staff/orders/<int:order_id>/events",
        type="http", auth="public", csrf=False, methods=["GET"],
    )
    def device_order_events(self, order_id):
        # Mirrors staff.py's order_events (web/Odoo-session auth) so the
        # Android Cashier app's Completed/Recent event timeline has a
        # device-key-authenticated equivalent to call — the Android client
        # has no Odoo session and cannot reach the web-only route.
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
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

    # ------------------------------------------------------------------
    # Print queue
    # ------------------------------------------------------------------
    @http.route("/api/food/v1/print/jobs", type="http", auth="public", csrf=False, methods=["GET"])
    def print_jobs(self):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        device.write({"last_seen_at": fields.Datetime.now(), "last_ip": fc.client_ip()})
        Job = request.env["food.print.job"].sudo()
        stale_cutoff = fields.Datetime.subtract(fields.Datetime.now(), minutes=5)
        Job.search(
            [("target", "=", device.target), ("state", "=", "claimed"), ("claimed_at", "<", stale_cutoff)]
        ).write({"state": "queued", "device_id": False, "claimed_at": False})
        # Claim with row locks so an Android bridge and a local Node agent polling
        # the same target cannot both receive and print the same queued job.
        request.env.cr.execute(
            """
                SELECT id
                  FROM food_print_job
                 WHERE target = %s AND state = 'queued'
                 ORDER BY create_date ASC, id ASC
                 FOR UPDATE SKIP LOCKED
                 LIMIT 10
            """,
            [device.target],
        )
        jobs = Job.browse([row[0] for row in request.env.cr.fetchall()])
        for job in jobs:
            job.write(
                {
                    "state": "claimed",
                    "device_id": device.id,
                    "claimed_at": fields.Datetime.now(),
                    "attempts": job.attempts + 1,
                }
            )
        return json_response(
            {
                "jobs": [
                    {
                        "id": job.id, "payload": json.loads(job.payload), "printer": device.printer_address,
                        "paper_width_mm": device.paper_width_mm, "cutter_enabled": device.cutter_enabled,
                        "encoding": device.encoding, "template_version": job.template_version,
                    }
                    for job in jobs
                ]
            }
        )

    @http.route("/api/food/v1/print/jobs/failed", type="http", auth="public", csrf=False, methods=["GET"])
    def failed_print_jobs(self):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        jobs = request.env["food.print.job"].sudo().search(
            [("target", "=", device.target), ("state", "in", ["failed", "dead_letter"])],
            order="create_date asc",
            limit=20,
        )
        return json_response({
            "jobs": [
                {
                    "id": job.id, "payload": json.loads(job.payload), "printer": device.printer_address,
                    "state": job.state, "paper_width_mm": device.paper_width_mm,
                    "cutter_enabled": device.cutter_enabled, "encoding": device.encoding,
                    "template_version": job.template_version,
                }
                for job in jobs
            ]
        })

    @http.route(
        "/api/food/v1/print/jobs/<int:job_id>/retry",
        type="http", auth="public", csrf=False, methods=["POST"],
    )
    def retry_print_job(self, job_id):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        job = request.env["food.print.job"].sudo().browse(job_id).exists()
        if not job or job.target != device.target or job.state not in ("failed", "dead_letter"):
            return json_response({"error": "not_found"}, 404)
        job.action_retry()
        return json_response({"ok": True})

    @http.route(
        "/api/food/v1/print/jobs/<int:job_id>/ack",
        type="http",
        auth="public",
        csrf=False,
        methods=["POST"],
    )
    def print_ack(self, job_id):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        job = request.env["food.print.job"].sudo().browse(job_id).exists()
        if not job or job.device_id != device:
            return json_response({"error": "not_found"}, 404)
        body = fc.body_json() or {}
        success = bool(body.get("success"))
        job.action_ack(success, body.get("error", "Printing failed"), actor_device=device)
        return json_response({"ok": True})

    @http.route("/api/food/v1/print/test", type="http", auth="public", csrf=False, methods=["POST"])
    def print_test(self):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        payload = {
            "order_id": 0, "order_number": "TEST", "target": device.target, "status": "test",
            "source": "test", "customer": {"name": "Test Print", "phone": "", "department": "",
                                            "floor": "", "note": "", "own_cup": False},
            "lines": [{"name": "Test line", "quantity": 1, "unit_price": 0, "subtotal": 0,
                       "own_cup_eligible": False, "own_cup": False, "own_cup_quantity": 0,
                       "modifiers": "", "modifier_details": [], "note": ""}],
            "promotion": "", "amount_total": 0, "currency": request.env.company.currency_id.name,
            "payment": {"status": "unpaid", "method": "", "reference": "", "received": 0, "change": 0},
        }
        return json_response({
            "payload": payload, "printer": device.printer_address,
            "paper_width_mm": device.paper_width_mm, "cutter_enabled": device.cutter_enabled,
            "encoding": device.encoding,
        })

    # ------------------------------------------------------------------
    # Cashier shift / cash session — device-key auth, for the Android
    # Cashier app which has no Odoo user login (staff.py's session
    # endpoints are for the authenticated web Cashier screen instead).
    # ------------------------------------------------------------------
    def _serialize_session(self, session):
        if not session:
            return None
        return {
            "id": session.id, "opening_cash": session.opening_cash,
            "sale_total": session.sale_total, "refund_total": session.refund_total,
            "cash_in_total": session.cash_in_total, "cash_out_total": session.cash_out_total,
            "closing_cash_expected": session.closing_cash_expected,
            "opened_at": session.opened_at,
        }

    @http.route("/api/food/v1/staff/session/current", type="http", auth="public", csrf=False, methods=["GET"])
    def device_session_current(self):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        if device.target != "cashier":
            return json_response({"error": "action_not_allowed"}, 403)
        session = request.env["sale.order"]._food_find_open_cash_session(actor_device=device)
        return json_response({"session": self._serialize_session(session)})

    @http.route("/api/food/v1/staff/session/open", type="http", auth="public", csrf=False, methods=["POST"])
    def device_session_open(self):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        if device.target != "cashier":
            return json_response({"error": "action_not_allowed"}, 403)
        existing = request.env["sale.order"]._food_find_open_cash_session(actor_device=device)
        if existing:
            return json_response({"error": "A cash session is already open for this device."}, 409)
        body = fc.body_json() or {}
        try:
            opening_cash = float(body.get("opening_cash") or 0)
        except (TypeError, ValueError):
            opening_cash = 0.0
        session = request.env["food.cash.session"].sudo().create({
            "opened_by_device_id": device.id, "opening_cash": opening_cash,
        })
        return json_response({"session": self._serialize_session(session)}, 201)

    @http.route("/api/food/v1/staff/session/movement", type="http", auth="public", csrf=False, methods=["POST"])
    def device_session_movement(self):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        if device.target != "cashier":
            return json_response({"error": "action_not_allowed"}, 403)
        session = request.env["sale.order"]._food_find_open_cash_session(actor_device=device)
        if not session:
            return json_response({"error": "No open cash session for this device."}, 404)
        body = fc.body_json() or {}
        movement_type = body.get("movement_type")
        if movement_type not in ("cash_in", "cash_out"):
            return json_response({"error": "Invalid movement type."}, 400)
        try:
            amount = float(body.get("amount") or 0)
        except (TypeError, ValueError):
            amount = 0.0
        if amount <= 0:
            return json_response({"error": "Enter an amount greater than zero."}, 400)
        signed_amount = amount if movement_type == "cash_in" else -amount
        request.env["food.cash.movement"].sudo().create({
            "session_id": session.id, "movement_type": movement_type, "amount": signed_amount,
            "reason": str(body.get("reason") or "")[:200],
        })
        return json_response({"session": self._serialize_session(session)})

    @http.route("/api/food/v1/staff/session/close", type="http", auth="public", csrf=False, methods=["POST"])
    def device_session_close(self):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        if device.target != "cashier":
            return json_response({"error": "action_not_allowed"}, 403)
        session = request.env["sale.order"]._food_find_open_cash_session(actor_device=device)
        if not session:
            return json_response({"error": "No open cash session for this device."}, 404)
        body = fc.body_json() or {}
        try:
            closing_actual = float(body.get("closing_cash_actual") or 0)
        except (TypeError, ValueError):
            closing_actual = 0.0
        session.action_close(closing_actual, closed_by_device=device)
        return json_response({
            "session_id": session.id, "expected": session.closing_cash_expected,
            "actual": session.closing_cash_actual, "difference": session.difference,
        })
