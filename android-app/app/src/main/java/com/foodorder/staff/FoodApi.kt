package com.foodorder.staff

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class OrderLine(
    val productId: Int,
    val name: String,
    val quantity: Double,
    val ownCupQuantity: Int,
    val note: String,
)

data class StaffOrder(
    val id: Int,
    val number: String,
    val status: String,
    val name: String,
    val phone: String,
    val department: String,
    val floor: String,
    val note: String,
    val total: Double,
    val currency: String,
    val source: String,
    val elapsedMinutes: Int,
    val paymentStatus: String,
    val paymentMethod: String,
    val amountReceived: Double,
    val changeAmount: Double,
    val lines: List<OrderLine>,
)

data class FoodProduct(
    val id: Int,
    val name: String,
    val price: Double,
    val currency: String,
    val category: String,
    val ownCupEligible: Boolean,
    val imageUrl: String,
)

data class Catalog(val products: List<FoodProduct>, val ownCupDiscount: Double)

data class WalkInLine(
    val productId: Int,
    val quantity: Int,
    val ownCupQuantity: Int,
    val note: String,
)

data class PrintJob(val id: Int, val payload: JSONObject)

class FoodApi(private val baseUrl: String, private val deviceKey: String) {
    private fun connection(path: String, method: String = "GET"): HttpURLConnection =
        (URL(baseUrl.trim().trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Device-Key", deviceKey.trim())
        }

    private fun execute(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        require(baseUrl.isNotBlank()) { "Enter the Odoo server URL" }
        require(deviceKey.isNotBlank()) { "Enter the device key for this screen" }
        val connection = connection(path, method)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "Odoo returned HTTP $status")
            }
            return JSONObject(text.ifBlank { "{}" })
        } finally {
            connection.disconnect()
        }
    }

    fun orders(status: String = "active"): List<StaffOrder> {
        val items = execute("/api/food/v1/staff/orders?status=$status").getJSONArray("orders")
        return (0 until items.length()).map { parseOrder(items.getJSONObject(it)) }
    }

    fun action(orderId: Int, action: String): StaffOrder = parseOrder(
        execute("/api/food/v1/staff/orders/$orderId/$action", "POST", JSONObject()).getJSONObject("order")
    )

    fun collectPayment(orderId: Int, amountReceived: Double): StaffOrder =
        parseOrder(
            execute(
                "/api/food/v1/staff/orders/$orderId/payment",
                "POST",
                JSONObject()
                    .put("amount_received", amountReceived),
            ).getJSONObject("order")
        )

    fun catalog(): Catalog {
        val response = execute("/api/food/v1/staff/catalog")
        val products = response.optJSONArray("products") ?: JSONArray()
        return Catalog(
            products = (0 until products.length()).map { index ->
                val item = products.getJSONObject(index)
                FoodProduct(
                    id = item.getInt("id"),
                    name = item.optString("name"),
                    price = item.optDouble("price"),
                    currency = item.optString("currency", "MMK"),
                    category = item.optString("category", "meals"),
                    ownCupEligible = item.optBoolean("own_cup_eligible"),
                    imageUrl = item.optString("image_url"),
                )
            },
            ownCupDiscount = response.optDouble("own_cup_discount", 500.0),
        )
    }

    fun createWalkIn(
        customerName: String,
        phone: String,
        orderNote: String,
        amountReceived: Double,
        lines: List<WalkInLine>,
    ): StaffOrder {
        val items = JSONArray()
        lines.forEach { line ->
            items.put(
                JSONObject()
                    .put("product_id", line.productId)
                    .put("quantity", line.quantity)
                    .put("own_cup_quantity", line.ownCupQuantity)
                    .put("note", line.note)
            )
        }
        val body = JSONObject()
            .put("customer_name", customerName)
            .put("phone", phone)
            .put("order_note", orderNote)
            .put("amount_received", amountReceived)
            .put("items", items)
        return parseOrder(execute("/api/food/v1/staff/walkin", "POST", body).getJSONObject("order"))
    }

    fun printJobs(): List<PrintJob> = parseJobs(execute("/api/food/v1/print/jobs"))
    fun failedPrintJobs(): List<PrintJob> = parseJobs(execute("/api/food/v1/print/jobs/failed"))

    fun retryPrint(jobId: Int) {
        execute("/api/food/v1/print/jobs/$jobId/retry", "POST", JSONObject())
    }

    fun acknowledge(jobId: Int, success: Boolean, error: String = "") {
        execute(
            "/api/food/v1/print/jobs/$jobId/ack", "POST",
            JSONObject().put("success", success).put("error", error.take(500)),
        )
    }

    private fun parseJobs(value: JSONObject): List<PrintJob> {
        val jobs = value.optJSONArray("jobs") ?: JSONArray()
        return (0 until jobs.length()).map {
            val job = jobs.getJSONObject(it)
            PrintJob(job.getInt("id"), job.getJSONObject("payload"))
        }
    }

    private fun parseOrder(value: JSONObject): StaffOrder {
        val customer = value.getJSONObject("customer")
        val lines = value.optJSONArray("lines") ?: JSONArray()
        return StaffOrder(
            id = value.getInt("id"),
            number = value.getString("number"),
            status = value.optString("status", "pending"),
            name = customer.optString("name", "Guest"),
            phone = customer.optString("phone"),
            department = customer.optString("department"),
            floor = customer.optString("floor"),
            note = customer.optString("note"),
            total = value.optDouble("total"),
            currency = value.optString("currency", "MMK"),
            source = value.optString("source", "web"),
            elapsedMinutes = value.optInt("elapsed_minutes"),
            paymentStatus = value.optString("payment_status", "unpaid"),
            paymentMethod = value.optString("payment_method"),
            amountReceived = value.optDouble("amount_received"),
            changeAmount = value.optDouble("change_amount"),
            lines = (0 until lines.length()).map { index ->
                val line = lines.getJSONObject(index)
                OrderLine(
                    productId = line.optInt("product_id"),
                    name = line.optString("name"),
                    quantity = line.optDouble("quantity"),
                    ownCupQuantity = line.optInt("own_cup_quantity"),
                    note = line.optString("note"),
                )
            },
        )
    }
}
