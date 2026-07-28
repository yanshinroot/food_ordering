from odoo.tests.common import TransactionCase


class FoodOrderingCommon(TransactionCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.env = cls.env(context=dict(cls.env.context, tracking_disable=True))
        Param = cls.env["ir.config_parameter"].sudo()
        Param.set_param("food_ordering.own_cup_discount", "500")
        Param.set_param("food_ordering.require_cash_session", "0")
        Param.set_param("food_ordering.allow_cancel_from_ready", "1")
        Param.set_param("food_ordering.manager_pin_hash", "")

        cls.drink_tmpl = cls.env["product.template"].create({
            "name": "Test Latte",
            "type": "consu",
            "list_price": 3000.0,
            "sale_ok": True,
            "purchase_ok": False,
            "food_available_online": True,
            "food_own_cup_eligible": True,
        })
        cls.drink = cls.drink_tmpl.product_variant_id

        cls.meal_tmpl = cls.env["product.template"].create({
            "name": "Test Fried Rice",
            "type": "consu",
            "list_price": 5000.0,
            "sale_ok": True,
            "purchase_ok": False,
            "food_available_online": True,
        })
        cls.meal = cls.meal_tmpl.product_variant_id

        cls.discount_product = cls.env.ref("food_ordering.product_own_cup_discount")
        cls.env.ref("food_ordering.product_promotion_discount")

        cls.size_group = cls.env["food.modifier.group"].create({
            "name": "Size", "selection_type": "single", "required": True,
            "product_tmpl_ids": [(6, 0, [cls.drink_tmpl.id])],
        })
        cls.size_regular = cls.env["food.modifier.option"].create({
            "name": "Regular", "group_id": cls.size_group.id, "price_extra": 0.0,
        })
        cls.size_large = cls.env["food.modifier.option"].create({
            "name": "Large", "group_id": cls.size_group.id, "price_extra": 800.0,
        })

        cls.addon_group = cls.env["food.modifier.group"].create({
            "name": "Add-ons", "selection_type": "multiple", "max_selection": 2,
            "product_tmpl_ids": [(6, 0, [cls.drink_tmpl.id])],
        })
        cls.addon_oat_milk = cls.env["food.modifier.option"].create({
            "name": "Oat Milk", "group_id": cls.addon_group.id, "price_extra": 500.0,
        })
        cls.addon_boba = cls.env["food.modifier.option"].create({
            "name": "Boba", "group_id": cls.addon_group.id, "price_extra": 300.0,
        })

        cls.manager_user = cls.env["res.users"].create({
            "name": "Food Manager", "login": "food_manager_test",
            "group_ids": [(4, cls.env.ref("food_ordering.group_food_manager").id)],
        })
        cls.cashier_user = cls.env["res.users"].create({
            "name": "Food Cashier", "login": "food_cashier_test",
            "group_ids": [(4, cls.env.ref("food_ordering.group_food_cashier").id)],
        })

    def _create_guest_order(self, items, **kwargs):
        vals = dict(
            name="Test Customer", phone="09-100-2000", department="Engineering",
            floor="3rd Floor", note="", items=items, source="web",
        )
        vals.update(kwargs)
        return self.env["sale.order"]._food_create_guest_order(**vals)
