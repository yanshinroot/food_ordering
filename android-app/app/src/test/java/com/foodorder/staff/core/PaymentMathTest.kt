package com.foodorder.staff.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scenarios 12 & 13: Cashier payment validation and cash change calculation. */
class PaymentMathTest {
    @Test
    fun `exact payment is confirmable with zero change`() {
        assertTrue(PaymentMath.canConfirmPayment(received = 4025.0, total = 4025.0))
        assertEquals(0.0, PaymentMath.changeDue(4025.0, 4025.0), 0.0001)
    }

    @Test
    fun `overpayment is confirmable and computes positive change`() {
        assertTrue(PaymentMath.canConfirmPayment(received = 5000.0, total = 4025.0))
        assertEquals(975.0, PaymentMath.changeDue(5000.0, 4025.0), 0.0001)
    }

    @Test
    fun `underpayment is not confirmable and change never goes negative`() {
        assertFalse(PaymentMath.canConfirmPayment(received = 3000.0, total = 4025.0))
        assertEquals(0.0, PaymentMath.changeDue(3000.0, 4025.0), 0.0001)
    }
}
