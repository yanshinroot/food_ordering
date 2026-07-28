from odoo import fields, models


class FoodMenuCategory(models.Model):
    _name = "food.menu.category"
    _description = "Food Menu Category"
    _order = "sequence, name"

    name = fields.Char(required=True)
    sequence = fields.Integer(default=10)
    icon = fields.Char(string="Icon", help="Emoji shown on the category chip, e.g. 🥟")
    active = fields.Boolean(default=True)
