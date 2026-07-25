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

    def _allowed(self, role):
        group = {
            "cashier": "food_ordering.group_food_cashier",
            "kitchen": "food_ordering.group_food_kitchen",
        }.get(role)
        return bool(group and request.env.user.has_group(group))

    def _serialize(self, order):
        now = fields.Datetime.now()
        created = order.create_date or now
        elapsed = max(0, int((now - created).total_seconds() // 60))
        print_jobs = {job.target: job.state for job in order.food_print_job_ids}
        return {
            "id": order.id,
            "number": order.name,
            "status": order.food_status,
            "source": order.food_order_source,
            "created_at": fields.Datetime.to_string(created),
            "elapsed_minutes": elapsed,
            "customer": order.food_customer_name,
            "phone": order.food_phone,
            "department": order.food_department,
            "floor": order.food_floor,
            "note": order.food_note or "",
            "total": "%.0f %s" % (order.amount_total, order.currency_id.name),
            "amount_total": order.amount_total,
            "currency": order.currency_id.name,
            "payment_status": order.food_payment_status,
            "payment_method": order.food_payment_method or "",
            "payment_reference": order.food_payment_reference or "",
            "amount_received": order.food_amount_received,
            "change_amount": order.food_change_amount,
            "print_jobs": print_jobs,
            "lines": [
                {
                    "name": line.product_id.display_name or line.name,
                    "quantity": line.product_uom_qty,
                    "note": line.food_line_note or "",
                    "own_cup": line.food_own_cup,
                    "own_cup_quantity": line.food_own_cup_quantity,
                }
                for line in order.order_line.filtered(
                    lambda item: not item.display_type and item.price_unit >= 0
                )
            ],
        }

    def _orders(self):
        return request.env["sale.order"].sudo().search(
            [
                ("food_order", "=", True),
                ("food_status", "in", ["pending", "accepted", "preparing", "ready"]),
            ],
            order="create_date asc",
            limit=150,
        )

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
        orders = [self._serialize(order) for order in self._orders()]
        return request.render(
            "food_ordering.food_staff_dashboard",
            {
                "role": role,
                "orders": orders,
                "can_switch": self._allowed("cashier") and self._allowed("kitchen"),
            },
        )

    @http.route("/food/staff/orders", type="http", auth="user", methods=["GET"])
    def staff_orders(self, role="cashier", **kwargs):
        if not self._allowed(role):
            return json_response({"error": "forbidden"}, 403)
        return json_response({"orders": [self._serialize(order) for order in self._orders()]})

    @http.route("/food/staff/products", type="http", auth="user", methods=["GET"])
    def staff_products(self, **kwargs):
        if not self._allowed("cashier"):
            return json_response({"error": "forbidden"}, 403)
        products = request.env["product.product"].sudo().search(
            [("sale_ok", "=", True), ("product_tmpl_id.food_available_online", "=", True)],
            order="name asc",
        )
        discount = float(request.env["ir.config_parameter"].sudo().get_param(
            "food_ordering.own_cup_discount", "500"
        ))
        return json_response({
            "own_cup_discount": discount,
            "products": [{
                "id": product.id,
                "name": product.display_name,
                "price": product.lst_price,
                "price_label": "%.0f %s" % (product.lst_price, product.currency_id.name),
                "currency": product.currency_id.name,
                "image_url": self._staff_product_image(product),
                "category": "drinks" if product.product_tmpl_id.food_is_beverage else "meals",
                "own_cup_eligible": product.product_tmpl_id.food_own_cup_eligible,
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
            ("target", "=", role), ("state", "=", "failed"),
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
        product_ids = [int(item.get("product_id", 0)) for item in items]
        products = request.env["product.product"].sudo().browse(product_ids).exists().filtered(
            lambda product: product.sale_ok and product.product_tmpl_id.food_available_online
        )
        product_map = {product.id: product for product in products}
        if len(product_map) != len(set(product_ids)):
            return json_response({"error": "One or more products are unavailable."}, 400)
        Partner = request.env["res.partner"].sudo()
        customer_name = (post.get("customer_name") or "Walk-in Customer").strip()
        partner = Partner.search([("name", "=", customer_name)], limit=1)
        if not partner:
            partner = Partner.create({"name": customer_name, "phone": (post.get("phone") or "").strip()})
        order = request.env["sale.order"].sudo().create({
            "partner_id": partner.id,
            "pricelist_id": partner.property_product_pricelist.id,
            "company_id": request.env.company.id,
            "food_order": True,
            "food_status": "pending",
            "food_order_source": "cashier",
            "food_customer_name": customer_name,
            "food_phone": (post.get("phone") or "Walk-in").strip() or "Walk-in",
            "food_department": "Counter",
            "food_floor": "Walk-in",
            "food_note": (post.get("order_note") or "").strip(),
        })
        discount_product = request.env.ref("food_ordering.product_own_cup_discount")
        discount_amount = float(request.env["ir.config_parameter"].sudo().get_param(
            "food_ordering.own_cup_discount", "500"
        ))
        for item in items:
            product = product_map[int(item["product_id"])]
            quantity = max(1, min(20, int(item.get("quantity") or 1)))
            own_cup_quantity = max(0, min(
                quantity, int(item.get("own_cup_quantity") or 0)
            )) if product.product_tmpl_id.food_own_cup_eligible else 0
            own_cup = own_cup_quantity > 0
            request.env["sale.order.line"].sudo().create({
                "order_id": order.id,
                "product_id": product.id,
                "product_uom_qty": quantity,
                "price_unit": product.lst_price,
                "food_own_cup": own_cup,
                "food_own_cup_quantity": own_cup_quantity,
                "food_line_note": (item.get("note") or "").strip(),
            })
            if own_cup_quantity:
                request.env["sale.order.line"].sudo().create({
                    "order_id": order.id,
                    "product_id": discount_product.id,
                    "product_uom_qty": own_cup_quantity,
                    "price_unit": -discount_amount,
                    "name": "Own cup discount",
                })
        try:
            order.action_food_record_payment(
                "cash", post.get("amount_received") or 0, False,
            )
            order.action_food_accept()
        except (UserError, ValueError) as error:
            order.unlink()
            return json_response({"error": str(error)}, 409)
        return json_response({"order": self._serialize(order)}, 201)

    @http.route("/food/staff/orders/<int:order_id>/payment", type="http", auth="user", methods=["POST"])
    def collect_payment(self, order_id, **post):
        if not self._allowed("cashier"):
            return json_response({"error": "forbidden"}, 403)
        order = request.env["sale.order"].sudo().browse(order_id).exists()
        if not order or not order.food_order:
            return json_response({"error": "not_found"}, 404)
        try:
            order.action_food_record_payment(
                "cash", post.get("amount_received") or 0, False,
            )
        except (UserError, ValueError) as error:
            return json_response({"error": str(error)}, 409)
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
        actions = {
            "accept": ("cashier", order.action_food_accept),
            "complete": ("cashier", order.action_food_complete),
            "cancel": ("cashier", order.action_food_cancel),
            "prepare": ("kitchen", order.action_food_prepare),
            "ready": ("kitchen", order.action_food_ready),
        }
        if action not in actions or actions[action][0] != role:
            return json_response({"error": "action_not_allowed"}, 403)
        try:
            actions[action][1]()
        except UserError as error:
            return json_response({"error": str(error)}, 409)
        return json_response({"order": self._serialize(order)})
