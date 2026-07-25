import json

from werkzeug.wrappers import Response

from odoo import fields, http
from odoo.exceptions import UserError
from odoo.http import request


def json_response(data, status=200):
    return Response(
        json.dumps(data, ensure_ascii=False, default=str),
        status=status,
        content_type="application/json; charset=utf-8",
    )


class FoodOrderingAPI(http.Controller):
    def _body(self):
        try:
            return json.loads(request.httprequest.get_data(as_text=True) or "{}")
        except json.JSONDecodeError:
            return {}

    def _device(self):
        key = request.httprequest.headers.get("X-Device-Key", "")
        return request.env["food.printer.device"].sudo().search(
            [("device_key", "=", key), ("active", "=", True)], limit=1
        )

    def _discount_amount(self):
        raw_value = request.env["ir.config_parameter"].sudo().get_param(
            "food_ordering.own_cup_discount", "500"
        )
        try:
            return max(0.0, float(raw_value))
        except ValueError:
            return 0.0

    def _serialize_order(self, order):
        now = fields.Datetime.now()
        created = order.create_date or now
        return {
            "id": order.id,
            "number": order.name,
            "status": order.food_status,
            "source": order.food_order_source,
            "elapsed_minutes": max(0, int((now - created).total_seconds() // 60)),
            "payment_status": order.food_payment_status,
            "payment_method": order.food_payment_method or "",
            "payment_reference": order.food_payment_reference or "",
            "amount_received": order.food_amount_received,
            "change_amount": order.food_change_amount,
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
                    "name": line.name,
                    "quantity": line.product_uom_qty,
                    "own_cup_quantity": line.food_own_cup_quantity,
                    "note": line.food_line_note or "",
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
                    }
                    for product in products
                ]
            }
        )

    @http.route("/api/food/v1/orders", type="http", auth="public", csrf=False, methods=["POST"])
    def create_order(self):
        body = self._body()
        required = ("name", "phone", "department", "floor")
        if any(not str(body.get(field, "")).strip() for field in required):
            return json_response({"error": "missing_customer_information"}, 400)
        raw_items = body.get("items")
        if not isinstance(raw_items, list) or not raw_items:
            return json_response({"error": "empty_order"}, 400)

        product_quantities = {}
        product_cup_quantities = {}
        for item in raw_items:
            try:
                product_id = int(item.get("product_id"))
                quantity = max(1, min(20, int(item.get("quantity", 1))))
            except (AttributeError, TypeError, ValueError):
                continue
            product_quantities[product_id] = product_quantities.get(product_id, 0) + quantity
            requested_cups = item.get("own_cup_quantity")
            if requested_cups is None:
                requested_cups = quantity if body.get("own_cup") else 0
            try:
                requested_cups = max(0, min(quantity, int(requested_cups)))
            except (TypeError, ValueError):
                requested_cups = 0
            product_cup_quantities[product_id] = product_cup_quantities.get(product_id, 0) + requested_cups
        products = request.env["product.product"].sudo().browse(product_quantities.keys()).exists()
        products = products.filtered(
            lambda product: product.sale_ok and product.product_tmpl_id.food_available_online
        )
        if not products:
            return json_response({"error": "no_valid_products"}, 400)

        Partner = request.env["res.partner"].sudo()
        phone = str(body["phone"]).strip()
        partner = Partner.search([("phone", "=", phone)], limit=1)
        partner_values = {
            "name": str(body["name"]).strip(),
            "phone": phone,
            "comment": "Department: %s\nFloor: %s" % (body["department"], body["floor"]),
        }
        if partner:
            partner.write(partner_values)
        else:
            partner = Partner.create(partner_values)

        website = request.env["website"].sudo().get_current_website()
        order = request.env["sale.order"].sudo().create(
            {
                "partner_id": partner.id,
                "pricelist_id": partner.property_product_pricelist.id,
                "company_id": website.company_id.id,
                "food_order": True,
                "food_status": "pending",
                "food_order_source": "android",
                "food_customer_name": partner_values["name"],
                "food_phone": phone,
                "food_department": str(body["department"]).strip(),
                "food_floor": str(body["floor"]).strip(),
                "food_note": str(body.get("note", "")).strip(),
                "food_own_cup": False,
            }
        )
        eligible_quantity = 0
        for product in products:
            quantity = product_quantities[product.id]
            own_cup_quantity = min(quantity, product_cup_quantities.get(product.id, 0)) if product.product_tmpl_id.food_own_cup_eligible else 0
            request.env["sale.order.line"].sudo().create(
                {"order_id": order.id, "product_id": product.id, "product_uom_qty": quantity,
                 "food_own_cup": own_cup_quantity > 0, "food_own_cup_quantity": own_cup_quantity}
            )
            eligible_quantity += own_cup_quantity
        if eligible_quantity:
            order.food_own_cup = True
            request.env["sale.order.line"].sudo().create(
                {
                    "order_id": order.id,
                    "product_id": request.env.ref("food_ordering.product_own_cup_discount").id,
                    "product_uom_qty": eligible_quantity,
                    "price_unit": -self._discount_amount(),
                }
            )
        return json_response(
            {
                "order": self._serialize_order(order),
                "access_token": order.food_access_token,
                "status_url": "/api/food/v1/orders/%s" % order.food_access_token,
            },
            201,
        )

    @http.route(
        "/api/food/v1/orders/<string:token>",
        type="http",
        auth="public",
        csrf=False,
        methods=["GET"],
    )
    def order_status(self, token):
        order = request.env["sale.order"].sudo().search(
            [("food_order", "=", True), ("food_access_token", "=", token)], limit=1
        )
        if not order:
            return json_response({"error": "not_found"}, 404)
        return json_response({"order": self._serialize_order(order)})

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
        orders = request.env["sale.order"].sudo().search(domain, order=order_by, limit=limit)
        device.last_seen_at = fields.Datetime.now()
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
        if action == "reprint":
            try:
                order.action_food_reprint(device.target)
            except UserError as error:
                return json_response({"error": str(error)}, 409)
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
        try:
            actions[action][1]()
        except UserError as error:
            return json_response({"error": str(error)}, 409)
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
            "own_cup_discount": self._discount_amount(),
            "products": [{
                "id": product.id,
                "name": product.display_name,
                "price": product.lst_price,
                "currency": product.currency_id.name,
                "category": "drinks" if product.product_tmpl_id.food_is_beverage else "meals",
                "own_cup_eligible": product.product_tmpl_id.food_own_cup_eligible,
                "image_url": "/web/image/product.product/%s/image_256" % product.id,
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
        body = self._body()
        items = body.get("items") if isinstance(body.get("items"), list) else []
        if not items:
            return json_response({"error": "Add at least one item."}, 400)
        try:
            product_ids = [int(item.get("product_id", 0)) for item in items]
        except (TypeError, ValueError):
            return json_response({"error": "Invalid cart."}, 400)
        products = request.env["product.product"].sudo().browse(product_ids).exists().filtered(
            lambda product: product.sale_ok and product.product_tmpl_id.food_available_online
        )
        product_map = {product.id: product for product in products}
        if len(product_map) != len(set(product_ids)):
            return json_response({"error": "One or more products are unavailable."}, 400)

        customer_name = str(body.get("customer_name") or "Walk-in Customer").strip()
        phone = str(body.get("phone") or "Walk-in").strip() or "Walk-in"
        Partner = request.env["res.partner"].sudo()
        partner = Partner.search([("name", "=", customer_name)], limit=1)
        if not partner:
            partner = Partner.create({"name": customer_name, "phone": phone})
        order = request.env["sale.order"].sudo().create({
            "partner_id": partner.id,
            "pricelist_id": partner.property_product_pricelist.id,
            "company_id": request.env.company.id,
            "food_order": True,
            "food_status": "pending",
            "food_order_source": "cashier",
            "food_customer_name": customer_name,
            "food_phone": phone,
            "food_department": "Counter",
            "food_floor": "Walk-in",
            "food_note": str(body.get("order_note") or "").strip(),
        })
        discount_product = request.env.ref("food_ordering.product_own_cup_discount")
        for item in items:
            product = product_map[int(item["product_id"])]
            quantity = max(1, min(20, int(item.get("quantity") or 1)))
            own_cup_quantity = max(0, min(quantity, int(item.get("own_cup_quantity") or 0))) \
                if product.product_tmpl_id.food_own_cup_eligible else 0
            request.env["sale.order.line"].sudo().create({
                "order_id": order.id,
                "product_id": product.id,
                "product_uom_qty": quantity,
                "price_unit": product.lst_price,
                "food_own_cup": own_cup_quantity > 0,
                "food_own_cup_quantity": own_cup_quantity,
                "food_line_note": str(item.get("note") or "").strip(),
            })
            if own_cup_quantity:
                request.env["sale.order.line"].sudo().create({
                    "order_id": order.id,
                    "product_id": discount_product.id,
                    "product_uom_qty": own_cup_quantity,
                    "price_unit": -self._discount_amount(),
                    "name": "Own cup discount",
                })
        try:
            order.action_food_record_payment(
                "cash", body.get("amount_received") or 0, False,
            )
            order.action_food_accept()
        except (UserError, ValueError) as error:
            order.unlink()
            return json_response({"error": str(error)}, 409)
        return json_response({"order": self._serialize_order(order)}, 201)

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
        body = self._body()
        try:
            order.action_food_record_payment(
                "cash", body.get("amount_received") or 0, False,
            )
        except (UserError, ValueError) as error:
            return json_response({"error": str(error)}, 409)
        return json_response({"order": self._serialize_order(order)})

    @http.route("/api/food/v1/print/jobs", type="http", auth="public", csrf=False, methods=["GET"])
    def print_jobs(self):
        device = self._device()
        if not device:
            return json_response({"error": "unauthorized"}, 401)
        device.last_seen_at = fields.Datetime.now()
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
                    {"id": job.id, "payload": json.loads(job.payload), "printer": device.printer_address}
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
            [("target", "=", device.target), ("state", "=", "failed")],
            order="create_date asc",
            limit=20,
        )
        return json_response({
            "jobs": [
                {"id": job.id, "payload": json.loads(job.payload), "printer": device.printer_address}
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
        if not job or job.target != device.target or job.state != "failed":
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
        body = self._body()
        success = bool(body.get("success"))
        job.write(
            {
                "state": "printed" if success else "failed",
                "printed_at": fields.Datetime.now() if success else False,
                "error_message": "" if success else str(body.get("error", "Printing failed")),
            }
        )
        return json_response({"ok": True})
