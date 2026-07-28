package com.foodorder.staff.core

/** Pure cash-payment arithmetic shared by the payment dialog — pulled out
 *  so it's unit-testable without Compose. */
object PaymentMath {
    fun changeDue(received: Double, total: Double): Double = (received - total).coerceAtLeast(0.0)
    fun canConfirmPayment(received: Double, total: Double): Boolean = received >= total
}
