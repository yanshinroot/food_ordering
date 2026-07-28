from odoo import api, fields, models
from odoo.exceptions import ValidationError


class FoodPromotion(models.Model):
    _name = "food.promotion"
    _description = "Food Ordering Promotion"
    _order = "priority desc, id desc"

    name = fields.Char(required=True)
    active = fields.Boolean(default=True)
    date_from = fields.Datetime(string="Active From")
    date_to = fields.Datetime(string="Active Until")
    min_order_amount = fields.Monetary(default=0.0)
    product_tmpl_ids = fields.Many2many(
        "product.template", "food_promotion_product_rel", "promotion_id", "product_tmpl_id",
        string="Eligible Products", help="Leave empty to apply to any product.",
    )
    category_ids = fields.Many2many(
        "food.menu.category", "food_promotion_category_rel", "promotion_id", "category_id",
        string="Eligible Categories", help="Leave empty to apply to any category.",
    )
    discount_type = fields.Selection(
        [("fixed", "Fixed Amount"), ("percentage", "Percentage"), ("free_product", "Free Product")],
        required=True,
        default="fixed",
    )
    discount_value = fields.Float(help="Amount for fixed, percent (0-100) for percentage.")
    free_product_id = fields.Many2one("product.product")
    free_product_qty = fields.Integer(default=1)
    usage_limit_per_customer = fields.Integer(default=0, help="Zero means unlimited.")
    priority = fields.Integer(default=10)
    stackable = fields.Boolean(help="If disabled, applying this promotion blocks all others.")
    currency_id = fields.Many2one(
        "res.currency", default=lambda self: self.env.company.currency_id
    )
    banner_headline = fields.Char(help="Short headline shown on the customer website banner.")
    banner_subtext = fields.Char(help="Supporting line shown under the headline.")
    show_on_banner = fields.Boolean(default=True)

    @api.constrains("discount_type", "discount_value", "free_product_id")
    def _check_discount_value(self):
        for promotion in self:
            if promotion.discount_type == "percentage" and not (0 < promotion.discount_value <= 100):
                raise ValidationError("Percentage discount must be between 0 and 100.")
            if promotion.discount_type == "fixed" and promotion.discount_value <= 0:
                raise ValidationError("Fixed discount amount must be positive.")
            if promotion.discount_type == "free_product" and not promotion.free_product_id:
                raise ValidationError("A free-product promotion needs a product selected.")

    def _is_currently_active(self, now=None):
        self.ensure_one()
        now = now or fields.Datetime.now()
        if self.date_from and now < self.date_from:
            return False
        if self.date_to and now > self.date_to:
            return False
        return True

    def _matches_products(self, product_tmpl_ids):
        self.ensure_one()
        if not self.product_tmpl_ids and not self.category_ids:
            return True
        product_tmpls = self.env["product.template"].sudo().browse(product_tmpl_ids)
        if self.product_tmpl_ids and (product_tmpls & self.product_tmpl_ids):
            return True
        if self.category_ids and (product_tmpls.mapped("food_category_id") & self.category_ids):
            return True
        return False

    @api.model
    def _get_active_banner(self):
        now = fields.Datetime.now()
        promotions = self.search([("show_on_banner", "=", True)], order="priority desc")
        for promotion in promotions:
            if promotion._is_currently_active(now):
                return promotion
        return self.browse()

    def _usage_count(self, phone):
        self.ensure_one()
        if not phone:
            return 0
        return self.env["sale.order"].sudo().search_count([
            ("food_promotion_id", "=", self.id),
            ("food_phone", "=", phone),
            ("food_status", "!=", "cancelled"),
        ])

    @api.model
    def _find_applicable(self, order_amount, product_tmpl_ids, phone=None):
        """Return the list of promotions to apply (server-computed, never
        trusts client input). Highest-priority non-stackable promotion wins
        outright; otherwise stackable promotions accumulate."""
        now = fields.Datetime.now()
        candidates = self.search([], order="priority desc, id desc")
        applicable = []
        for promotion in candidates:
            if not promotion._is_currently_active(now):
                continue
            if order_amount < promotion.min_order_amount:
                continue
            if not promotion._matches_products(product_tmpl_ids):
                continue
            if promotion.usage_limit_per_customer and promotion._usage_count(phone) >= promotion.usage_limit_per_customer:
                continue
            applicable.append(promotion)
            if not promotion.stackable:
                break
        return applicable

    def _discount_amount(self, order_amount):
        self.ensure_one()
        if self.discount_type == "fixed":
            return min(self.discount_value, order_amount)
        if self.discount_type == "percentage":
            return round(order_amount * self.discount_value / 100.0, 2)
        return 0.0
