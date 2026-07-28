from odoo import fields
from odoo.tests import tagged

from .common import FoodOrderingCommon


@tagged("post_install", "-at_install", "food_ordering")
class TestPromotion(FoodOrderingCommon):
    def test_fixed_discount_applied_above_minimum(self):
        self.env["food.promotion"].create({
            "name": "1000 off", "discount_type": "fixed", "discount_value": 1000.0,
            "min_order_amount": 1000.0,
        })
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        self.assertEqual(order.food_promotion_id.name, "1000 off")
        promo_lines = order.order_line.filtered(
            lambda l: l.product_id == self.env.ref("food_ordering.product_promotion_discount")
        )
        self.assertEqual(len(promo_lines), 1)
        self.assertEqual(promo_lines.price_unit, -1000.0)

    def test_promotion_below_minimum_not_applied(self):
        self.env["food.promotion"].create({
            "name": "Big spender", "discount_type": "fixed", "discount_value": 1000.0,
            "min_order_amount": 100000.0,
        })
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        self.assertFalse(order.food_promotion_id)

    def test_promotion_outside_date_range_not_applied(self):
        self.env["food.promotion"].create({
            "name": "Future promo", "discount_type": "fixed", "discount_value": 500.0,
            "date_from": fields.Datetime.add(fields.Datetime.now(), days=1),
        })
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        self.assertFalse(order.food_promotion_id)

    def test_promotion_usage_limit_per_customer(self):
        self.env["food.promotion"].create({
            "name": "Once only", "discount_type": "fixed", "discount_value": 500.0,
            "usage_limit_per_customer": 1,
        })
        order1, _ = self._create_guest_order(
            [{"product_id": self.meal.id, "quantity": 1}], phone="09-777-1111"
        )
        self.assertTrue(order1.food_promotion_id)
        order1.action_food_accept()
        order2, _ = self._create_guest_order(
            [{"product_id": self.meal.id, "quantity": 1}], phone="09-777-1111"
        )
        self.assertFalse(order2.food_promotion_id)

    def test_non_stackable_blocks_lower_priority(self):
        self.env["food.promotion"].create({
            "name": "Exclusive", "discount_type": "fixed", "discount_value": 700.0,
            "priority": 20, "stackable": False,
        })
        self.env["food.promotion"].create({
            "name": "Stackable extra", "discount_type": "fixed", "discount_value": 200.0,
            "priority": 10, "stackable": True,
        })
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        self.assertEqual(order.food_promotion_id.name, "Exclusive")
        promo_lines = order.order_line.filtered(
            lambda l: l.product_id == self.env.ref("food_ordering.product_promotion_discount")
        )
        self.assertEqual(len(promo_lines), 1)

    def test_free_product_promotion(self):
        free_item = self.env["product.product"].create({
            "name": "Free Cookie", "type": "consu", "list_price": 1000.0, "sale_ok": True,
        })
        self.env["food.promotion"].create({
            "name": "Free cookie promo", "discount_type": "free_product",
            "free_product_id": free_item.id, "free_product_qty": 1,
        })
        order, _ = self._create_guest_order([{"product_id": self.meal.id, "quantity": 1}])
        free_lines = order.order_line.filtered(lambda l: l.product_id == free_item)
        self.assertEqual(len(free_lines), 1)
        self.assertEqual(free_lines.price_unit, 0.0)

    def test_banner_hidden_without_active_promotion(self):
        self.assertFalse(self.env["food.promotion"]._get_active_banner())
        self.env["food.promotion"].create({
            "name": "Banner promo", "discount_type": "fixed", "discount_value": 300.0,
            "show_on_banner": True, "banner_headline": "Free drink!",
        })
        banner = self.env["food.promotion"]._get_active_banner()
        self.assertEqual(banner.banner_headline, "Free drink!")
