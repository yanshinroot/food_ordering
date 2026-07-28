from odoo.exceptions import UserError, ValidationError
from odoo.tests import tagged

from .common import FoodOrderingCommon


@tagged("post_install", "-at_install", "food_ordering")
class TestOrderWorkflow(FoodOrderingCommon):
    def test_guest_order_creation(self):
        order, created = self._create_guest_order([{"product_id": self.meal.id, "quantity": 2}])
        self.assertTrue(created)
        self.assertTrue(order.food_order)
        self.assertEqual(order.food_status, "pending")
        self.assertEqual(order.food_order_source, "web")
        lines = order.order_line.filtered(lambda l: not l.display_type)
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines.product_uom_qty, 2)

    def test_required_customer_fields(self):
        with self.assertRaises(ValidationError):
            self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}], name="")

    def test_empty_cart_rejected(self):
        with self.assertRaises(UserError):
            self._create_guest_order([])

    def test_idempotent_duplicate_submission(self):
        order1, created1 = self._create_guest_order(
            [{"product_id": self.meal.id, "quantity": 1}], idempotency_key="dup-key-1"
        )
        order2, created2 = self._create_guest_order(
            [{"product_id": self.meal.id, "quantity": 5}], idempotency_key="dup-key-1"
        )
        self.assertTrue(created1)
        self.assertFalse(created2)
        self.assertEqual(order1.id, order2.id)
        count = self.env["sale.order"].search_count([("food_idempotency_key", "=", "dup-key-1")])
        self.assertEqual(count, 1)

    def test_own_cup_discount_applied(self):
        order, _ = self._create_guest_order(
            [{
                "product_id": self.drink.id, "quantity": 2, "own_cup_quantity": 2,
                "modifier_option_ids": [self.size_regular.id],
            }]
        )
        self.assertTrue(order.food_own_cup)
        discount_lines = order.order_line.filtered(lambda l: l.product_id == self.discount_product)
        self.assertEqual(len(discount_lines), 1)
        self.assertEqual(discount_lines.price_unit, -500.0)
        self.assertEqual(discount_lines.product_uom_qty, 2)

    def test_partner_not_overwritten_on_repeat_guest(self):
        Partner = self.env["res.partner"]
        partner = Partner.create({"name": "Original Name", "phone": "09-999-0000", "comment": "keep me"})
        order, _ = self._create_guest_order(
            [{"product_id": self.meal.id, "quantity": 1}],
            name="Someone Else", phone="09-999-0000",
        )
        partner.invalidate_recordset()
        self.assertEqual(partner.name, "Original Name")
        # `comment` is an Html field: Odoo wraps plain text in a <p>, so
        # compare on content rather than exact markup.
        self.assertIn("keep me", str(partner.comment))
        # The order itself still records the guest's own entered name.
        self.assertEqual(order.food_customer_name, "Someone Else")

    def test_valid_transition_chain(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept()
        self.assertEqual(order.food_status, "accepted")
        order.action_food_prepare()
        self.assertEqual(order.food_status, "preparing")
        order.action_food_ready()
        self.assertEqual(order.food_status, "ready")
        order.action_food_record_payment("cash", order.amount_total)
        order.action_food_complete()
        self.assertEqual(order.food_status, "completed")

    def test_invalid_transition_raises_clear_error(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        # Still pending: preparing/ready must be rejected, not silently ignored.
        with self.assertRaises(UserError):
            order.action_food_prepare()
        self.assertEqual(order.food_status, "pending")
        with self.assertRaises(UserError):
            order.action_food_ready()

    def test_double_accept_raises(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept()
        with self.assertRaises(UserError):
            order.action_food_accept()

    def test_complete_requires_payment(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept()
        order.action_food_prepare()
        order.action_food_ready()
        with self.assertRaises(UserError):
            order.action_food_complete()

    def test_idempotent_request_replay(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept(request_id="req-1")
        version_after_first = order.food_state_version
        # Same request id replayed (e.g. a retried Android call): must not
        # error and must not double-apply the transition.
        order.action_food_accept(request_id="req-1")
        self.assertEqual(order.food_state_version, version_after_first)
        self.assertEqual(order.food_status, "accepted")

    def test_cancel_not_allowed_after_completed(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept()
        order.action_food_prepare()
        order.action_food_ready()
        order.action_food_record_payment("cash", order.amount_total)
        order.action_food_complete()
        with self.assertRaises(UserError):
            order.action_food_cancel(reason="too late")

    def test_audit_events_recorded(self):
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        order.action_food_accept()
        events = order.food_event_ids.mapped("event_type")
        self.assertIn("created", events)
        self.assertIn("accepted", events)
