package com.foodorder.staff.net

/** Central path constants — every request goes through here, never a
 *  string literal at the call site, so the whole API surface is visible
 *  in one place. */
object Endpoints {
    const val PAIRING_CLAIM = "/api/food/v1/pairing/claim"
    const val STAFF_ORDERS = "/api/food/v1/staff/orders"
    const val STAFF_CONFIG = "/api/food/v1/staff/config"
    fun orderAction(orderId: Int, action: String) = "/api/food/v1/staff/orders/$orderId/$action"
    fun orderPayment(orderId: Int) = "/api/food/v1/staff/orders/$orderId/payment"
    fun orderEvents(orderId: Int) = "/api/food/v1/staff/orders/$orderId/events"
    const val STAFF_CATALOG = "/api/food/v1/staff/catalog"
    const val STAFF_WALKIN = "/api/food/v1/staff/walkin"
    const val PRINT_JOBS = "/api/food/v1/print/jobs"
    const val PRINT_JOBS_FAILED = "/api/food/v1/print/jobs/failed"
    fun printRetry(jobId: Int) = "/api/food/v1/print/jobs/$jobId/retry"
    fun printAck(jobId: Int) = "/api/food/v1/print/jobs/$jobId/ack"
    const val PRINT_TEST = "/api/food/v1/print/test"
    const val SESSION_CURRENT = "/api/food/v1/staff/session/current"
    const val SESSION_OPEN = "/api/food/v1/staff/session/open"
    const val SESSION_MOVEMENT = "/api/food/v1/staff/session/movement"
    const val SESSION_CLOSE = "/api/food/v1/staff/session/close"
}
