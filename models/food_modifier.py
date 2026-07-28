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
    min_selection = fields.Integer(default=0, help="Minimum options that must be selected when required.")
    max_selection = fields.Integer(default=0, help="Zero means no limit for multiple choice.")
    product_tmpl_ids = fields.Many2many(
        "product.template", "food_modifier_group_product_rel", "group_id", "product_tmpl_id",
        string="Products",
    )
    option_ids = fields.One2many("food.modifier.option", "group_id", string="Options")
    active = fields.Boolean(default=True)

    @api.constrains("selection_type", "max_selection", "min_selection")
    def _check_max_selection(self):
        for group in self:
            if group.max_selection < 0:
                raise ValidationError("Maximum selections cannot be negative.")
            if group.min_selection < 0:
                raise ValidationError("Minimum selections cannot be negative.")
            if group.max_selection and group.min_selection > group.max_selection:
                raise ValidationError("Minimum selections cannot exceed the maximum.")

    def _effective_min(self):
        self.ensure_one()
        if self.min_selection:
            return self.min_selection
        return 1 if self.required else 0

    def _effective_max(self):
        self.ensure_one()
        if self.selection_type == "single":
            return 1
        return self.max_selection or 0

    @api.model
    def _validate_and_price(self, product_tmpl, selected_option_ids):
        """Validate `selected_option_ids` against `product_tmpl`'s modifier
        groups (required/min/max) and return (extra_price, snapshot) where
        snapshot is a list of dicts safe to freeze onto the order line."""
        Option = self.env["food.modifier.option"].sudo()
        options = Option.browse(selected_option_ids).exists()
        groups = product_tmpl.food_modifier_group_ids
        allowed_group_ids = set(groups.ids)
        for option in options:
            if option.group_id.id not in allowed_group_ids or not option.active:
                raise ValidationError(
                    "'%s' is not an available option for this product." % option.name
                )
        options_by_group = {}
        for option in options:
            options_by_group.setdefault(option.group_id.id, Option.browse())
            options_by_group[option.group_id.id] |= option
        for group in groups:
            chosen = options_by_group.get(group.id, Option.browse())
            count = len(chosen)
            min_needed = group._effective_min()
            max_allowed = group._effective_max()
            if count < min_needed:
                raise ValidationError(
                    "Please select at least %s option(s) for '%s'." % (min_needed, group.name)
                )
            if max_allowed and count > max_allowed:
                raise ValidationError(
                    "Please select at most %s option(s) for '%s'." % (max_allowed, group.name)
                )
        extra_price = sum(options.mapped("price_extra"))
        snapshot = [
            {
                "option_id": option.id,
                "group_name": option.group_id.name,
                "option_name": option.name,
                "price_extra": option.price_extra,
            }
            for option in options
        ]
        return extra_price, snapshot


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


class FoodOrderLineModifier(models.Model):
    _name = "food.order.line.modifier"
    _description = "Food Order Line Modifier Selection (frozen snapshot)"
    _order = "id"

    order_line_id = fields.Many2one(
        "sale.order.line", required=True, ondelete="cascade", index=True
    )
    option_id = fields.Many2one("food.modifier.option", ondelete="set null")
    group_name = fields.Char(required=True, help="Snapshot of the group name at order time.")
    option_name = fields.Char(required=True, help="Snapshot of the option name at order time.")
    price_extra = fields.Monetary(help="Snapshot of the option's extra price at order time.")
    currency_id = fields.Many2one(
        "res.currency", default=lambda self: self.env.company.currency_id
    )
