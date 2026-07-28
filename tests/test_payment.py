import hashlib

from odoo.exceptions import UserError
from odoo.tests import tagged

from .common import FoodOrderingCommon


@tagged("post_install", "-at_install", "food_ordering")
class TestPayment(FoodOrderingCommon):
    def test_cash_change_calculation(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        total = order.amount_total
        order.action_food_record_payment("cash", total + 500)
        self.assertEqual(order.food_payment_status, "paid")
        self.assertEqual(order.food_change_amount, 500)
        self.assertEqual(order.food_amount_received, total + 500)

    def test_insufficient_cash_rejected(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        with self.assertRaises(UserError):
            order.action_food_record_payment("cash", 1)

    def test_cash_session_required_when_configured(self):
        self.env["ir.config_parameter"].sudo().set_param("food_ordering.require_cash_session", "1")
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        with self.assertRaises(UserError):
            order.action_food_record_payment(
                "cash", order.amount_total, actor_user=self.cashier_user
            )
        session = self.env["food.cash.session"].create({
            "opened_by_user_id": self.cashier_user.id, "opening_cash": 10000,
        })
        order.action_food_record_payment("cash", order.amount_total, actor_user=self.cashier_user)
        self.assertEqual(order.food_payment_status, "paid")
        self.assertEqual(order.food_cash_session_id, session)
        movement = session.movement_ids.filtered(lambda m: m.movement_type == "sale")
        self.assertEqual(len(movement), 1)
        self.assertEqual(movement.amount, order.amount_total)

    def test_paid_order_cancel_creates_refund(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_record_payment("cash", order.amount_total)
        order.action_food_cancel(reason="Customer changed mind", actor_user=self.manager_user)
        self.assertEqual(order.food_status, "cancelled")
        self.assertEqual(order.food_payment_status, "refunded")
        refunds = order.food_payment_ids.filtered(lambda p: p.payment_type == "refund")
        self.assertEqual(len(refunds), 1)
        self.assertAlmostEqual(refunds.amount, order.amount_total)

    def test_refund_requires_manager_authorization(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_record_payment("cash", order.amount_total)
        with self.assertRaises(UserError):
            order.action_food_refund(order.amount_total, "no reason", actor_user=self.cashier_user)
        # A manager user is allowed.
        order.action_food_refund(order.amount_total, "manager approved", actor_user=self.manager_user)
        self.assertEqual(order.food_payment_status, "refunded")

    def test_refund_with_manager_pin(self):
        pin = "1234"
        self.env["ir.config_parameter"].sudo().set_param(
            "food_ordering.manager_pin_hash", hashlib.sha256(pin.encode()).hexdigest()
        )
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_record_payment("cash", order.amount_total)
        with self.assertRaises(UserError):
            order.action_food_refund(order.amount_total, "wrong pin", manager_pin="0000")
        order.action_food_refund(order.amount_total, "correct pin", manager_pin=pin)
        self.assertEqual(order.food_payment_status, "refunded")

    def test_cash_session_close_totals(self):
        session = self.env["food.cash.session"].create({
            "opened_by_user_id": self.cashier_user.id, "opening_cash": 10000,
        })
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_record_payment("cash", order.amount_total, actor_user=self.cashier_user)
        expected = 10000 + order.amount_total
        self.assertEqual(session.closing_cash_expected, expected)
        session.action_close(expected, closed_by_user=self.cashier_user)
        self.assertEqual(session.state, "closed")
        self.assertEqual(session.difference, 0.0)
