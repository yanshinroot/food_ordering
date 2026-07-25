from odoo import fields, models


class FoodDepartment(models.Model):
    _name = "food.department"
    _description = "Food Order Department"
    _order = "sequence, name"

    name = fields.Char(required=True)
    sequence = fields.Integer(default=10)
    active = fields.Boolean(default=True)
