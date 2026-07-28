package com.foodorder.staff.net

import com.foodorder.staff.core.ApiError
import com.foodorder.staff.core.ApiResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodApiClientTest {
    private fun client() = FoodApiClient({ "https://odoo.example.com" }, { "device-key" })

    private fun response(status: Int, body: JSONObject = JSONObject()) =
        FoodApiClient.RawResponse(status, body)

    @Test
    fun `2xx status is a success passthrough`() {
        val body = JSONObject().put("ok", true)
        val result = client().interpret(response(200, body))
        assertTrue(result is ApiResult.Success)
        assertEquals(true, (result as ApiResult.Success).value.optBoolean("ok"))
    }

    @Test
    fun `401 maps to Unauthorized`() {
        val result = client().interpret(response(401, JSONObject().put("error", "unauthorized")))
        assertTrue((result as ApiResult.Failure).error is ApiError.Unauthorized)
    }

    /** Scenario 10 & 11: stale_version is distinguished from an ordinary
     *  409 conflict so the caller can react specifically (refresh just that
     *  order) instead of treating every 409 the same way. */
    @Test
    fun `409 with stale_version error_code maps to StaleVersion`() {
        val body = JSONObject().put("error", "This order changed on the server.").put("error_code", "stale_version")
        val result = client().interpret(response(409, body))
        val error = (result as ApiResult.Failure).error
        assertTrue(error is ApiError.StaleVersion)
        assertEquals("This order changed on the server.", error.userMessage)
    }

    @Test
    fun `409 without stale_version error_code maps to a plain Conflict`() {
        val body = JSONObject().put("error", "Only paid orders can be refunded.")
        val result = client().interpret(response(409, body))
        assertTrue((result as ApiResult.Failure).error is ApiError.Conflict)
    }

    @Test
    fun `429 maps to RateLimited and 400 maps to Validation`() {
        assertTrue((client().interpret(response(429)) as ApiResult.Failure).error is ApiError.RateLimited)
        assertTrue((client().interpret(response(400)) as ApiResult.Failure).error is ApiError.Validation)
    }

    @Test
    fun `5xx maps to ServerError and is safe to auto retry`() {
        val error = (client().interpret(response(502)) as ApiResult.Failure).error
        assertTrue(error is ApiError.ServerError)
        assertTrue(error.isSafeToAutoRetry)
    }

    /** Scenario 25: walk-in server-confirmation requirement — parseOrder
     *  only ever produces a StaffOrder (with a real server-assigned number)
     *  from an actual server response body; there is no code path that
     *  fabricates one from local draft state. */
    @Test
    fun `parseOrder builds a full order from the server's serialize_order shape`() {
        val json = JSONObject(
            """
            {
              "id": 482, "number": "S00482", "status": "accepted", "state_version": 3,
              "source": "cashier", "elapsed_minutes": 4, "payment_status": "paid",
              "payment_method": "cash", "amount_received": 5000, "change_amount": 975,
              "promotion": "Happy Hour", "accepted_at": "2026-01-01 10:00:00", "ready_at": false,
              "print_jobs": {"cashier": "printed", "kitchen": "queued"},
              "customer": {"name": "Walk-in Customer", "phone": "Walk-in", "department": "Counter", "floor": "Walk-in", "note": "", "own_cup": false},
              "lines": [
                {"product_id": 12, "name": "Americano", "quantity": 1, "own_cup_quantity": 0, "note": "",
                 "modifiers": "Size: Large", "modifier_details": [{"group": "Size", "option": "Large", "price_extra": 500}],
                 "subtotal": 4025}
              ],
              "total": 4025, "currency": "MMK", "created_at": "2026-01-01 09:56:00"
            }
            """.trimIndent()
        )
        val order = client().parseOrder(json)
        assertEquals("S00482", order.number)
        assertEquals(3, order.stateVersion)
        assertEquals("printed", order.printJobs["cashier"])
        assertEquals("queued", order.printJobs["kitchen"])
        assertEquals("2026-01-01 10:00:00", order.acceptedAt)
        assertEquals(null, order.readyAt)
        assertEquals(1, order.lines.size)
        assertEquals("Large", order.lines[0].modifiers[0].option)
    }

    /** Scenario 14: cash-session state parsing. */
    @Test
    fun `parseSession builds a CashSession from the session endpoints' shape`() {
        val json = JSONObject(
            """{"id": 9, "opening_cash": 50000, "sale_total": 12000, "refund_total": 0,
                 "cash_in_total": 2000, "cash_out_total": 0, "closing_cash_expected": 64000,
                 "opened_at": "2026-01-01 08:00:00"}""".trimIndent()
        )
        val session = client().parseSession(json)
        assertEquals(9, session.id)
        assertEquals(64000.0, session.closingCashExpected, 0.001)
    }
}
