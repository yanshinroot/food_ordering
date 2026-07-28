import json

from odoo.tests import HttpCase, tagged

from .common import FoodOrderingCommon


@tagged("post_install", "-at_install", "food_ordering")
class TestSecurityHttp(HttpCase, FoodOrderingCommon):
    def test_recent_orders_ignores_phone_query_param(self):
        order, _ = self._create_guest_order(
            [{"product_id": self.meal.id, "quantity": 1}], phone="09-555-1234"
        )
        response = self.url_open("/food/orders?phone=09-555-1234")
        self.assertEqual(response.status_code, 200)
        self.assertNotIn(order.food_access_token, response.text)
        self.assertNotIn(order.name or "", response.text)

    def test_order_status_requires_valid_token(self):
        response = self.url_open("/api/food/v1/orders/not-a-real-token")
        self.assertEqual(response.status_code, 404)
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        response = self.url_open("/api/food/v1/orders/%s" % order.food_access_token)
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["order"]["number"], order.name)

    def test_create_order_idempotency_over_http(self):
        payload = {
            "name": "HTTP Guest", "phone": "09-321-0000", "department": "Ops", "floor": "1F",
            "items": [{"product_id": self.meal.id, "quantity": 1}],
        }
        headers = {"Content-Type": "application/json", "X-Idempotency-Key": "http-dup-1"}
        response1 = self.url_open("/api/food/v1/orders", data=json.dumps(payload).encode(), headers=headers)
        response2 = self.url_open("/api/food/v1/orders", data=json.dumps(payload).encode(), headers=headers)
        self.assertEqual(response1.status_code, 201)
        self.assertEqual(response2.status_code, 200)
        order_id_1 = response1.json()["order"]["id"]
        order_id_2 = response2.json()["order"]["id"]
        self.assertEqual(order_id_1, order_id_2)
        count = self.env["sale.order"].search_count([("food_idempotency_key", "=", "http-dup-1")])
        self.assertEqual(count, 1)

    def test_oversized_request_body_rejected(self):
        huge_payload = json.dumps({"note": "x" * (70 * 1024)}).encode()
        response = self.url_open(
            "/api/food/v1/orders", data=huge_payload, headers={"Content-Type": "application/json"}
        )
        self.assertEqual(response.status_code, 413)

    def test_device_key_required_for_staff_endpoint(self):
        response = self.url_open("/api/food/v1/staff/orders?status=active")
        self.assertEqual(response.status_code, 401)

    def test_pairing_claim_then_authenticated_call(self):
        pairing = self.env["food.device.pairing"].create({"role": "cashier"})
        payload = {"code": pairing.code, "device_name": "HTTP Test Register", "device_uuid": "http-uuid-1"}
        response = self.url_open(
            "/api/food/v1/pairing/claim", data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(response.status_code, 201)
        device_key = response.json()["device_key"]
        response = self.url_open(
            "/api/food/v1/staff/orders?status=active", headers={"X-Device-Key": device_key}
        )
        self.assertEqual(response.status_code, 200)

    def test_pairing_claim_wrong_code_rejected(self):
        response = self.url_open(
            "/api/food/v1/pairing/claim", data=json.dumps({"code": "BADCODE1"}).encode(),
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(response.status_code, 404)
