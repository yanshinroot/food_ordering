from odoo.exceptions import ValidationError
from odoo.tests import tagged

from .common import FoodOrderingCommon


@tagged("post_install", "-at_install", "food_ordering")
class TestModifiers(FoodOrderingCommon):
    def test_required_group_must_be_selected(self):
        with self.assertRaises(ValidationError):
            self._create_guest_order([{"product_id": self.drink.id, "quantity": 1, "modifier_option_ids": []}])

    def test_modifier_pricing_added_to_line(self):
        order, _ = self._create_guest_order([
            {
                "product_id": self.drink.id, "quantity": 1,
                "modifier_option_ids": [self.size_large.id, self.addon_oat_milk.id],
            }
        ])
        line = order.order_line.filtered(lambda l: l.product_id == self.drink)
        self.assertEqual(line.price_unit, self.drink.lst_price + 800.0 + 500.0)
        self.assertEqual(len(line.food_modifier_ids), 2)
        self.assertIn("Large", line.food_modifier_summary)

    def test_modifier_snapshot_survives_master_data_changes(self):
        order, _ = self._create_guest_order([
            {"product_id": self.drink.id, "quantity": 1, "modifier_option_ids": [self.size_large.id]}
        ])
        line = order.order_line.filtered(lambda l: l.product_id == self.drink)
        snapshot = line.food_modifier_ids
        self.assertEqual(snapshot.price_extra, 800.0)
        self.assertEqual(snapshot.option_name, "Large")
        # Changing the master option afterwards must not retroactively
        # change this historical order line's frozen snapshot.
        self.size_large.write({"name": "Extra Large", "price_extra": 1500.0})
        snapshot.invalidate_recordset()
        self.assertEqual(snapshot.price_extra, 800.0)
        self.assertEqual(snapshot.option_name, "Large")
        self.assertEqual(line.price_unit, self.drink.lst_price + 800.0)

    def test_multiple_choice_max_selection_enforced(self):
        third_option = self.env["food.modifier.option"].create({
            "name": "Boba Extra", "group_id": self.addon_group.id, "price_extra": 200.0,
        })
        with self.assertRaises(ValidationError):
            self._create_guest_order([
                {
                    "product_id": self.drink.id, "quantity": 1,
                    "modifier_option_ids": [
                        self.size_regular.id, self.addon_oat_milk.id, self.addon_boba.id, third_option.id,
                    ],
                }
            ])

    def test_option_from_wrong_group_rejected(self):
        unrelated_group = self.env["food.modifier.group"].create({
            "name": "Meal Spice", "selection_type": "single",
            "product_tmpl_ids": [(6, 0, [self.meal_tmpl.id])],
        })
        unrelated_option = self.env["food.modifier.option"].create({
            "name": "Hot", "group_id": unrelated_group.id,
        })
        with self.assertRaises(ValidationError):
            self._create_guest_order([
                {
                    "product_id": self.drink.id, "quantity": 1,
                    "modifier_option_ids": [self.size_regular.id, unrelated_option.id],
                }
            ])
