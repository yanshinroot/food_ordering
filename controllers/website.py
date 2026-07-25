import secrets

from odoo import http
from odoo.http import request
from odoo.tools.image import image_data_uri


class FoodOrderingWebsite(http.Controller):
    def _product_image_url(self, template, size="512"):
        image = getattr(template, "image_%s" % size, False)
        if image:
            return image_data_uri(image)
        starter_images = {
            request.env.ref("food_ordering.starter_americano").id: "americano.jpg",
            request.env.ref("food_ordering.starter_milk_tea").id: "milk-tea.jpg",
            request.env.ref("food_ordering.starter_chicken_rice").id: "chicken-rice.jpg",
            request.env.ref("food_ordering.starter_avocado_smoothie").id: "avocado-smoothie.jpg",
        }
        filename = starter_images.get(template.id)
        return "/food_ordering/static/src/img/products/%s" % filename if filename else ""

    def _cart(self):
        raw = dict(request.session.get("food_cart", {}))
        normalized = {}
        for key, value in raw.items():
            if isinstance(value, dict):
                try:
                    quantity = max(1, min(20, int(value.get("quantity", 1))))
                    legacy_cup_quantity = quantity if value.get("own_cup") else 0
                    normalized[str(key)] = {
                        "product_id": int(value.get("product_id", 0)),
                        "quantity": quantity,
                        "own_cup_quantity": max(0, min(quantity, int(
                            value.get("own_cup_quantity", legacy_cup_quantity)
                        ))),
                        "note": str(value.get("note", ""))[:300],
                    }
                except (TypeError, ValueError):
                    continue
            else:
                try:
                    normalized["legacy-%s" % key] = {
                        "product_id": int(key), "quantity": max(1, min(20, int(value))),
                        "own_cup_quantity": 0, "note": "",
                    }
                except (TypeError, ValueError):
                    continue
        return normalized

    def _cart_context(self):
        cart = self._cart()
        currency = request.website.company_id.currency_id
        lines, clean_cart = [], {}
        subtotal = 0.0
        count = 0
        for line_key, item in cart.items():
            product = request.env["product.product"].sudo().browse(item["product_id"]).exists()
            if not product or not product.product_tmpl_id.food_available_online:
                continue
            quantity = item["quantity"]
            own_cup_quantity = max(0, min(
                quantity, int(item.get("own_cup_quantity", 0))
            )) if product.product_tmpl_id.food_own_cup_eligible else 0
            own_cup = own_cup_quantity > 0
            item_note = str(item.get("note", ""))[:300]
            unit_price = product.lst_price
            line_total = unit_price * quantity
            clean_cart[line_key] = {
                "product_id": product.id, "quantity": quantity,
                "own_cup_quantity": own_cup_quantity, "note": item_note,
            }
            lines.append({
                "line_key": line_key, "product": product, "id": product.id,
                "name": product.display_name, "quantity": quantity,
                "price": unit_price, "line_total": line_total,
                "image_url": self._product_image_url(product.product_tmpl_id, "256"),
                "own_cup_eligible": product.product_tmpl_id.food_own_cup_eligible,
                "own_cup": own_cup, "own_cup_quantity": own_cup_quantity, "note": item_note,
            })
            subtotal += line_total
            count += quantity
        request.session["food_cart"] = clean_cart
        discount = sum(
            line["own_cup_quantity"] * self._discount_amount() for line in lines
        )
        return {
            "cart_lines": lines, "cart_count": count, "cart_subtotal": subtotal,
            "cart_discount": discount, "cart_total": max(0.0, subtotal - discount),
            "currency": currency, "currency_name": currency.name,
        }

    def _cart_json(self):
        values = self._cart_context()
        currency = values["currency_name"]
        money = lambda amount: "%s %s" % (format(amount, ",.0f"), currency)
        return {
            "count": values["cart_count"],
            "subtotal": values["cart_subtotal"],
            "subtotal_formatted": money(values["cart_subtotal"]),
            "discount": values["cart_discount"],
            "discount_formatted": money(values["cart_discount"]),
            "total": values["cart_total"], "total_formatted": money(values["cart_total"]),
            "lines": [{
                "line_key": line["line_key"], "id": line["id"], "name": line["name"],
                "quantity": line["quantity"], "price_formatted": money(line["price"]),
                "line_total_formatted": money(line["line_total"]), "image_url": line["image_url"],
                "own_cup_eligible": line["own_cup_eligible"], "own_cup": line["own_cup"],
                "own_cup_quantity": line["own_cup_quantity"],
                "note": line["note"],
            } for line in values["cart_lines"]],
        }

    def _discount_amount(self):
        value = request.env["ir.config_parameter"].sudo().get_param(
            "food_ordering.own_cup_discount", "500"
        )
        try:
            return max(0.0, float(value))
        except ValueError:
            return 0.0

    def _auto_fill_customer(self):
        user = request.env.user
        if user._is_public():
            return "", ""
        partner = user.partner_id
        return partner.name or "", partner.phone or partner.mobile or ""

    @http.route("/food", type="http", auth="public", website=True, sitemap=True)
    def food_catalog(self, **kwargs):
        products = request.env["product.template"].sudo().search(
            [("food_available_online", "=", True), ("sale_ok", "=", True)], order="name"
        )
        values = self._cart_context()
        recent_tokens = request.session.get("food_recent_orders", [])
        auto_name, auto_phone = self._auto_fill_customer()
        values.update({
            "products": products, "own_cup_discount": self._discount_amount(),
            "recent_order_count": len(recent_tokens),
            "product_images": {template.id: self._product_image_url(template) for template in products},
            "checkout_error": kwargs.get("error"),
            "departments": request.env["food.department"].sudo().search([], order="sequence, name"),
            "auto_name": auto_name, "auto_phone": auto_phone,
        })
        return request.render("food_ordering.food_catalog", values)

    @http.route("/food/cart/add", type="http", auth="public", website=True, methods=["POST"])
    def food_cart_add(self, product_id=None, quantity=1, **post):
        product = request.env["product.product"].sudo().browse(int(product_id or 0)).exists()
        if product and product.product_tmpl_id.food_available_online:
            cart = self._cart()
            existing_key = next((key for key, item in cart.items() if item["product_id"] == product.id and not item.get("own_cup_quantity") and not item.get("note")), None)
            if existing_key:
                cart[existing_key]["quantity"] = min(20, cart[existing_key]["quantity"] + max(1, int(quantity or 1)))
            else:
                cart[secrets.token_hex(6)] = {"product_id": product.id, "quantity": max(1, min(20, int(quantity or 1))), "own_cup_quantity": 0, "note": ""}
            request.session["food_cart"] = cart
        return request.redirect("/food")

    @http.route("/food/cart/update", type="http", auth="public", website=True, methods=["POST"])
    def food_cart_update(self, product_id=None, line_key=None, delta=0, quantity=None,
                         own_cup=None, own_cup_quantity=None, note=None, **post):
        cart = self._cart()
        try:
            delta = int(delta or 0)
            requested_quantity = int(quantity) if quantity not in (None, "") else None
            if line_key and line_key in cart:
                item = cart[line_key]
                new_quantity = requested_quantity if requested_quantity is not None else item["quantity"] + delta
                if new_quantity <= 0:
                    cart.pop(line_key, None)
                else:
                    item["quantity"] = min(20, new_quantity)
                    if own_cup_quantity is not None or own_cup is not None:
                        product = request.env["product.product"].sudo().browse(item["product_id"]).exists()
                        requested_cups = int(own_cup_quantity) if own_cup_quantity is not None else (item["quantity"] if str(own_cup).lower() in ("1", "true", "on") else 0)
                        item["own_cup_quantity"] = max(0, min(item["quantity"], requested_cups)) if product and product.product_tmpl_id.food_own_cup_eligible else 0
                    else:
                        item["own_cup_quantity"] = min(item["quantity"], item.get("own_cup_quantity", 0))
                    if note is not None:
                        item["note"] = str(note).strip()[:300]
                    cart[line_key] = item
            else:
                product = request.env["product.product"].sudo().browse(int(product_id or 0)).exists()
                if not product or not product.product_tmpl_id.food_available_online:
                    return request.make_json_response({"error": "Product is unavailable."}, status=404)
                requested_cups = int(own_cup_quantity or 0)
                use_own_cup_quantity = max(0, min(requested_quantity or delta or 1, requested_cups)) if product.product_tmpl_id.food_own_cup_eligible else 0
                # A confirmed product selection is its own basket line. Keeping
                # it separate preserves the selected note and own-cup ratio.
                cart[secrets.token_hex(6)] = {
                    "product_id": product.id, "quantity": max(1, min(20, requested_quantity or delta or 1)),
                    "own_cup_quantity": use_own_cup_quantity, "note": str(note or "").strip()[:300],
                }
        except (TypeError, ValueError) as error:
            return request.make_json_response({"error": str(error) or "Invalid cart update."}, status=400)
        request.session["food_cart"] = cart
        return request.make_json_response(self._cart_json())

    @http.route("/food/cart/remove", type="http", auth="public", website=True, methods=["POST"])
    def food_cart_remove(self, line_key=None, product_id=None, **post):
        cart = self._cart()
        if line_key:
            cart.pop(str(line_key), None)
        elif product_id:
            for key in [key for key, item in cart.items() if item["product_id"] == int(product_id)]:
                cart.pop(key, None)
        request.session["food_cart"] = cart
        return request.redirect("/food?checkout=1")

    @http.route("/food/checkout", type="http", auth="public", website=True)
    def food_checkout(self, error=None, **kwargs):
        suffix = "&error=%s" % error if error else ""
        return request.redirect("/food?checkout=1%s" % suffix)

    @http.route("/food/order/submit", type="http", auth="public", website=True, methods=["POST"])
    def food_submit(self, **post):
        required = ["name", "phone", "department", "floor"]
        missing = [field for field in required if not str(post.get(field, "")).strip()]
        cart_values = self._cart_context()
        lines = cart_values["cart_lines"]
        if missing or not lines:
            return request.redirect("/food?checkout=1&error=missing")

        Partner = request.env["res.partner"].sudo()
        phone = str(post["phone"]).strip()
        partner = Partner.search([("phone", "=", phone)], limit=1)
        partner_values = {
            "name": str(post["name"]).strip(), "phone": phone,
            "comment": "Department: %s\nFloor: %s" % (post["department"], post["floor"]),
        }
        partner.write(partner_values) if partner else None
        if not partner:
            partner = Partner.create(partner_values)
        order = request.env["sale.order"].sudo().create({
            "partner_id": partner.id, "pricelist_id": partner.property_product_pricelist.id,
            "company_id": request.website.company_id.id, "food_order": True,
            "food_status": "pending", "food_order_source": "web",
            "food_customer_name": partner_values["name"], "food_phone": phone,
            "food_department": str(post["department"]).strip(), "food_floor": str(post["floor"]).strip(),
            "food_note": str(post.get("note", "")).strip(),
            "food_own_cup": any(line["own_cup"] for line in lines),
            "client_order_ref": "WEB-%s" % secrets.token_hex(4).upper(),
        })
        discount_quantity = 0
        for line in lines:
            label = line["name"]
            if line["note"]:
                label += "\nNote: " + line["note"]
            if line["own_cup_quantity"]:
                label += "\nOwn cup: %s" % line["own_cup_quantity"]
                discount_quantity += line["own_cup_quantity"]
            request.env["sale.order.line"].sudo().create({
                "order_id": order.id, "product_id": line["id"], "name": label,
                "product_uom_qty": line["quantity"], "price_unit": line["price"],
                "food_own_cup": line["own_cup"], "food_line_note": line["note"],
                "food_own_cup_quantity": line["own_cup_quantity"],
            })
        if discount_quantity:
            discount_product = request.env.ref("food_ordering.product_own_cup_discount")
            request.env["sale.order.line"].sudo().create({
                "order_id": order.id, "product_id": discount_product.id,
                "product_uom_qty": discount_quantity, "price_unit": -self._discount_amount(),
            })
        request.session["food_cart"] = {}
        recent = list(request.session.get("food_recent_orders", []))
        recent = [order.food_access_token] + [token for token in recent if token != order.food_access_token]
        request.session["food_recent_orders"] = recent[:8]
        return request.redirect("/food/order/%s" % order.food_access_token)

    @http.route("/food/orders", type="http", auth="public", website=True)
    def food_recent_orders(self, phone=None, **kwargs):
        tokens = request.session.get("food_recent_orders", [])
        session_orders = request.env["sale.order"].sudo().search(
            [("food_order", "=", True), ("food_access_token", "in", tokens)], order="date_order desc"
        ) if tokens else request.env["sale.order"].sudo()
        searched_phone = str(phone or "").strip()
        orders = session_orders
        if searched_phone:
            phone_orders = request.env["sale.order"].sudo().search(
                [("food_order", "=", True), ("food_phone", "=", searched_phone)],
                order="date_order desc", limit=30,
            )
            orders = (phone_orders | session_orders).sorted(key=lambda order: order.date_order, reverse=True)
        return request.render("food_ordering.food_recent_orders", {
            "orders": orders, "searched_phone": searched_phone,
        })

    @http.route("/food/order/<string:token>", type="http", auth="public", website=True)
    def food_status(self, token, **kwargs):
        order = request.env["sale.order"].sudo().search(
            [("food_order", "=", True), ("food_access_token", "=", token)], limit=1
        )
        if not order:
            return request.not_found()
        return request.render("food_ordering.food_order_status", {"order": order})
