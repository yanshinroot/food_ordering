package com.foodorder.staff.kitchen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.foodorder.staff.AppEnvironment
import com.foodorder.staff.core.ApiError
import com.foodorder.staff.core.ApiResult
import com.foodorder.staff.net.StaffConfig
import com.foodorder.staff.net.StaffOrder
import com.foodorder.staff.poll.AdaptivePoller
import com.foodorder.staff.sound.AlertDedupeGuard
import com.foodorder.staff.sound.AlertPlayer
import com.foodorder.staff.ui.KeepScreenOn
import com.foodorder.staff.ui.components.ConnectionChip
import com.foodorder.staff.ui.components.EmptyState
import com.foodorder.staff.ui.components.OfflineBanner
import com.foodorder.staff.ui.components.StatusBanner
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class KitchenTab(val label: String) { NEW("New / Accepted"), PREPARING("Preparing"), READY("Ready") }

private data class PendingKitchenAction(val order: StaffOrder, val action: String, val label: String)

@Composable
fun KitchenHomeScreen(env: AppEnvironment) {
    var orders by remember { mutableStateOf(emptyList<StaffOrder>()) }
    var config by remember { mutableStateOf(StaffConfig(10, 20, shiftEnabled = false, refundEnabled = false)) }
    var search by remember { mutableStateOf("") }
    var deptFilter by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var lastSyncMillis by remember { mutableStateOf<Long?>(null) }
    var statusMessage by remember { mutableStateOf("Connecting to Odoo…") }
    var statusError by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(KitchenTab.NEW) }
    var busyOrderId by remember { mutableStateOf<Int?>(null) }
    var detailOrder by remember { mutableStateOf<StaffOrder?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingKitchenAction?>(null) }
    val undoScheduler = remember { KitchenUndoScheduler() }
    val alertGuard = remember { AlertDedupeGuard() }
    val scope = rememberCoroutineScope()
    val ordersPoller = remember { AdaptivePoller(baseIntervalMs = 5_000) }
    val printPoller = remember { AdaptivePoller(baseIntervalMs = 4_000) }

    fun handleFailure(error: ApiError) {
        env.sessionManager.reactTo(error)
        connected = error !is ApiError.Network && error !is ApiError.Timeout
        statusMessage = error.userMessage
        statusError = true
    }

    suspend fun refreshOrders(): Boolean {
        val result = env.apiClient.orders("active")
        if (result is ApiResult.Failure) { handleFailure(result.error); return false }
        val active = (result as ApiResult.Success).value
        orders = active
        connected = true
        statusError = false
        lastSyncMillis = System.currentTimeMillis()
        statusMessage = "Live — ${active.size} order${if (active.size == 1) "" else "s"}"
        val newIds = active.filter { it.status in listOf("accepted", "preparing") }.map { it.id }.toSet()
        if (alertGuard.hasNewArrivals(newIds)) AlertPlayer.play(env.activity, scope, env.uiPreferences.soundEnabled)
        return true
    }

    suspend fun printTick(): Boolean = env.printQueue.claimAndPrintQueued().failed == 0

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    ordersPoller.refreshNow(scope) { refreshOrders() }
                    printPoller.refreshNow(scope) { printTick() }
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
        when (val result = env.apiClient.staffConfig()) {
            is ApiResult.Success -> config = result.value
            is ApiResult.Failure -> {} // non-fatal — falls back to the 10/20-minute defaults
        }
    }

    // Kitchen screen stays awake the whole time it's the active screen;
    // released automatically (see KeepScreenOn) the moment this leaves composition.
    KeepScreenOn(env.activity, enabled = true)

    fun matches(order: StaffOrder): Boolean {
        val term = search.trim()
        val searchOk = term.isBlank() || listOf(order.number, order.name, order.phone, order.department, order.floor)
            .any { it.contains(term, ignoreCase = true) }
        val deptTerm = deptFilter.trim()
        val deptOk = deptTerm.isBlank() || order.department.contains(deptTerm, ignoreCase = true) || order.floor.contains(deptTerm, ignoreCase = true)
        return searchOk && deptOk
    }

    val visible = orders.filter(::matches)
    val newAccepted = visible.filter { it.status == "accepted" }
    val preparing = visible.filter { it.status == "preparing" }
    val ready = visible.filter { it.status == "ready" }

    fun sendNow(order: StaffOrder, action: String) {
        busyOrderId = order.id
        scope.launch {
            val requestId = env.idempotency.keyFor("$action:${order.id}")
            when (val result = env.apiClient.orderAction(order.id, action, requestId, order.stateVersion)) {
                is ApiResult.Success -> {
                    env.idempotency.retire("$action:${order.id}")
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

    fun scheduleHold(order: StaffOrder, action: String, label: String) {
        pending = PendingKitchenAction(order, action, label)
        undoScheduler.schedule(scope) {
            pending = null
            sendNow(order, action)
        }
    }

    fun cancelHold() {
        undoScheduler.undo()
        pending = null
    }

    fun runAction(order: StaffOrder, action: String) {
        if (busyOrderId != null) return
        when (action) {
            "prepare" -> scheduleHold(order, action, "Starting to prepare ${order.number}…")
            "ready" -> scheduleHold(order, action, "Marking ${order.number} ready…")
            else -> sendNow(order, action)
        }
    }

    Column(Modifier.fillMaxSize()) {
        KitchenToolbar(connected, lastSyncMillis, onSettings = { showSettings = true }, onManualRefresh = { scope.launch { ordersPoller.refreshNow(scope) { refreshOrders() } } })
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

        if (!connected) OfflineBanner(lastSyncMillis?.let(::formatSyncTime))
        else if (statusError) StatusBanner(statusMessage, statusError, false) { scope.launch { refreshOrders() } }

        pending?.let { current ->
            KitchenUndoBar(label = current.label, onUndo = { cancelHold() })
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(KitchenTab.NEW to newAccepted.size, KitchenTab.PREPARING to preparing.size, KitchenTab.READY to ready.size).forEach { (option, count) ->
                val selected = tab == option
                if (selected) Button(onClick = { tab = option }, modifier = Modifier.weight(1f)) { Text("${option.label} ($count)", fontSize = 11.sp) }
                else OutlinedButton(onClick = { tab = option }, modifier = Modifier.weight(1f)) { Text("${option.label} ($count)", fontSize = 11.sp) }
            }
        }

        val shown = when (tab) {
            KitchenTab.NEW -> newAccepted
            KitchenTab.PREPARING -> preparing
            KitchenTab.READY -> ready
        }
        if (shown.isEmpty()) {
            EmptyState(
                when (tab) { KitchenTab.NEW -> "Kitchen queue is clear"; KitchenTab.PREPARING -> "Nothing preparing"; KitchenTab.READY -> "Nothing ready" },
                "Orders refresh automatically.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = StaffOrder::id) { order ->
                    KitchenOrderCard(
                        order = order,
                        busy = busyOrderId == order.id || pending?.order?.id == order.id,
                        warnMinutes = config.kitchenSlaWarnMinutes, lateMinutes = config.kitchenSlaLateMinutes,
                        onDetails = { detailOrder = order },
                        onStartPreparing = { runAction(order, "prepare") },
                        onMarkReady = { runAction(order, "ready") },
                        onReprint = { sendNow(order, "reprint") },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    detailOrder?.let { order -> KitchenOrderDetailDialog(order, env, onDismiss = { detailOrder = null }) }
    if (showSettings) KitchenSettingsDialog(env, onDismiss = { showSettings = false })
}

@Composable
private fun KitchenToolbar(connected: Boolean, lastSyncMillis: Long?, onSettings: () -> Unit, onManualRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Kitchen", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ConnectionChip(if (connected) "Live" else "Offline", connected)
            OutlinedButton(onClick = onManualRefresh) { Text("Refresh", fontSize = 11.sp) }
            OutlinedButton(onClick = onSettings) { Text("Setup", fontSize = 11.sp) }
        }
    }
}

private fun formatSyncTime(millis: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

@Composable
private fun KitchenUndoBar(label: String, onUndo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).background(Color(0xFF17653D), RoundedCornerShape(10.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        OutlinedButton(onClick = onUndo) { Text("Undo") }
    }
}
