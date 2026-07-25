from odoo import fields, models


class ProductTemplate(models.Model):
    _inherit = "product.template"

    food_available_online = fields.Boolean(
        string="Available in Food Ordering",
        help="Show this product on the public food ordering page.",
    )
    food_is_beverage = fields.Boolean(string="Beverage")
    food_own_cup_eligible = fields.Boolean(
        string="Own-cup Eligible",
        help="The own-cup discount can be applied to this product.",
    )
    food_modifier_group_ids = fields.Many2many(
        "food.modifier.group",
        "food_modifier_group_product_rel",
        "product_tmpl_id",
        "group_id",
        string="Modifier Groups",
    )
