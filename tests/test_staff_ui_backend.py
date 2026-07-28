import json

from odoo.tests import HttpCase, tagged

from .common import FoodOrderingCommon


@tagged("post_install", "-at_install", "food_ordering")
class TestStaffUiBackend(HttpCase, FoodOrderingCommon):
    """Backend coverage for the endpoints the redesigned Cashier/Kitchen
    web UIs call directly (stale-version rejection, cash movements, event
    timeline, print retry, walk-in cleanup-on-failure). The UI itself was
    verified interactively with Playwright against staging; this file
    covers the same surface with executable, repeatable assertions."""

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.staff_user = cls.env["res.users"].create({
            "name": "Test Cashier Web", "login": "test_cashier_web", "password": "test_cashier_web",
            "group_ids": [
                (4, cls.env.ref("base.group_user").id),
                (4, cls.env.ref("food_ordering.group_food_manager").id),
            ],
        })

    def _login(self):
        self.authenticate("test_cashier_web", "test_cashier_web")

    def test_stale_version_rejected_on_accept(self):
        self._login()
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        response = self.url_open(
            "/food/staff/orders/%s/accept" % order.id,
            data={"role": "cashier", "expected_version": "999", "csrf_token": self._get_csrf_token()},
        )
        self.assertEqual(response.status_code, 409)
        body = response.json()
        self.assertEqual(body.get("error_code"), "stale_version")
        order.invalidate_recordset()
        self.assertEqual(order.food_status, "pending")

    def test_correct_version_accept_succeeds(self):
        self._login()
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        response = self.url_open(
            "/food/staff/orders/%s/accept" % order.id,
            data={"role": "cashier", "expected_version": str(order.food_state_version), "csrf_token": self._get_csrf_token()},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["order"]["status"], "accepted")

    def test_cash_session_open_movement_close(self):
        self._login()
        open_resp = self.url_open(
            "/food/staff/session/open",
            data={"opening_cash": "50000", "csrf_token": self._get_csrf_token()},
        )
        self.assertEqual(open_resp.status_code, 201)

        movement_resp = self.url_open(
            "/food/staff/session/movement",
            data={"movement_type": "cash_in", "amount": "2000", "reason": "float top-up", "csrf_token": self._get_csrf_token()},
        )
        self.assertEqual(movement_resp.status_code, 200)
        self.assertEqual(movement_resp.json()["session"]["cash_in_total"], 2000.0)

        bad_movement = self.url_open(
            "/food/staff/session/movement",
            data={"movement_type": "not_a_type", "amount": "10", "csrf_token": self._get_csrf_token()},
        )
        self.assertEqual(bad_movement.status_code, 400)

        current = self.url_open("/food/staff/session/current")
        expected = current.json()["session"]["closing_cash_expected"]
        close_resp = self.url_open(
            "/food/staff/session/close",
            data={"closing_cash_actual": str(expected), "csrf_token": self._get_csrf_token()},
        )
        self.assertEqual(close_resp.status_code, 200)
        self.assertEqual(close_resp.json()["difference"], 0.0)

    def test_order_events_endpoint(self):
        self._login()
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept(actor_user=self.staff_user)
        response = self.url_open("/food/staff/orders/%s/events?role=cashier" % order.id)
        self.assertEqual(response.status_code, 200)
        event_types = [e["event_type"] for e in response.json()["events"]]
        self.assertIn("created", event_types)
        self.assertIn("accepted", event_types)

    def test_print_retry_endpoint(self):
        self._login()
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept(actor_user=self.staff_user)
        job = self.env["food.print.job"].search([("order_id", "=", order.id), ("target", "=", "cashier")])
        job.write({"state": "failed", "error_message": "printer offline"})
        response = self.url_open(
            "/food/staff/print/jobs/%s/retry" % job.id,
            data={"csrf_token": self._get_csrf_token()},
        )
        self.assertEqual(response.status_code, 200)
        job.invalidate_recordset()
        self.assertEqual(job.state, "queued")

    def test_walkin_failure_does_not_leave_dangling_order(self):
        self._login()
        self.env["ir.config_parameter"].sudo().set_param("food_ordering.require_cash_session", "1")
        # No cash session open -> payment step inside create_walkin fails.
        items = json.dumps([{"product_id": self.meal.id, "quantity": 1}])
        before_count = self.env["sale.order"].search_count([("food_customer_name", "=", "STAGING-TEST Dangling Check")])
        response = self.url_open(
            "/food/staff/walkin",
            data={
                "customer_name": "STAGING-TEST Dangling Check", "amount_received": "5000",
                "items": items, "csrf_token": self._get_csrf_token(),
            },
        )
        self.assertEqual(response.status_code, 409)
        after_count = self.env["sale.order"].search_count([("food_customer_name", "=", "STAGING-TEST Dangling Check")])
        self.assertEqual(before_count, after_count, "A failed walk-in must not leave a dangling order behind")

    def test_kitchen_cannot_call_cashier_only_action(self):
        kitchen_user = self.env["res.users"].create({
            "name": "Test Kitchen Web", "login": "test_kitchen_web", "password": "test_kitchen_web",
            "group_ids": [
                (4, self.env.ref("base.group_user").id),
                (4, self.env.ref("food_ordering.group_food_kitchen").id),
            ],
        })
        self.authenticate("test_kitchen_web", "test_kitchen_web")
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        response = self.url_open(
            "/food/staff/orders/%s/accept" % order.id,
            data={"role": "kitchen", "csrf_token": self._get_csrf_token("/food/kitchen")},
        )
        self.assertEqual(response.status_code, 403)

    def _get_csrf_token(self, page_url="/food/cashier"):
        response = self.url_open(page_url)
        # The CSRF token is embedded in the page's data-csrf attribute.
        import re
        match = re.search(r'data-csrf="([^"]+)"', response.text)
        return match.group(1) if match else ""
