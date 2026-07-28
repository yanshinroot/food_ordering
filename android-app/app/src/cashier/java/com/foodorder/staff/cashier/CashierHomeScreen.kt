package com.foodorder.staff.cashier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.foodorder.staff.AppEnvironment
import com.foodorder.staff.core.ApiError
import com.foodorder.staff.core.ApiResult
import com.foodorder.staff.net.Catalog
import com.foodorder.staff.net.StaffOrder
import com.foodorder.staff.poll.AdaptivePoller
import com.foodorder.staff.sound.AlertDedupeGuard
import com.foodorder.staff.sound.AlertPlayer
import com.foodorder.staff.ui.components.ConnectionChip
import com.foodorder.staff.ui.components.EmptyState
import com.foodorder.staff.ui.components.OfflineBanner
import com.foodorder.staff.ui.components.StatusBanner
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class CashierTab(val label: String) { NEW("New"), IN_PROGRESS("Accepted"), READY("Ready"), RECENT("Recent") }

@Composable
fun CashierHomeScreen(env: AppEnvironment) {
    var orders by remember { mutableStateOf(emptyList<StaffOrder>()) }
    var catalog by remember { mutableStateOf(Catalog(emptyList(), 500.0)) }
    var session by remember { mutableStateOf<com.foodorder.staff.net.CashSession?>(null) }
    var search by remember { mutableStateOf("") }
    var deptFilter by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var lastSyncMillis by remember { mutableStateOf<Long?>(null) }
    var failedPrintCount by remember { mutableStateOf(0) }
    var busyOrderId by remember { mutableStateOf<Int?>(null) }
    var statusMessage by remember { mutableStateOf("Connecting to Odoo…") }
    var statusError by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(CashierTab.NEW) }
    var showWalkIn by remember { mutableStateOf(false) }
    var walkInDraft by remember { mutableStateOf(WalkInDraft()) }
    var paymentOrder by remember { mutableStateOf<StaffOrder?>(null) }
    var refundOrder by remember { mutableStateOf<StaffOrder?>(null) }
    var detailOrder by remember { mutableStateOf<StaffOrder?>(null) }
    var showSession by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(com.foodorder.staff.net.StaffConfig(10, 20, shiftEnabled = false, refundEnabled = false)) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf<StaffOrder?>(null) }
    val alertGuard = remember { AlertDedupeGuard() }
    val scope = rememberCoroutineScope()
    val ordersPoller = remember { AdaptivePoller(baseIntervalMs = 6_000) }
    val printPoller = remember { AdaptivePoller(baseIntervalMs = 4_000) }

    fun handleFailure(error: ApiError) {
        env.sessionManager.reactTo(error)
        connected = error !is ApiError.Network && error !is ApiError.Timeout
        statusMessage = error.userMessage
        statusError = true
    }

    suspend fun refreshOrders(): Boolean {
        val activeResult = env.apiClient.orders("active")
        val recentResult = env.apiClient.orders("recent")
        if (activeResult is ApiResult.Failure) { handleFailure(activeResult.error); return false }
        if (recentResult is ApiResult.Failure) { handleFailure(recentResult.error); return false }
        val active = (activeResult as ApiResult.Success).value
        val recent = (recentResult as ApiResult.Success).value
        orders = active + recent
        connected = true
        statusError = false
        lastSyncMillis = System.currentTimeMillis()
        statusMessage = "Live — ${active.size} active order${if (active.size == 1) "" else "s"}"
        val newIds = active.filter { it.status == "pending" }.map { it.id }.toSet()
        if (alertGuard.hasNewArrivals(newIds)) AlertPlayer.play(env.activity, scope, env.uiPreferences.soundEnabled)
        return true
    }

    suspend fun refreshSession() {
        if (!config.shiftEnabled) return
        when (val result = env.apiClient.currentSession()) {
            is ApiResult.Success -> session = result.value
            is ApiResult.Failure -> env.sessionManager.reactTo(result.error)
        }
    }

    suspend fun printTick(): Boolean {
        val result = env.printQueue.claimAndPrintQueued()
        val failedResult = env.apiClient.failedPrintJobs()
        if (failedResult is ApiResult.Success) failedPrintCount = failedResult.value.size
        return result.failed == 0
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    ordersPoller.refreshNow(scope) { refreshOrders() }
                    printPoller.refreshNow(scope) { printTick() }
                    scope.launch { refreshSession() }
                }
                Lifecycle.Event.ON_STOP -> {
                    ordersPoller.stop()
                    printPoller.stop()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ordersPoller.stop()
            printPoller.stop()
        }
    }

    LaunchedEffect(Unit) {
        when (val result = env.apiClient.catalog()) {
            is ApiResult.Success -> catalog = result.value
            is ApiResult.Failure -> handleFailure(result.error)
        }
        when (val result = env.apiClient.staffConfig()) {
            is ApiResult.Success -> config = result.value
            is ApiResult.Failure -> {} // non-fatal — falls back to shift/refund hidden
        }
    }

    fun matches(order: StaffOrder): Boolean {
        val term = search.trim()
        val searchOk = term.isBlank() || listOf(order.number, order.name, order.phone, order.department, order.floor)
            .any { it.contains(term, ignoreCase = true) }
        val deptTerm = deptFilter.trim()
        val deptOk = deptTerm.isBlank() || order.department.contains(deptTerm, ignoreCase = true) || order.floor.contains(deptTerm, ignoreCase = true)
        return searchOk && deptOk
    }

    val visible = orders.filter(::matches)
    val newOrders = visible.filter { it.status == "pending" }
    val inProgress = visible.filter { it.status in listOf("accepted", "preparing") }
    val ready = visible.filter { it.status == "ready" }
    val recent = visible.filter { it.status in listOf("completed", "cancelled") }

    fun runAction(order: StaffOrder, action: String, requireReason: Boolean = false, reason: String? = null) {
        if (busyOrderId != null) return
        busyOrderId = order.id
        scope.launch {
            val requestId = env.idempotency.keyFor("$action:${order.id}")
            val body = if (reason != null) org.json.JSONObject().put("reason", reason) else org.json.JSONObject()
            when (val result = env.apiClient.orderAction(order.id, action, requestId, order.stateVersion, body)) {
                is ApiResult.Success -> {
                    env.idempotency.retire("$action:${order.id}")
                    statusMessage = "${order.number} updated"
                    statusError = false
                    if (action == "accept") scope.launch { printTick() }
                    refreshOrders()
                }
                is ApiResult.Failure -> {
                    if (result.error is ApiError.StaleVersion) {
                        statusMessage = "${order.number} changed on the server — refreshed."
                        statusError = true
                        refreshOrders()
                    } else {
                        handleFailure(result.error)
                    }
                }
            }
            busyOrderId = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        CashierToolbar(
            session = session, connected = connected, failedPrintCount = failedPrintCount,
            shiftEnabled = config.shiftEnabled,
            lastSyncMillis = lastSyncMillis, onOpenSession = { showSession = true },
            onWalkIn = {
                if (catalog.products.isEmpty()) {
                    scope.launch {
                        when (val result = env.apiClient.catalog()) {
                            is ApiResult.Success -> { catalog = result.value; showWalkIn = true }
                            is ApiResult.Failure -> handleFailure(result.error)
                        }
                    }
                } else {
                    showWalkIn = true
                }
            },
            onSettings = { showSettings = true },
            onManualRefresh = { scope.launch { ordersPoller.refreshNow(scope) { refreshOrders() } } },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                search, { search = it }, label = { Text("Search", fontSize = 11.sp) }, singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp), modifier = Modifier.weight(1.3f),
            )
            OutlinedTextField(
                deptFilter, { deptFilter = it }, label = { Text("Dept/Floor", fontSize = 11.sp) }, singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp), modifier = Modifier.weight(1f),
            )
        }

        if (!connected) OfflineBanner(lastSyncMillis?.let { formatSyncTime(it) })
        else if (statusError || busyOrderId != null) StatusBanner(statusMessage, statusError, busyOrderId != null) { scope.launch { refreshOrders() } }

        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                CashierTab.NEW to newOrders.size, CashierTab.IN_PROGRESS to inProgress.size,
                CashierTab.READY to ready.size, CashierTab.RECENT to recent.size,
            ).forEach { (option, count) ->
                val selected = tab == option
                if (selected) Button(onClick = { tab = option }, modifier = Modifier.weight(1f)) { Text("${option.label} ($count)", fontSize = 11.sp) }
                else OutlinedButton(onClick = { tab = option }, modifier = Modifier.weight(1f)) { Text("${option.label} ($count)", fontSize = 11.sp) }
            }
        }

        val shown = when (tab) {
            CashierTab.NEW -> newOrders
            CashierTab.IN_PROGRESS -> inProgress
            CashierTab.READY -> ready
            CashierTab.RECENT -> recent
        }
        if (shown.isEmpty()) {
            EmptyState(
                when (tab) {
                    CashierTab.NEW -> "No new orders"
                    CashierTab.IN_PROGRESS -> "Nothing in progress"
                    CashierTab.READY -> "Nothing waiting"
                    CashierTab.RECENT -> "No recent orders"
                },
                "Orders refresh automatically.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = StaffOrder::id) { order ->
                    CashierOrderCard(
                        order = order, busy = busyOrderId == order.id,
                        refundEnabled = config.refundEnabled,
                        onDetails = { detailOrder = order },
                        onAccept = { runAction(order, "accept") },
                        onCancel = { confirmCancel = order },
                        onPayment = { paymentOrder = order },
                        onComplete = { runAction(order, "complete") },
                        onReprint = { runAction(order, "reprint") },
                        onRefund = { refundOrder = order },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showWalkIn) {
        WalkInScreen(
            env = env, catalog = catalog,
            draft = walkInDraft, onDraftChanged = { walkInDraft = it },
            onDismiss = { showWalkIn = false },
            onSubmitted = { showWalkIn = false; walkInDraft = WalkInDraft(); tab = CashierTab.NEW; scope.launch { refreshOrders() } },
        )
    }
    paymentOrder?.let { order ->
        PaymentDialog(order, env, onDismiss = { paymentOrder = null }) { updated ->
            paymentOrder = null
            statusMessage = "${order.number} payment recorded"
            statusError = false
            scope.launch { refreshOrders() }
        }
    }
    refundOrder?.let { order ->
        RefundDialog(order, env, onDismiss = { refundOrder = null }) {
            refundOrder = null
            statusMessage = "${order.number} refunded"
            statusError = false
            scope.launch { refreshOrders() }
        }
    }
    detailOrder?.let { order ->
        OrderDetailDialog(order, env, onDismiss = { detailOrder = null })
    }
    if (showSession) {
        CashSessionDialog(env, session, onDismiss = { showSession = false }, onChanged = { updated -> session = updated; scope.launch { refreshSession() } })
    }
    if (showSettings) {
        CashierSettingsDialog(env, onDismiss = { showSettings = false })
    }
    confirmCancel?.let { order ->
        CancelReasonDialog(
            order = order,
            onDismiss = { confirmCancel = null },
            onConfirm = { reason -> confirmCancel = null; runAction(order, "cancel", reason = reason) },
        )
    }
}

private fun formatSyncTime(millis: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
