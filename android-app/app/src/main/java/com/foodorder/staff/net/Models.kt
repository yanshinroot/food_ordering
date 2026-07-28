package com.foodorder.staff.net

data class ModifierSelection(val group: String, val option: String, val priceExtra: Double)

data class StaffOrderLine(
    val productId: Int,
    val name: String,
    val quantity: Double,
    val ownCupQuantity: Int,
    val note: String,
    val modifiersSummary: String,
    val modifiers: List<ModifierSelection>,
    val subtotal: Double,
)

data class StaffOrder(
    val id: Int,
    val number: String,
    val status: String,
    val stateVersion: Int,
    val source: String,
    val elapsedMinutes: Int,
    val paymentStatus: String,
    val paymentMethod: String,
    val amountReceived: Double,
    val changeAmount: Double,
    val promotion: String,
    val acceptedAt: String?,
    val readyAt: String?,
    val name: String,
    val phone: String,
    val department: String,
    val floor: String,
    val note: String,
    val ownCup: Boolean,
    val lines: List<StaffOrderLine>,
    val total: Double,
    val currency: String,
    /** target ("cashier"/"kitchen") -> print job state ("queued"/"printed"/"failed"/...). */
    val printJobs: Map<String, String>,
)

data class ModifierOption(val id: Int, val name: String, val priceExtra: Double)

data class ModifierGroup(
    val id: Int,
    val name: String,
    val selectionType: String,
    val required: Boolean,
    val minSelection: Int,
    val maxSelection: Int,
    val options: List<ModifierOption>,
)

data class FoodProduct(
    val id: Int,
    val name: String,
    val price: Double,
    val currency: String,
    val category: String,
    val ownCupEligible: Boolean,
    val imageUrl: String,
    val modifierGroups: List<ModifierGroup>,
)

data class PromotionBanner(val headline: String, val subtext: String)

data class Catalog(val products: List<FoodProduct>, val ownCupDiscount: Double, val promotion: PromotionBanner? = null)

data class SelectedModifier(val optionId: Int, val optionName: String, val groupName: String, val priceExtra: Double)

data class WalkInLine(
    val productId: Int,
    val quantity: Int,
    val ownCupQuantity: Int,
    val note: String,
    val modifierOptionIds: List<Int>,
)

data class PrintJob(
    val id: Int,
    val payload: org.json.JSONObject,
    val printerAddress: String,
    val paperWidthMm: Int,
    val cutterEnabled: Boolean,
    val encoding: String,
)

data class PairingClaim(val deviceId: Int, val deviceKey: String, val role: String, val name: String)

data class CashSession(
    val id: Int,
    val openingCash: Double,
    val saleTotal: Double,
    val refundTotal: Double,
    val cashInTotal: Double,
    val cashOutTotal: Double,
    val closingCashExpected: Double,
    val openedAt: String,
)

data class CashSessionCloseResult(val sessionId: Int, val expected: Double, val actual: Double, val difference: Double)

data class StaffConfig(
    val kitchenSlaWarnMinutes: Int,
    val kitchenSlaLateMinutes: Int,
    val shiftEnabled: Boolean,
    val refundEnabled: Boolean,
)

data class OrderEvent(
    val eventType: String,
    val previousStatus: String,
    val newStatus: String,
    val reason: String,
    val actor: String,
    val createdAt: String,
)
