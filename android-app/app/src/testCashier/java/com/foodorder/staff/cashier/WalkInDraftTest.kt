package com.foodorder.staff.cashier

import com.foodorder.staff.net.FoodProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun product(id: Int) = FoodProduct(
    id = id, name = "Item $id", price = 1000.0, currency = "MMK",
    category = "meals", ownCupEligible = false, imageUrl = "", modifierGroups = emptyList(),
)

/** Scenario 24: Walk-in draft persistence — the draft is plain immutable
 *  state (copy()-based updates), so editing one field (e.g. the customer
 *  name) never drops the cart, and the draft is never mistaken for a real
 *  order (there is no order-number field on WalkInDraft at all — that only
 *  ever comes back from the server, see FoodApiClientTest's parseOrder
 *  coverage of scenario 25). */
class WalkInDraftTest {
    @Test
    fun `editing customer fields preserves the existing cart`() {
        val draft = WalkInDraft(cart = mapOf("1:" to WalkInCartLine(product(1), quantity = 2)))
        val updated = draft.copy(customerName = "Jane", phone = "09-123")
        assertEquals(draft.cart, updated.cart)
        assertEquals("Jane", updated.customerName)
    }

    @Test
    fun `a fresh draft is empty`() {
        assertTrue(WalkInDraft().isEmpty)
    }

    @Test
    fun `adding a line makes the draft non-empty`() {
        val draft = WalkInDraft(cart = mapOf("1:" to WalkInCartLine(product(1))))
        assertTrue(!draft.isEmpty)
    }

    @Test
    fun `default customer fields are sensible walk-in placeholders`() {
        val draft = WalkInDraft()
        assertEquals("Walk-in Customer", draft.customerName)
        assertEquals("Walk-in", draft.phone)
    }
}
