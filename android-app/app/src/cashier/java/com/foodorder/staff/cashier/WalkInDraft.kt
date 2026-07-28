package com.foodorder.staff.cashier

import com.foodorder.staff.net.FoodProduct
import com.foodorder.staff.net.SelectedModifier

data class WalkInCartLine(
    val product: FoodProduct,
    val quantity: Int = 1,
    val ownCups: Int = 0,
    val note: String = "",
    val modifiers: List<SelectedModifier> = emptyList(),
)

/** Held in CashierHomeScreen (outlives the Walk-in dialog being opened and
 *  closed) so an unsent draft survives navigating away and back — it is
 *  never treated as a real order; only a successful server response
 *  (see WalkInScreen's submit handler) ever produces an order number. */
data class WalkInDraft(
    val cart: Map<String, WalkInCartLine> = emptyMap(),
    val customerName: String = "Walk-in Customer",
    val phone: String = "Walk-in",
    val orderNote: String = "",
) {
    val isEmpty: Boolean get() = cart.isEmpty()
}

/** Pure cart arithmetic, pulled out of WalkInScreen so it's unit-testable
 *  without Compose. Modifier extra prices are per-unit, matching how the
 *  server prices order lines (price_unit = product.lst_price + extra_price). */
object CartMath {
    fun subtotal(cart: Collection<WalkInCartLine>): Double =
        cart.sumOf { line -> (line.product.price + line.modifiers.sumOf { it.priceExtra }) * line.quantity }

    fun ownCupDiscount(cart: Collection<WalkInCartLine>, discountPerCup: Double): Double =
        cart.sumOf { it.ownCups } * discountPerCup

    fun total(cart: Collection<WalkInCartLine>, discountPerCup: Double): Double =
        (subtotal(cart) - ownCupDiscount(cart, discountPerCup)).coerceAtLeast(0.0)
}
