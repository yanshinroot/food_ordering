from odoo import api, fields, models
from odoo.exceptions import ValidationError


class FoodModifierGroup(models.Model):
    _name = "food.modifier.group"
    _description = "Food Modifier Group"
    _order = "sequence, id"

    name = fields.Char(required=True)
    sequence = fields.Integer(default=10)
    selection_type = fields.Selection(
        [("single", "Choose One"), ("multiple", "Choose Multiple")],
        default="single",
        required=True,
    )
    required = fields.Boolean()
    max_selection = fields.Integer(default=0, help="Zero means no limit for multiple choice.")
    product_tmpl_ids = fields.Many2many(
        "product.template", "food_modifier_group_product_rel", "group_id", "product_tmpl_id",
        string="Products",
    )
    option_ids = fields.One2many("food.modifier.option", "group_id", string="Options")
    active = fields.Boolean(default=True)

    @api.constrains("selection_type", "max_selection")
    def _check_max_selection(self):
        for group in self:
            if group.max_selection < 0:
                raise ValidationError("Maximum selections cannot be negative.")


class FoodModifierOption(models.Model):
    _name = "food.modifier.option"
    _description = "Food Modifier Option"
    _order = "sequence, id"

    name = fields.Char(required=True)
    group_id = fields.Many2one("food.modifier.group", required=True, ondelete="cascade")
    price_extra = fields.Monetary(default=0.0)
    currency_id = fields.Many2one(
        "res.currency", related="company_id.currency_id", readonly=True
    )
    company_id = fields.Many2one(
        "res.company", default=lambda self: self.env.company, required=True
    )
    sequence = fields.Integer(default=10)
    active = fields.Boolean(default=True)
