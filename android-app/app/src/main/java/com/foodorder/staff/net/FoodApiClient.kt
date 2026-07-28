package com.foodorder.staff.net

import com.foodorder.staff.core.ApiError
import com.foodorder.staff.core.ApiResult
import com.foodorder.staff.core.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Shared, role-agnostic device-key API client. A Cashier-flavor build and a
 * Kitchen-flavor build both use this exact class; the only difference is
 * which endpoints their screens choose to call and which paired device key
 * is supplied — the server is what actually enforces which target a given
 * key may act as (see controllers/api.py `_device()` / `device.target`
 * checks), never this client.
 *
 * Every function returns [ApiResult] — nothing here throws for expected
 * failure modes, and no raw exception message or stack trace is ever
 * surfaced to a caller. The device key itself is never included in any
 * logging statement in this class.
 */
class FoodApiClient(
    private val baseUrlProvider: () -> String,
    private val deviceKeyProvider: () -> String,
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val REQUEST_TIMEOUT_MS = 25_000L
    }

    private fun normalizedBaseUrl(): String = baseUrlProvider().trim().trimEnd('/')

    internal data class RawResponse(val status: Int, val body: JSONObject)

    /** Retries GETs and idempotent POSTs with capped backoff; the same
     *  [idempotencyHeaders] are sent on every attempt so a retried POST is
     *  safe to replay on the server rather than creating a duplicate. */
    private suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        idempotencyKey: String? = null,
        requestId: String? = null,
        allowRetry: Boolean = method == "GET",
    ): ApiResult<JSONObject> {
        var attempt = 1
        while (true) {
            val outcome = executeOnce(path, method, body, idempotencyKey, requestId)
            if (outcome is ApiResult.Success) return outcome
            val error = (outcome as ApiResult.Failure).error
            if (!allowRetry || !RetryPolicy.shouldRetry(attempt, error)) return outcome
            delay(RetryPolicy.delayMillisFor(attempt))
            attempt++
        }
    }

    private suspend fun executeOnce(
        path: String,
        method: String,
        body: JSONObject?,
        idempotencyKey: String?,
        requestId: String?,
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val baseUrl = normalizedBaseUrl()
        if (baseUrl.isBlank()) return@withContext ApiResult.Failure(ApiError.Network("No server address is configured."))
        val deviceKey = deviceKeyProvider()
        try {
            val raw = withTimeout(REQUEST_TIMEOUT_MS) {
                performRequest(baseUrl, path, method, body, deviceKey, idempotencyKey, requestId)
            }
            interpret(raw)
        } catch (timeout: TimeoutCancellationException) {
            ApiResult.Failure(ApiError.Timeout("The server took too long to respond. Try again."))
        } catch (timeout: SocketTimeoutException) {
            ApiResult.Failure(ApiError.Timeout("The server took too long to respond. Try again."))
        } catch (unknownHost: UnknownHostException) {
            ApiResult.Failure(ApiError.Network("Can't reach the server. Check the network and server address."))
        } catch (io: IOException) {
            ApiResult.Failure(ApiError.Network("Network error. Check the connection and try again."))
        } catch (malformed: JSONException) {
            ApiResult.Failure(ApiError.Malformed("The server sent an unexpected response."))
        }
    }

    private fun performRequest(
        baseUrl: String,
        path: String,
        method: String,
        body: JSONObject?,
        deviceKey: String,
        idempotencyKey: String?,
        requestId: String?,
    ): RawResponse {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (deviceKey.isNotBlank()) setRequestProperty("X-Device-Key", deviceKey.trim())
            if (idempotencyKey != null) setRequestProperty("X-Idempotency-Key", idempotencyKey)
            if (requestId != null) setRequestProperty("X-Request-Id", requestId)
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            return RawResponse(status, json)
        } finally {
            connection.disconnect()
        }
    }

    internal fun interpret(raw: RawResponse): ApiResult<JSONObject> {
        if (raw.status in 200..299) return ApiResult.Success(raw.body)
        val serverMessage = raw.body.optString("error").takeIf { it.isNotBlank() }
        val errorCode = raw.body.optString("error_code")
        val error: ApiError = when (raw.status) {
            401 -> ApiError.Unauthorized(serverMessage ?: "This device is not recognized. Re-pair the device.")
            403 -> ApiError.Forbidden(serverMessage ?: "This screen can't perform that action.")
            404 -> ApiError.NotFound(serverMessage ?: "That order or job could not be found.")
            409 -> if (errorCode == "stale_version") {
                // The backend's stale_version response doesn't echo the order id back
                // (see controllers/api.py _run_action) — 0 here means "unknown",
                // and callers that already know which order they acted on
                // (orderAction/collectPayment below) attach the real id themselves.
                ApiError.StaleVersion(serverMessage ?: "This order changed. Refreshing…", 0)
            } else {
                ApiError.Conflict(serverMessage ?: "That action can't be completed right now.")
            }
            413 -> ApiError.Validation("That request was too large.")
            429 -> ApiError.RateLimited(serverMessage ?: "Too many requests. Wait a moment and try again.")
            400 -> ApiError.Validation(serverMessage ?: "Check the entered details and try again.")
            in 500..599 -> ApiError.ServerError("The server hit a problem. Try again shortly.", raw.status)
            else -> ApiError.ServerError(serverMessage ?: "Something went wrong (HTTP ${raw.status}).", raw.status)
        }
        return ApiResult.Failure(error)
    }

    // ------------------------------------------------------------------
    // Pairing (no device key required yet)
    // ------------------------------------------------------------------
    suspend fun claimPairing(
        serverUrl: String,
        code: String,
        deviceName: String,
        deviceUuid: String,
        appVersion: String,
    ): ApiResult<PairingClaim> {
        val body = JSONObject()
            .put("code", code)
            .put("device_name", deviceName)
            .put("device_uuid", deviceUuid)
            .put("app_version", appVersion)
        val client = FoodApiClient({ serverUrl }, { "" })
        return client.request(Endpoints.PAIRING_CLAIM, "POST", body, allowRetry = false).let { result ->
            when (result) {
                is ApiResult.Success -> ApiResult.Success(
                    PairingClaim(
                        deviceId = result.value.optInt("device_id"),
                        deviceKey = result.value.optString("device_key"),
                        role = result.value.optString("role"),
                        name = result.value.optString("name"),
                    )
                )
                is ApiResult.Failure -> result
            }
        }
    }

    // ------------------------------------------------------------------
    // Orders
    // ------------------------------------------------------------------
    suspend fun orders(status: String, sinceVersion: Int? = null): ApiResult<List<StaffOrder>> {
        val query = buildString {
            append(Endpoints.STAFF_ORDERS).append("?status=").append(status)
            if (sinceVersion != null) append("&since_version=").append(sinceVersion)
        }
        return request(query).mapValue { json ->
            val items = json.optJSONArray("orders") ?: JSONArray()
            (0 until items.length()).map { parseOrder(items.getJSONObject(it)) }
        }
    }

    suspend fun orderAction(
        orderId: Int,
        action: String,
        requestId: String,
        expectedVersion: Int?,
        extra: JSONObject = JSONObject(),
    ): ApiResult<StaffOrder> {
        val body = extra.put("request_id", requestId)
        if (expectedVersion != null) body.put("expected_version", expectedVersion)
        return request(Endpoints.orderAction(orderId, action), "POST", body, requestId = requestId, allowRetry = false)
            .mapValue { parseOrder(it.getJSONObject("order")) }
            .withKnownOrderId(orderId)
    }

    suspend fun collectPayment(
        orderId: Int,
        amountReceived: Double,
        requestId: String,
        expectedVersion: Int?,
    ): ApiResult<StaffOrder> {
        val body = JSONObject().put("amount_received", amountReceived).put("request_id", requestId)
        if (expectedVersion != null) body.put("expected_version", expectedVersion)
        return request(Endpoints.orderPayment(orderId), "POST", body, requestId = requestId, allowRetry = false)
            .mapValue { parseOrder(it.getJSONObject("order")) }
            .withKnownOrderId(orderId)
    }

    suspend fun staffConfig(): ApiResult<StaffConfig> = request(Endpoints.STAFF_CONFIG).mapValue { json ->
        StaffConfig(
            kitchenSlaWarnMinutes = json.optInt("kitchen_sla_warn_minutes", 10),
            kitchenSlaLateMinutes = json.optInt("kitchen_sla_late_minutes", 20),
        )
    }

    suspend fun orderEvents(orderId: Int): ApiResult<List<OrderEvent>> =
        request(Endpoints.orderEvents(orderId)).mapValue { json ->
            val events = json.optJSONArray("events") ?: JSONArray()
            (0 until events.length()).map { index ->
                val event = events.getJSONObject(index)
                OrderEvent(
                    eventType = event.optString("event_type"),
                    previousStatus = event.optString("previous_status"),
                    newStatus = event.optString("new_status"),
                    reason = event.optString("reason"),
                    actor = event.optString("actor"),
                    createdAt = event.optString("created_at"),
                )
            }
        }

    // ------------------------------------------------------------------
    // Catalog / walk-in
    // ------------------------------------------------------------------
    suspend fun catalog(): ApiResult<Catalog> = request(Endpoints.STAFF_CATALOG).mapValue { json ->
        val products = json.optJSONArray("products") ?: JSONArray()
        Catalog(
            products = (0 until products.length()).map { parseProduct(products.getJSONObject(it)) },
            ownCupDiscount = json.optDouble("own_cup_discount", 500.0),
            promotion = json.optJSONObject("promotion_banner")?.let {
                PromotionBanner(headline = it.optString("headline"), subtext = it.optString("subtext"))
            },
        )
    }

    suspend fun createWalkIn(
        customerName: String,
        phone: String,
        orderNote: String,
        amountReceived: Double,
        lines: List<WalkInLine>,
        idempotencyKey: String,
    ): ApiResult<StaffOrder> {
        val items = JSONArray()
        lines.forEach { line ->
            items.put(
                JSONObject()
                    .put("product_id", line.productId)
                    .put("quantity", line.quantity)
                    .put("own_cup_quantity", line.ownCupQuantity)
                    .put("note", line.note)
                    .put("modifier_option_ids", JSONArray(line.modifierOptionIds))
            )
        }
        val body = JSONObject()
            .put("customer_name", customerName)
            .put("phone", phone)
            .put("order_note", orderNote)
            .put("amount_received", amountReceived)
            .put("items", items)
            .put("idempotency_key", idempotencyKey)
        return request(Endpoints.STAFF_WALKIN, "POST", body, idempotencyKey = idempotencyKey, allowRetry = false)
            .mapValue { parseOrder(it.getJSONObject("order")) }
    }

    // ------------------------------------------------------------------
    // Print queue
    // ------------------------------------------------------------------
    suspend fun printJobs(): ApiResult<List<PrintJob>> = request(Endpoints.PRINT_JOBS).mapValue(::parseJobs)
    suspend fun failedPrintJobs(): ApiResult<List<PrintJob>> = request(Endpoints.PRINT_JOBS_FAILED).mapValue(::parseJobs)

    suspend fun retryPrintJob(jobId: Int): ApiResult<Unit> =
        request(Endpoints.printRetry(jobId), "POST", JSONObject(), allowRetry = false).mapValue { }

    suspend fun acknowledgePrint(jobId: Int, success: Boolean, error: String = ""): ApiResult<Unit> =
        request(
            Endpoints.printAck(jobId), "POST",
            JSONObject().put("success", success).put("error", error.take(500)),
            allowRetry = false,
        ).mapValue { }

    // ------------------------------------------------------------------
    // Cash session
    // ------------------------------------------------------------------
    suspend fun currentSession(): ApiResult<CashSession?> = request(Endpoints.SESSION_CURRENT).mapValue { json ->
        json.optJSONObject("session")?.let(::parseSession)
    }

    suspend fun openSession(openingCash: Double): ApiResult<CashSession> =
        request(Endpoints.SESSION_OPEN, "POST", JSONObject().put("opening_cash", openingCash), allowRetry = false)
            .mapValue { parseSession(it.getJSONObject("session")) }

    suspend fun recordMovement(type: String, amount: Double, reason: String): ApiResult<CashSession> =
        request(
            Endpoints.SESSION_MOVEMENT, "POST",
            JSONObject().put("movement_type", type).put("amount", amount).put("reason", reason),
            allowRetry = false,
        ).mapValue { parseSession(it.getJSONObject("session")) }

    suspend fun closeSession(closingCashActual: Double): ApiResult<CashSessionCloseResult> =
        request(Endpoints.SESSION_CLOSE, "POST", JSONObject().put("closing_cash_actual", closingCashActual), allowRetry = false)
            .mapValue {
                CashSessionCloseResult(
                    sessionId = it.optInt("session_id"),
                    expected = it.optDouble("expected"),
                    actual = it.optDouble("actual"),
                    difference = it.optDouble("difference"),
                )
            }

    // ------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------
    internal fun parseSession(value: JSONObject) = CashSession(
        id = value.optInt("id"),
        openingCash = value.optDouble("opening_cash"),
        saleTotal = value.optDouble("sale_total"),
        refundTotal = value.optDouble("refund_total"),
        cashInTotal = value.optDouble("cash_in_total"),
        cashOutTotal = value.optDouble("cash_out_total"),
        closingCashExpected = value.optDouble("closing_cash_expected"),
        openedAt = value.optString("opened_at"),
    )

    internal fun parseJobs(value: JSONObject): List<PrintJob> {
        val jobs = value.optJSONArray("jobs") ?: JSONArray()
        return (0 until jobs.length()).map {
            val job = jobs.getJSONObject(it)
            PrintJob(
                id = job.getInt("id"),
                payload = job.getJSONObject("payload"),
                printerAddress = job.optString("printer"),
                paperWidthMm = job.optString("paper_width_mm", "80").toIntOrNull() ?: 80,
                cutterEnabled = job.optBoolean("cutter_enabled", true),
                encoding = job.optString("encoding", "utf-8"),
            )
        }
    }

    internal fun parseProduct(item: JSONObject): FoodProduct {
        val groups = item.optJSONArray("modifier_groups") ?: JSONArray()
        return FoodProduct(
            id = item.getInt("id"),
            name = item.optString("name"),
            price = item.optDouble("price"),
            currency = item.optString("currency", "MMK"),
            category = item.optString("category", "meals"),
            ownCupEligible = item.optBoolean("own_cup_eligible"),
            imageUrl = item.optString("image_url"),
            modifierGroups = (0 until groups.length()).map { parseModifierGroup(groups.getJSONObject(it)) },
        )
    }

    internal fun parseModifierGroup(group: JSONObject): ModifierGroup {
        val options = group.optJSONArray("options") ?: JSONArray()
        return ModifierGroup(
            id = group.getInt("id"),
            name = group.optString("name"),
            selectionType = group.optString("selection_type", "single"),
            required = group.optBoolean("required"),
            minSelection = group.optInt("min_selection"),
            maxSelection = group.optInt("max_selection"),
            options = (0 until options.length()).map { index ->
                val option = options.getJSONObject(index)
                ModifierOption(option.getInt("id"), option.optString("name"), option.optDouble("price_extra"))
            },
        )
    }

    internal fun parseOrder(value: JSONObject): StaffOrder {
        val customer = value.getJSONObject("customer")
        val lines = value.optJSONArray("lines") ?: JSONArray()
        return StaffOrder(
            id = value.getInt("id"),
            number = value.getString("number"),
            status = value.optString("status", "pending"),
            stateVersion = value.optInt("state_version"),
            source = value.optString("source", "web"),
            elapsedMinutes = value.optInt("elapsed_minutes"),
            paymentStatus = value.optString("payment_status", "unpaid"),
            paymentMethod = value.optString("payment_method"),
            amountReceived = value.optDouble("amount_received"),
            changeAmount = value.optDouble("change_amount"),
            promotion = value.optString("promotion"),
            acceptedAt = value.optString("accepted_at").takeIf { it.isNotBlank() && it != "false" },
            readyAt = value.optString("ready_at").takeIf { it.isNotBlank() && it != "false" },
            name = customer.optString("name", "Guest"),
            phone = customer.optString("phone"),
            department = customer.optString("department"),
            floor = customer.optString("floor"),
            note = customer.optString("note"),
            ownCup = customer.optBoolean("own_cup"),
            total = value.optDouble("total"),
            currency = value.optString("currency", "MMK"),
            printJobs = value.optJSONObject("print_jobs")?.let { jobs ->
                jobs.keys().asSequence().associateWith { key -> jobs.optString(key) }
            } ?: emptyMap(),
            lines = (0 until lines.length()).map { index ->
                val line = lines.getJSONObject(index)
                val modifierDetails = line.optJSONArray("modifier_details") ?: JSONArray()
                StaffOrderLine(
                    productId = line.optInt("product_id"),
                    name = line.optString("name"),
                    quantity = line.optDouble("quantity"),
                    ownCupQuantity = line.optInt("own_cup_quantity"),
                    note = line.optString("note"),
                    modifiersSummary = line.optString("modifiers"),
                    modifiers = (0 until modifierDetails.length()).map { modIndex ->
                        val modifier = modifierDetails.getJSONObject(modIndex)
                        ModifierSelection(
                            group = modifier.optString("group"),
                            option = modifier.optString("option"),
                            priceExtra = modifier.optDouble("price_extra"),
                        )
                    },
                    subtotal = line.optDouble("subtotal"),
                )
            },
        )
    }

    private fun <T> ApiResult<T>.withKnownOrderId(orderId: Int): ApiResult<T> = when {
        this is ApiResult.Failure && error is ApiError.StaleVersion ->
            ApiResult.Failure(error.copy(orderId = orderId))
        else -> this
    }

    private inline fun <T, R> ApiResult<T>.mapValue(transform: (T) -> R): ApiResult<R> = when (this) {
        is ApiResult.Success -> try {
            ApiResult.Success(transform(value))
        } catch (malformed: JSONException) {
            ApiResult.Failure(ApiError.Malformed("The server sent an unexpected response."))
        }
        is ApiResult.Failure -> this
    }
}
