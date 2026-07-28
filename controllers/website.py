import base64
import io
import json
import os
import secrets

import qrcode

from odoo import http
from odoo.exceptions import UserError, ValidationError
from odoo.http import request
from odoo.modules.module import get_module_path
from odoo.tools.image import image_data_uri

from . import food_common as fc

ORDER_SUBMIT_IP_LIMIT = (20, 3600)
ORDER_SUBMIT_PHONE_LIMIT = (10, 3600)
MAX_CART_MODIFIERS = 20


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
                    modifier_ids = value.get("modifier_option_ids", [])
                    modifier_ids = [int(x) for x in modifier_ids][:MAX_CART_MODIFIERS] if isinstance(modifier_ids, list) else []
                    normalized[str(key)] = {
                        "product_id": int(value.get("product_id", 0)),
                        "quantity": quantity,
                        "own_cup_quantity": max(0, min(quantity, int(
                            value.get("own_cup_quantity", legacy_cup_quantity)
                        ))),
                        "note": str(value.get("note", ""))[:300],
                        "modifier_option_ids": modifier_ids,
                    }
                except (TypeError, ValueError):
                    continue
            else:
                try:
                    normalized["legacy-%s" % key] = {
                        "product_id": int(key), "quantity": max(1, min(20, int(value))),
                        "own_cup_quantity": 0, "note": "", "modifier_option_ids": [],
                    }
                except (TypeError, ValueError):
                    continue
        return normalized

    def _cart_context(self):
        cart = self._cart()
        currency = request.website.company_id.currency_id
        ModifierGroup = request.env["food.modifier.group"].sudo()
        lines, clean_cart = [], {}
        subtotal = 0.0
        modifier_total = 0.0
        count = 0
        product_tmpl_ids = set()
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
            modifier_ids = item.get("modifier_option_ids", [])
            try:
                extra_price, modifier_snapshot = ModifierGroup._validate_and_price(
                    product.product_tmpl_id, modifier_ids
                )
            except ValidationError:
                extra_price, modifier_snapshot = 0.0, []
                modifier_ids = []
            unit_price = product.lst_price + extra_price
            line_total = unit_price * quantity
            clean_cart[line_key] = {
                "product_id": product.id, "quantity": quantity,
                "own_cup_quantity": own_cup_quantity, "note": item_note,
                "modifier_option_ids": modifier_ids,
            }
            lines.append({
                "line_key": line_key, "product": product, "id": product.id,
                "name": product.display_name, "quantity": quantity,
                "price": unit_price, "line_total": line_total,
                "image_url": self._product_image_url(product.product_tmpl_id, "256"),
                "own_cup_eligible": product.product_tmpl_id.food_own_cup_eligible,
                "own_cup": own_cup, "own_cup_quantity": own_cup_quantity, "note": item_note,
                "modifiers": modifier_snapshot,
                "modifier_summary": "; ".join(
                    "%s: %s" % (m["group_name"], m["option_name"]) for m in modifier_snapshot
                ),
            })
            subtotal += line_total
            modifier_total += extra_price * quantity
            count += quantity
            product_tmpl_ids.add(product.product_tmpl_id.id)
        request.session["food_cart"] = clean_cart
        own_cup_discount_amount = request.env["sale.order"]._food_own_cup_discount_amount()
        own_cup_discount = sum(
            line["own_cup_quantity"] * own_cup_discount_amount for line in lines
        )
        after_own_cup = max(0.0, subtotal - own_cup_discount)
        promotions = request.env["food.promotion"].sudo()._find_applicable(
            after_own_cup, list(product_tmpl_ids), None
        )
        promotion_discount = 0.0
        promotion_names = []
        for promotion in promotions:
            if promotion.discount_type != "free_product":
                promotion_discount += promotion._discount_amount(after_own_cup)
            promotion_names.append(promotion.name)
        return {
            "cart_lines": lines, "cart_count": count, "cart_subtotal": subtotal,
            "cart_modifier_total": modifier_total,
            "cart_discount": own_cup_discount, "cart_promotion_discount": promotion_discount,
            "cart_promotion_names": promotion_names,
            "cart_total": max(0.0, after_own_cup - promotion_discount),
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
            "modifier_total": values["cart_modifier_total"],
            "modifier_total_formatted": money(values["cart_modifier_total"]),
            "discount": values["cart_discount"],
            "discount_formatted": money(values["cart_discount"]),
            "promotion_discount": values["cart_promotion_discount"],
            "promotion_discount_formatted": money(values["cart_promotion_discount"]),
            "promotion_names": values["cart_promotion_names"],
            "total": values["cart_total"], "total_formatted": money(values["cart_total"]),
            "lines": [{
                "line_key": line["line_key"], "id": line["id"], "name": line["name"],
                "quantity": line["quantity"], "price_formatted": money(line["price"]),
                "line_total_formatted": money(line["line_total"]), "image_url": line["image_url"],
                "own_cup_eligible": line["own_cup_eligible"], "own_cup": line["own_cup"],
                "own_cup_quantity": line["own_cup_quantity"],
                "note": line["note"], "modifier_summary": line["modifier_summary"],
                "modifier_option_ids": [m["option_id"] for m in line["modifiers"]],
            } for line in values["cart_lines"]],
        }

    def _auto_fill_customer(self):
        user = request.env.user
        if user._is_public():
            return "", ""
        partner = user.partner_id
        phone = partner.phone or ""
        if not phone and "mobile" in partner._fields:
            phone = partner.mobile or ""
        return partner.name or "", phone

    @http.route("/food/welcome", type="http", auth="public", website=True, sitemap=True)
    def food_welcome(self, **kwargs):
        return request.render("food_ordering.food_welcome", {})

    @http.route("/food/qr.png", type="http", auth="public", website=True)
    def food_qr_png(self, size="10", **kwargs):
        base_url = request.env["ir.config_parameter"].sudo().get_param("web.base.url")
        try:
            box_size = max(4, min(20, int(size)))
        except ValueError:
            box_size = 10
        qr = qrcode.QRCode(border=2, box_size=box_size)
        qr.add_data("%s/food/welcome" % base_url)
        qr.make(fit=True)
        image = qr.make_image(fill_color="#111111", back_color="white")
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        return request.make_response(buffer.getvalue(), headers=[("Content-Type", "image/png")])

    @http.route("/food/qr", type="http", auth="user", website=True)
    def food_qr_page(self, **kwargs):
        base_url = request.env["ir.config_parameter"].sudo().get_param("web.base.url")
        return request.render("food_ordering.food_qr_page", {
            "food_url": "%s/food/welcome" % base_url,
        })

    @http.route("/food/manifest.webmanifest", type="http", auth="public", website=True)
    def food_manifest(self, **kwargs):
        manifest = {
            "name": "Food Ordering",
            "short_name": "Food Ordering",
            "start_url": "/food/welcome",
            "scope": "/food",
            "display": "standalone",
            "background_color": "#ffffff",
            "theme_color": "#e4232c",
            "icons": [
                {"src": "/food_ordering/static/src/img/pwa-icon-192.png", "sizes": "192x192", "type": "image/png"},
                {"src": "/food_ordering/static/src/img/pwa-icon-512.png", "sizes": "512x512", "type": "image/png"},
            ],
        }
        return request.make_json_response(manifest, headers=[("Content-Type", "application/manifest+json")])

    @http.route("/food/service-worker.js", type="http", auth="public", website=True)
    def food_service_worker(self, **kwargs):
        module_path = os.path.join(
            get_module_path("food_ordering"), "static", "src", "js", "food_service_worker.js"
        )
        with open(module_path, "r", encoding="utf-8") as script_file:
            script = script_file.read()
        return request.make_response(script, headers=[
            ("Content-Type", "text/javascript"),
            ("Service-Worker-Allowed", "/food"),
            ("Cache-Control", "no-cache"),
        ])

    @http.route("/food/offline", type="http", auth="public", website=True)
    def food_offline(self, **kwargs):
        return request.render("food_ordering.food_offline", {})

    def _product_modifier_payload(self, template):
        return [
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
            for group in template.food_modifier_group_ids
        ]

    @http.route("/food", type="http", auth="public", website=True, sitemap=True)
    def food_catalog(self, **kwargs):
        products = request.env["product.template"].sudo().search(
            [("food_available_online", "=", True), ("sale_ok", "=", True)], order="name"
        )
        values = self._cart_context()
        recent_tokens = request.session.get("food_recent_orders", [])
        auto_name, auto_phone = self._auto_fill_customer()
        promotion = request.env["food.promotion"].sudo()._get_active_banner()
        values.update({
            "products": products, "own_cup_discount": request.env["sale.order"]._food_own_cup_discount_amount(),
            "recent_order_count": len(recent_tokens),
            "product_images": {template.id: self._product_image_url(template) for template in products},
            "checkout_error": kwargs.get("error"),
            "departments": request.env["food.department"].sudo().search([], order="sequence, name"),
            "menu_categories": request.env["food.menu.category"].sudo().search([], order="sequence, name"),
            "auto_name": auto_name, "auto_phone": auto_phone,
            "promotion_banner": promotion,
            "catalog_data_b64": base64.b64encode(json.dumps({
                "products": {
                    template.id: {
                        "modifierGroups": self._product_modifier_payload(template),
                        "ownCupEligible": template.food_own_cup_eligible,
                    }
                    for template in products
                },
                "promotion": {
                    "headline": promotion.banner_headline or promotion.name,
                    "subtext": promotion.banner_subtext or "",
                } if promotion else None,
            }, ensure_ascii=False).encode("utf-8")).decode("ascii"),
        })
        return request.render("food_ordering.food_catalog", values)

    @http.route("/food/cart/add", type="http", auth="public", website=True, methods=["POST"])
    def food_cart_add(self, product_id=None, quantity=1, **post):
        product = request.env["product.product"].sudo().browse(int(product_id or 0)).exists()
        if product and product.product_tmpl_id.food_available_online:
            cart = self._cart()
            existing_key = next((key for key, item in cart.items() if item["product_id"] == product.id and not item.get("own_cup_quantity") and not item.get("note") and not item.get("modifier_option_ids")), None)
            if existing_key:
                cart[existing_key]["quantity"] = min(20, cart[existing_key]["quantity"] + max(1, int(quantity or 1)))
            else:
                cart[secrets.token_hex(6)] = {"product_id": product.id, "quantity": max(1, min(20, int(quantity or 1))), "own_cup_quantity": 0, "note": "", "modifier_option_ids": []}
            request.session["food_cart"] = cart
        return request.redirect("/food")

    @http.route("/food/cart/update", type="http", auth="public", website=True, methods=["POST"])
    def food_cart_update(self, product_id=None, line_key=None, delta=0, quantity=None,
                         own_cup=None, own_cup_quantity=None, note=None, modifier_option_ids=None, **post):
        cart = self._cart()
        try:
            delta = int(delta or 0)
            requested_quantity = int(quantity) if quantity not in (None, "") else None
            requested_modifier_ids = None
            if modifier_option_ids is not None:
                raw_ids = modifier_option_ids if isinstance(modifier_option_ids, list) else [modifier_option_ids]
                requested_modifier_ids = [int(x) for x in raw_ids if str(x).strip().isdigit()][:MAX_CART_MODIFIERS]
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
                    if requested_modifier_ids is not None:
                        item["modifier_option_ids"] = requested_modifier_ids
                    cart[line_key] = item
            else:
                product = request.env["product.product"].sudo().browse(int(product_id or 0)).exists()
                if not product or not product.product_tmpl_id.food_available_online:
                    return request.make_json_response({"error": "Product is unavailable."}, status=404)
                requested_cups = int(own_cup_quantity or 0)
                use_own_cup_quantity = max(0, min(requested_quantity or delta or 1, requested_cups)) if product.product_tmpl_id.food_own_cup_eligible else 0
                # A confirmed product selection is its own basket line. Keeping
                # it separate preserves the selected note, modifiers and own-cup ratio.
                cart[secrets.token_hex(6)] = {
                    "product_id": product.id, "quantity": max(1, min(20, requested_quantity or delta or 1)),
                    "own_cup_quantity": use_own_cup_quantity, "note": str(note or "").strip()[:300],
                    "modifier_option_ids": requested_modifier_ids or [],
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
        wants_json = request.httprequest.headers.get("X-Requested-With") == "fetch"

        def fail(error_code, status=400):
            if wants_json:
                return request.make_json_response({"error": error_code}, status=status)
            return request.redirect("/food?checkout=1&error=%s" % error_code)

        ip = fc.client_ip()
        if not fc.rate_limited("order_submit_ip", ip, *ORDER_SUBMIT_IP_LIMIT):
            return fail("rate_limited", 429)
        required = ["name", "phone", "department", "floor"]
        missing = [field for field in required if not str(post.get(field, "")).strip()]
        cart_values = self._cart_context()
        lines = cart_values["cart_lines"]
        if missing or not lines:
            return fail("missing")
        phone = str(post["phone"]).strip()[:32]
        if not fc.rate_limited("order_submit_phone", phone, *ORDER_SUBMIT_PHONE_LIMIT):
            return fail("rate_limited", 429)

        items = [
            {
                "product_id": line["id"], "quantity": line["quantity"],
                "own_cup_quantity": line["own_cup_quantity"], "note": line["note"],
                "modifier_option_ids": [m["option_id"] for m in line["modifiers"]],
            }
            for line in lines
        ]
        idem_key = post.get("idempotency_key") or request.session.get("food_pending_submit_key")
        if not idem_key:
            idem_key = secrets.token_hex(16)
        request.session["food_pending_submit_key"] = idem_key
        try:
            order, created = request.env["sale.order"]._food_create_guest_order(
                name=post["name"], phone=post["phone"], department=post["department"],
                floor=post["floor"], note=post.get("note", ""), items=items,
                source="web", company=request.website.company_id, idempotency_key=idem_key,
            )
        except (UserError, ValidationError):
            return fail("invalid")
        request.session["food_cart"] = {}
        request.session.pop("food_pending_submit_key", None)
        recent = list(request.session.get("food_recent_orders", []))
        recent = [order.food_access_token] + [token for token in recent if token != order.food_access_token]
        request.session["food_recent_orders"] = recent[:8]
        if wants_json:
            return request.make_json_response({
                "order_number": order.name,
                "status_url": "/food/order/%s" % order.food_access_token,
            }, status=201 if created else 200)
        return request.redirect("/food/order/%s" % order.food_access_token)

    @http.route("/food/orders", type="http", auth="public", website=True)
    def food_recent_orders(self, **kwargs):
        # Recent orders come only from this browser's own session — a
        # phone number alone never reveals someone else's order. There is
        # no phone-based lookup: the only alternate path is pasting a
        # secure tracking code (handled by food_track below).
        tokens = request.session.get("food_recent_orders", [])
        orders = request.env["sale.order"].sudo().search(
            [("food_order", "=", True), ("food_access_token", "in", tokens)], order="date_order desc"
        ) if tokens else request.env["sale.order"].sudo()
        return request.render("food_ordering.food_recent_orders", {
            "orders": orders, "track_error": kwargs.get("error"),
        })

    @http.route("/food/track", type="http", auth="public", website=True, methods=["POST"])
    def food_track(self, code=None, **kwargs):
        # Accepts either a bare token or a pasted full tracking URL.
        raw = str(code or "").strip()
        token = raw.rsplit("/", 1)[-1] if raw else ""
        order = request.env["sale.order"].sudo().search(
            [("food_order", "=", True), ("food_access_token", "=", token)], limit=1
        ) if token else request.env["sale.order"].sudo()
        if not order:
            return request.redirect("/food/orders?error=not_found")
        return request.redirect("/food/order/%s" % order.food_access_token)

    @http.route("/food/order/<string:token>", type="http", auth="public", website=True)
    def food_status(self, token, **kwargs):
        order = request.env["sale.order"].sudo().search(
            [("food_order", "=", True), ("food_access_token", "=", token)], limit=1
        )
        if not order:
            return request.not_found()
        return request.render("food_ordering.food_order_status", {"order": order})
