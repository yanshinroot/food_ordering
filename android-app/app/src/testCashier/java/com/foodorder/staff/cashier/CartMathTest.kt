package com.foodorder.staff.cashier

import com.foodorder.staff.net.FoodProduct
import com.foodorder.staff.net.SelectedModifier
import org.junit.Assert.assertEquals
import org.junit.Test

private fun product(price: Double, ownCupEligible: Boolean = false) = FoodProduct(
    id = 1, name = "Americano", price = price, currency = "MMK",
    category = "drinks", ownCupEligible = ownCupEligible, imageUrl = "", modifierGroups = emptyList(),
)

class CartMathTest {
    @Test
    fun `subtotal sums price times quantity across lines`() {
        val cart = listOf(
            WalkInCartLine(product(3500.0), quantity = 2),
            WalkInCartLine(product(6500.0), quantity = 1),
        )
        assertEquals(3500.0 * 2 + 6500.0, CartMath.subtotal(cart), 0.001)
    }

    @Test
    fun `modifier extra price is applied per unit`() {
        val modifiers = listOf(SelectedModifier(optionId = 1, optionName = "Large", groupName = "Size", priceExtra = 500.0))
        val cart = listOf(WalkInCartLine(product(3500.0), quantity = 3, modifiers = modifiers))
        // (3500 + 500) * 3
        assertEquals(12000.0, CartMath.subtotal(cart), 0.001)
    }

    @Test
    fun `own cup discount is per cup not per line`() {
        val cart = listOf(WalkInCartLine(product(3500.0, ownCupEligible = true), quantity = 3, ownCups = 2))
        assertEquals(2 * 500.0, CartMath.ownCupDiscount(cart, discountPerCup = 500.0), 0.001)
    }

    @Test
    fun `total never goes negative even if discount exceeds subtotal`() {
        val cart = listOf(WalkInCartLine(product(100.0, ownCupEligible = true), quantity = 1, ownCups = 1))
        assertEquals(0.0, CartMath.total(cart, discountPerCup = 500.0), 0.001)
    }

    @Test
    fun `empty cart totals to zero`() {
        assertEquals(0.0, CartMath.total(emptyList(), discountPerCup = 500.0), 0.001)
    }
}
