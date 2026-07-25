package com.foodorder.staff

import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import kotlin.math.ceil

private enum class StaffRole { CASHIER, KITCHEN }
private enum class OrderView { ACTIVE, RECENT }

data class AppSettings(
    val serverUrl: String,
    val cashierKey: String,
    val kitchenKey: String,
    val networkPrinter: String,
    val usbDeviceId: Int,
    val protocol: PrinterProtocol,
    val labelWidthMm: Int,
    val textScale: Int,
    val tearMarginMm: Int,
)

private data class CartLine(
    val product: FoodProduct,
    val quantity: Int = 1,
    val ownCups: Int = 0,
    val note: String = "",
)

private data class PrintBatchResult(val printed: Int, val failed: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val preferences = getSharedPreferences("food-order", MODE_PRIVATE)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF17653D), onPrimary = Color.White,
                    secondary = Color(0xFFF06A32), background = Color(0xFFF5F7F5),
                    surface = Color.White, onSurface = Color(0xFF111713),
                )
            ) { FoodStaffApp(this@MainActivity, preferences) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoodStaffApp(activity: MainActivity, preferences: SharedPreferences) {
    var settings by remember { mutableStateOf(loadSettings(preferences)) }
    var role by remember {
        mutableStateOf(
            runCatching { StaffRole.valueOf(preferences.getString("role", StaffRole.CASHIER.name)!!) }
                .getOrDefault(StaffRole.CASHIER)
        )
    }
    var orderView by remember { mutableStateOf(OrderView.ACTIVE) }
    var searchQuery by remember { mutableStateOf("") }
    var orders by remember { mutableStateOf(emptyList<StaffOrder>()) }
    var catalog by remember { mutableStateOf(Catalog(emptyList(), 500.0)) }
    var connected by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var busyOrderId by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf("Connecting to Odoo…") }
    var statusError by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(settings.cashierKey.isBlank() && settings.kitchenKey.isBlank()) }
    var showWalkIn by remember { mutableStateOf(false) }
    var paymentOrder by remember { mutableStateOf<StaffOrder?>(null) }
    var confirmAction by remember { mutableStateOf<Pair<StaffOrder, String>?>(null) }
    var knownLiveOrderIds by remember { mutableStateOf<Set<Int>?>(null) }
    val scope = rememberCoroutineScope()

    fun keyForRole() = if (role == StaffRole.CASHIER) settings.cashierKey else settings.kitchenKey
    fun printerConfigured() = settings.networkPrinter.isNotBlank() || settings.usbDeviceId >= 0

    fun refresh(silent: Boolean = false) {
        val key = keyForRole()
        if (settings.serverUrl.isBlank() || key.isBlank() || refreshing) {
            if (key.isBlank() && !silent) {
                status = "Add the ${role.name.lowercase()} device key in Setup"
                statusError = true
            }
            return
        }
        refreshing = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val api = FoodApi(settings.serverUrl, key)
                    val active = api.orders(if (orderView == OrderView.ACTIVE) "active" else "recent")
                    val menu = if (role == StaffRole.CASHIER && catalog.products.isEmpty()) api.catalog() else catalog
                    active to menu
                }
            }.onSuccess { result ->
                if (orderView == OrderView.ACTIVE) {
                    val relevantOrders = result.first.filter {
                        if (role == StaffRole.CASHIER) it.status == "pending"
                        else it.status in listOf("accepted", "preparing", "ready")
                    }
                    val currentIds = relevantOrders.map(StaffOrder::id).toSet()
                    val newCount = knownLiveOrderIds?.let { known -> (currentIds - known).size } ?: 0
                    if (newCount > 0) playNewOrderAlert(activity, scope)
                    knownLiveOrderIds = currentIds
                }
                orders = result.first
                catalog = result.second
                connected = true
                statusError = false
                val label = if (orderView == OrderView.ACTIVE) "active" else "recent"
                status = "Live - ${orders.size} $label order${if (orders.size == 1) "" else "s"}"
            }.onFailure {
                connected = false
                status = if (silent) "Odoo connection lost - tap Refresh" else friendlyError(it)
                statusError = true
            }
            refreshing = false
        }
    }

    fun printDeviceJobs(api: FoodApi): PrintBatchResult {
        if (!printerConfigured()) return PrintBatchResult(0, 0)
        var printed = 0
        var failed = 0
        val jobs = runCatching { api.printJobs() }.getOrElse { return PrintBatchResult(0, 1) }
        jobs.forEach { job ->
            try {
                PrinterBridge.print(
                    activity, PrinterBridge.receipt(job.payload, settings.protocol, settings.labelWidthMm, settings.textScale, settings.tearMarginMm),
                    settings.networkPrinter, settings.usbDeviceId,
                )
                api.acknowledge(job.id, true)
                printed++
            } catch (error: Exception) {
                runCatching { api.acknowledge(job.id, false, error.message ?: "Print failed") }
                failed++
            }
        }
        return PrintBatchResult(printed, failed)
    }

    fun orderAction(order: StaffOrder, action: String) {
        if (busyOrderId != null) return
        busyOrderId = order.id
        status = "Updating ${order.number}…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val api = FoodApi(settings.serverUrl, keyForRole())
                    api.action(order.id, action)
                    val printResult = if (action in listOf("accept", "reprint")) printDeviceJobs(api) else null
                    api.orders(if (orderView == OrderView.ACTIVE) "active" else "recent") to printResult
                }
            }.onSuccess { (updatedOrders, printResult) ->
                orders = updatedOrders
                connected = true
                status = when {
                    printResult != null && printResult.failed > 0 ->
                        "${order.number} ${action}ed - printer error, check Print queue"
                    action == "accept" && !printerConfigured() -> "${order.number} accepted - receipt queued"
                    else -> "${order.number} - ${action.replaceFirstChar(Char::uppercase)} done"
                }
                statusError = printResult != null && printResult.failed > 0
            }.onFailure { status = friendlyError(it); statusError = true }
            busyOrderId = null
        }
    }

    fun savePayment(order: StaffOrder, amount: Double) {
        busyOrderId = order.id
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val api = FoodApi(settings.serverUrl, settings.cashierKey)
                    api.collectPayment(order.id, amount)
                    api.orders(if (orderView == OrderView.ACTIVE) "active" else "recent")
                }
            }.onSuccess {
                orders = it
                paymentOrder = null
                status = "${order.number} payment recorded"
                statusError = false
            }.onFailure { status = friendlyError(it); statusError = true }
            busyOrderId = null
        }
    }

    fun submitWalkIn(
        customer: String, phone: String, note: String,
        amount: Double, cart: List<CartLine>,
    ) {
        refreshing = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val api = FoodApi(settings.serverUrl, settings.cashierKey)
                    val created = api.createWalkIn(
                        customer, phone, note, amount,
                        cart.map { WalkInLine(it.product.id, it.quantity, it.ownCups, it.note) },
                    )
                    val printResult = printDeviceJobs(api)
                    Triple(created, api.orders("active"), printResult)
                }
            }.onSuccess {
                orders = it.second
                orderView = OrderView.ACTIVE
                showWalkIn = false
                status = when {
                    !printerConfigured() -> "${it.first.number} sent - receipt queued"
                    it.third.failed > 0 -> "${it.first.number} sent - ${it.third.failed} print failed"
                    else -> "${it.first.number} charged, printed and sent to kitchen"
                }
                statusError = it.third.failed > 0
            }.onFailure { status = friendlyError(it); statusError = true }
            refreshing = false
        }
    }

    fun retryPrintQueue() {
        if (!printerConfigured() || refreshing) return
        refreshing = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val api = FoodApi(settings.serverUrl, keyForRole())
                    api.failedPrintJobs().forEach { api.retryPrint(it.id) }
                    printDeviceJobs(api)
                }
            }.onSuccess {
                status = when {
                    it.failed > 0 -> "${it.printed} printed - ${it.failed} failed"
                    it.printed == 0 -> "Print queue is empty"
                    else -> "${it.printed} receipt${if (it.printed == 1) "" else "s"} printed"
                }
                statusError = it.failed > 0
            }.onFailure { status = friendlyError(it); statusError = true }
            refreshing = false
        }
    }

    LaunchedEffect(role, orderView, settings.serverUrl, settings.cashierKey, settings.kitchenKey) {
        connected = false
        orders = emptyList()
        refresh()
        while (true) { delay(8_000); refresh(silent = true) }
    }

    LaunchedEffect(role, settings.serverUrl, settings.cashierKey, settings.kitchenKey, settings.networkPrinter, settings.usbDeviceId, settings.protocol) {
        while (true) {
            delay(3_000)
            val key = keyForRole()
            if (printerConfigured() && settings.serverUrl.isNotBlank() && key.isNotBlank()) {
                val result = runCatching {
                    withContext(Dispatchers.IO) { printDeviceJobs(FoodApi(settings.serverUrl, key)) }
                }.getOrNull()
                if (result != null && (result.printed > 0 || result.failed > 0)) {
                    status = "Auto print: ${result.printed} printed${if (result.failed > 0) ", ${result.failed} failed" else ""}"
                    statusError = result.failed > 0
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Food Order Staff", fontWeight = FontWeight.ExtraBold) },
                actions = {
                    if (printerConfigured()) TextButton(onClick = { retryPrintQueue() }, enabled = !refreshing) { Text("Print queue") }
                    TextButton(onClick = { showSettings = true }) { Text("Setup") }
                },
            )
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val tablet = maxWidth >= 700.dp
            fun changeRole(newRole: StaffRole) {
                role = newRole
                orderView = OrderView.ACTIVE
                searchQuery = ""
                knownLiveOrderIds = null
                preferences.edit().putString("role", newRole.name).apply()
            }
            fun openWalkIn() {
                if (catalog.products.isEmpty()) refresh() else showWalkIn = true
            }
            Column(Modifier.fillMaxSize().padding(horizontal = if (tablet) 18.dp else 14.dp)) {
                if (tablet) {
                    TabletOperationsBar(
                        role, ::changeRole, orderView, { orderView = it }, searchQuery, { searchQuery = it },
                        connected, printerConfigured(), ::openWalkIn,
                    )
                } else {
                    RoleSwitch(role, ::changeRole)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConnectionChip(if (connected) "Odoo online" else "Odoo offline", connected, Modifier.weight(1f))
                        ConnectionChip(if (printerConfigured()) "Printer configured" else "Printer not set", printerConfigured(), Modifier.weight(1f))
                    }
                    OrderViewSwitch(orderView) { orderView = it }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search order, customer or phone") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                if (statusError || refreshing || !status.startsWith("Live")) {
                    StatusBanner(status, statusError, refreshing) { refresh() }
                }
                if (role == StaffRole.CASHIER) {
                    CashierScreen(
                        orders = orders, view = orderView, query = searchQuery, busyOrderId = busyOrderId,
                        onWalkIn = ::openWalkIn, showHeader = !tablet,
                        onAction = { order, action ->
                            if (action in listOf("cancel", "complete")) confirmAction = order to action
                            else orderAction(order, action)
                        }, onPayment = { paymentOrder = it },
                    )
                } else {
                    KitchenScreen(orders, orderView, searchQuery, busyOrderId, showHeader = !tablet) { order, action -> orderAction(order, action) }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(activity, settings, onDismiss = { showSettings = false }) {
            settings = it
            saveSettings(preferences, it)
            showSettings = false
            status = "Settings saved · reconnecting…"
        }
    }
    paymentOrder?.let { order ->
        PaymentDialog(order, onDismiss = { paymentOrder = null }) { amount ->
            savePayment(order, amount)
        }
    }
    confirmAction?.let { (order, action) ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(if (action == "cancel") "Cancel ${order.number}?" else "Complete ${order.number}?", fontWeight = FontWeight.ExtraBold) },
            text = { Text(if (action == "cancel") "This removes the order from the live cashier and kitchen queues." else "Confirm that the paid order has been handed to the customer.") },
            confirmButton = {
                Button(onClick = { confirmAction = null; orderAction(order, action) }) {
                    Text(if (action == "cancel") "Cancel order" else "Complete order")
                }
            },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Go back") } },
        )
    }
    if (showWalkIn) {
        WalkInDialog(
            catalog = catalog, busy = refreshing, onDismiss = { showWalkIn = false },
            onSubmit = ::submitWalkIn,
        )
    }
}

@Composable
private fun TabletOperationsBar(
    role: StaffRole, onRole: (StaffRole) -> Unit,
    view: OrderView, onView: (OrderView) -> Unit,
    query: String, onQuery: (String) -> Unit,
    connected: Boolean, printerReady: Boolean, onWalkIn: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFE9EEEA), RoundedCornerShape(12.dp)).padding(4.dp)) {
            StaffRole.entries.forEach { option ->
                val selected = role == option
                Surface(
                    modifier = Modifier.weight(1f).clickable { onRole(option) },
                    color = if (selected) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(9.dp),
                    shadowElevation = if (selected) 1.dp else 0.dp,
                ) { Text(if (option == StaffRole.CASHIER) "▦  Cashier POS" else "♨  Kitchen", Modifier.padding(10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold, color = if (selected) Color(0xFF17653D) else Color(0xFF68716B)) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OrderView.entries.forEach { option ->
                if (view == option) Button(onClick = { onView(option) }, shape = RoundedCornerShape(9.dp)) { Text(if (option == OrderView.ACTIVE) "Active orders" else "Recent orders") }
                else OutlinedButton(onClick = { onView(option) }, shape = RoundedCornerShape(9.dp)) { Text(if (option == OrderView.ACTIVE) "Active orders" else "Recent orders") }
            }
            OutlinedTextField(value = query, onValueChange = onQuery, placeholder = { Text("Search order, customer or phone") }, singleLine = true, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(if (connected) "● Odoo" else "○ Odoo", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (connected) Color(0xFF17653D) else Color(0xFF9A3E32))
                Text(if (printerReady) "● Printer" else "○ No printer", fontSize = 10.sp, color = if (printerReady) Color(0xFF17653D) else Color(0xFF69726C))
            }
            if (role == StaffRole.CASHIER && view == OrderView.ACTIVE) Button(onClick = onWalkIn, shape = RoundedCornerShape(9.dp)) { Text("+ Walk-in") }
        }
    }
}

@Composable
private fun RoleSwitch(role: StaffRole, onChange: (StaffRole) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 9.dp).background(Color(0xFFE9EEEA), RoundedCornerShape(14.dp)).padding(4.dp)) {
        StaffRole.entries.forEach { option ->
            val selected = role == option
            Surface(
                modifier = Modifier.weight(1f).clickable { onChange(option) },
                color = if (selected) Color.White else Color.Transparent,
                shape = RoundedCornerShape(11.dp),
                shadowElevation = if (selected) 1.dp else 0.dp,
            ) { Text(if (option == StaffRole.CASHIER) "▦  Cashier POS" else "♨  Kitchen", Modifier.padding(11.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF68716B)) }
        }
    }
}

@Composable
private fun OrderViewSwitch(view: OrderView, onChange: (OrderView) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OrderView.entries.forEach { option ->
            val selected = option == view
            if (selected) {
                Button(
                    onClick = { onChange(option) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) { Text(if (option == OrderView.ACTIVE) "Active orders" else "Recent orders") }
            } else {
                OutlinedButton(
                    onClick = { onChange(option) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) { Text(if (option == OrderView.ACTIVE) "Active orders" else "Recent orders") }
            }
        }
    }
}

private fun List<StaffOrder>.matching(query: String): List<StaffOrder> {
    val term = query.trim()
    if (term.isBlank()) return this
    return filter { order ->
        listOf(order.number, order.name, order.phone, order.department, order.floor, order.status)
            .any { it.contains(term, ignoreCase = true) }
    }
}

@Composable
private fun CashierScreen(
    orders: List<StaffOrder>, view: OrderView, query: String, busyOrderId: Int?, onWalkIn: () -> Unit,
    showHeader: Boolean, onAction: (StaffOrder, String) -> Unit, onPayment: (StaffOrder) -> Unit,
) {
    val visibleOrders = orders.matching(query)
    Column(Modifier.fillMaxSize()) {
        if (showHeader) {
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(if (view == OrderView.ACTIVE) "Cashier queue" else "Recent orders", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold); Text(if (view == OrderView.ACTIVE) "Online and counter orders" else "Completed and cancelled orders", fontSize = 11.sp, color = Color(0xFF6D756F)) }
                if (view == OrderView.ACTIVE) Button(onClick = onWalkIn, shape = RoundedCornerShape(11.dp)) { Text("+ Walk-in") }
            }
        }
        if (visibleOrders.isEmpty()) EmptyState(if (view == OrderView.ACTIVE) "No active orders" else "No recent orders", if (query.isBlank()) "Orders refresh automatically." else "Try another search.")
        else BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 700.dp) {
                TabletOrderWorkspace(visibleOrders, busyOrderId, StaffRole.CASHIER, onAction, onPayment)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visibleOrders, key = StaffOrder::id) { order ->
                        StaffOrderCard(order, busyOrderId == order.id, StaffRole.CASHIER, onAction, onPayment)
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun KitchenScreen(orders: List<StaffOrder>, view: OrderView, query: String, busyOrderId: Int?, showHeader: Boolean, onAction: (StaffOrder, String) -> Unit) {
    val kitchenOrders = orders.filter { view == OrderView.RECENT || it.status in listOf("accepted", "preparing", "ready") }.matching(query)
    Column(Modifier.fillMaxSize()) {
        if (showHeader) {
            Text(if (view == OrderView.ACTIVE) "Kitchen display" else "Kitchen history", Modifier.padding(top = 7.dp), fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
            Text(if (view == OrderView.ACTIVE) "Queue -> Preparing -> Ready" else "Completed and cancelled tickets", fontSize = 11.sp, color = Color(0xFF6D756F))
        }
        if (kitchenOrders.isEmpty()) EmptyState(if (view == OrderView.ACTIVE) "Kitchen is clear" else "No recent tickets", if (query.isBlank()) "Orders refresh automatically." else "Try another search.")
        else BoxWithConstraints(Modifier.fillMaxSize().padding(top = 10.dp)) {
            if (maxWidth >= 700.dp) {
                TabletOrderWorkspace(kitchenOrders, busyOrderId, StaffRole.KITCHEN, onAction, {})
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(kitchenOrders, key = StaffOrder::id) { order ->
                        StaffOrderCard(order, busyOrderId == order.id, StaffRole.KITCHEN, onAction, {})
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TabletOrderWorkspace(
    orders: List<StaffOrder>, busyOrderId: Int?, role: StaffRole,
    onAction: (StaffOrder, String) -> Unit, onPayment: (StaffOrder) -> Unit,
) {
    var selectedId by remember { mutableStateOf<Int?>(orders.firstOrNull()?.id) }
    LaunchedEffect(orders.map(StaffOrder::id)) {
        if (orders.none { it.id == selectedId }) selectedId = orders.firstOrNull()?.id
    }
    val selected = orders.firstOrNull { it.id == selectedId } ?: orders.first()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(.34f).fillMaxSize()) {
            Text("${orders.size} ORDERS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6D756F), modifier = Modifier.padding(bottom = 7.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(orders, key = StaffOrder::id) { order ->
                    CompactOrderCard(order, selectedId == order.id) { selectedId = order.id }
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
        }
        Box(Modifier.width(1.dp).fillMaxSize().background(Color(0xFFE2E7E3)))
        Column(Modifier.weight(.66f).fillMaxSize().verticalScroll(rememberScrollState()).padding(end = 4.dp, bottom = 20.dp)) {
            Text("ORDER DETAILS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 7.dp))
            StaffOrderCard(selected, busyOrderId == selected.id, role, onAction, onPayment, expanded = true)
        }
    }
}

@Composable
private fun CompactOrderCard(order: StaffOrder, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFE8F3EC) else Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(if (selected) 2.dp else 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(order.number, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                StatusPill(order.status)
            }
            Text(order.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${order.lines.sumOf { it.quantity }.let(::cleanQuantity)} items - ${order.elapsedMinutes} min", fontSize = 11.sp, color = if (order.elapsedMinutes >= 15) Color(0xFFC5532B) else Color(0xFF68716B))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(order.source.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(formatMoney(order.total, order.currency), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StaffOrderCard(
    order: StaffOrder, busy: Boolean, role: StaffRole,
    onAction: (StaffOrder, String) -> Unit, onPayment: (StaffOrder) -> Unit,
    expanded: Boolean = false,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(if (expanded) 22.dp else 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(order.number, fontSize = if (expanded) 30.sp else 18.sp, fontWeight = FontWeight.ExtraBold); Text("${order.source.uppercase()} · ${order.elapsedMinutes} MIN", color = MaterialTheme.colorScheme.secondary, fontSize = if (expanded) 13.sp else 9.sp, fontWeight = FontWeight.Bold) }
                Column(horizontalAlignment = Alignment.End) { StatusPill(order.status, expanded); Text(formatMoney(order.total, order.currency), fontWeight = FontWeight.Bold, fontSize = if (expanded) 22.sp else 14.sp) }
            }
            HorizontalDivider(Modifier.padding(vertical = if (expanded) 15.dp else 9.dp), color = Color(0xFFEDF0EE))
            Text(order.name, fontSize = if (expanded) 21.sp else 14.sp, fontWeight = FontWeight.Bold)
            Text("${order.phone} · ${order.department} · ${order.floor}", color = Color(0xFF69726C), fontSize = if (expanded) 15.sp else 10.sp)
            order.lines.forEach { line ->
                Row(Modifier.fillMaxWidth().padding(top = if (expanded) 13.dp else 7.dp)) {
                    Text("${cleanQuantity(line.quantity)}×", Modifier.width(if (expanded) 48.dp else 32.dp), color = MaterialTheme.colorScheme.primary, fontSize = if (expanded) 18.sp else 14.sp, fontWeight = FontWeight.Bold)
                    Column { Text(line.name, fontSize = if (expanded) 18.sp else 12.sp, fontWeight = FontWeight.SemiBold); val detail = listOfNotNull(line.ownCupQuantity.takeIf { it > 0 }?.let { "Own cup × $it" }, line.note.takeIf(String::isNotBlank)).joinToString(" · "); if (detail.isNotBlank()) Text(detail, fontSize = if (expanded) 14.sp else 9.sp, color = Color(0xFF17653D)) }
                }
            }
            if (order.note.isNotBlank()) Text("Note · ${order.note}", Modifier.fillMaxWidth().padding(top = if (expanded) 15.dp else 8.dp).background(Color(0xFFFFF6E8), RoundedCornerShape(8.dp)).padding(if (expanded) 14.dp else 8.dp), fontSize = if (expanded) 15.sp else 10.sp)
            if (role == StaffRole.CASHIER) {
                Row(Modifier.fillMaxWidth().padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    when {
                        order.status in listOf("completed", "cancelled") -> {
                            OutlinedButton(onClick = { onAction(order, "reprint") }, enabled = !busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Reprint receipt") }
                        }
                        order.status == "pending" -> ActionButton("Accept & print", busy, Modifier.weight(1f), expanded) { onAction(order, "accept") }
                        order.status == "ready" -> {
                            if (order.paymentStatus != "paid") ActionButton("Collect payment", busy, Modifier.weight(1f), expanded) { onPayment(order) }
                            else ActionButton("Complete", busy, Modifier.weight(1f), expanded) { onAction(order, "complete") }
                        }
                        else -> {
                            if (order.paymentStatus != "paid") ActionButton("Collect payment", busy, Modifier.weight(1f), expanded) { onPayment(order) }
                            else Surface(Modifier.weight(1f), color = Color(0xFFF0F4F1), shape = RoundedCornerShape(10.dp)) { Text("Paid - ${order.paymentMethod.uppercase()}", Modifier.padding(12.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF17653D)) }
                        }
                    }
                    if (order.status !in listOf("ready", "completed", "cancelled") || (order.status == "ready" && order.paymentStatus != "paid")) {
                        OutlinedButton(onClick = { onAction(order, "cancel") }, enabled = !busy, shape = RoundedCornerShape(10.dp)) { Text("Cancel") }
                    }
                }
            } else {
                when (order.status) {
                    "accepted" -> ActionButton("Start preparing", busy, Modifier.fillMaxWidth().padding(top = 11.dp), expanded) { onAction(order, "prepare") }
                    "preparing" -> ActionButton("Mark ready", busy, Modifier.fillMaxWidth().padding(top = 11.dp), expanded) { onAction(order, "ready") }
                    "ready" -> Row(Modifier.fillMaxWidth().padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Ready for cashier", Modifier.weight(1f).background(Color(0xFFEAF4ED), RoundedCornerShape(10.dp)).padding(12.dp), color = Color(0xFF17653D), fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { onAction(order, "reprint") }, enabled = !busy, shape = RoundedCornerShape(10.dp)) { Text("Reprint") }
                    }
                    "completed", "cancelled" -> OutlinedButton(onClick = { onAction(order, "reprint") }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 11.dp), shape = RoundedCornerShape(10.dp)) { Text("Reprint kitchen ticket") }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, busy: Boolean, modifier: Modifier, large: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = modifier.height(if (large) 58.dp else 46.dp), shape = RoundedCornerShape(10.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Text(label)
    }
}

@Composable
private fun PaymentDialog(order: StaffOrder, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var amount by remember { mutableStateOf(ceil(order.total).toLong().toString()) }
    val received = amount.toDoubleOrNull() ?: 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cash payment · ${order.number}", fontWeight = FontWeight.ExtraBold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(formatMoney(order.total, order.currency), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("CASH ONLY", color = Color(0xFF17653D), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(amount, { amount = it }, label = { Text("Cash received") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Text("Change: ${formatMoney((received - order.total).coerceAtLeast(0.0), order.currency)}", color = Color(0xFF17653D), fontWeight = FontWeight.Bold)
        } },
        confirmButton = { Button(onClick = { onSave(received) }, enabled = received >= order.total) { Text("Confirm cash payment") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WalkInDialog(
    catalog: Catalog, busy: Boolean, onDismiss: () -> Unit,
    onSubmit: (String, String, String, Double, List<CartLine>) -> Unit,
) {
    var cart by remember { mutableStateOf<Map<Int, CartLine>>(emptyMap()) }
    var customer by remember { mutableStateOf("Walk-in Customer") }
    var phone by remember { mutableStateOf("Walk-in") }
    var orderNote by remember { mutableStateOf("") }
    var cash by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("all") }
    val subtotal = cart.values.sumOf { it.product.price * it.quantity }
    val discount = cart.values.sumOf { it.ownCups } * catalog.ownCupDiscount
    val total = subtotal - discount
    val received = cash.toDoubleOrNull() ?: 0.0
    val visibleProducts = catalog.products.filter {
        (category == "all" || it.category == category) && it.name.contains(search.trim(), ignoreCase = true)
    }
    LaunchedEffect(total) { cash = ceil(total).toLong().toString() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF5F7F5)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("New walk-in order", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold); Text("FULL-SCREEN CASHIER POS · CASH ONLY", color = Color(0xFF17653D), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) { Text("Back to orders") }
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(.60f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(search, { search = it }, placeholder = { Text("Search menu") }, singleLine = true, modifier = Modifier.weight(1f))
                            listOf("all" to "All", "drinks" to "Drinks", "meals" to "Meals").forEach { (value, label) ->
                                if (category == value) Button(onClick = { category = value }) { Text(label) }
                                else OutlinedButton(onClick = { category = value }) { Text(label) }
                            }
                        }
                        Text("MENU · ${visibleProducts.size} PRODUCTS", color = Color(0xFF68716B), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            gridItems(visibleProducts, key = FoodProduct::id) { product ->
                                val line = cart[product.id]
                                Card(modifier = Modifier.fillMaxWidth().clickable { cart = cart + (product.id to (line?.copy(quantity = line.quantity + 1) ?: CartLine(product))) }, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(product.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        Text(formatMoney(product.price, product.currency), color = Color(0xFF68716B), fontSize = 12.sp)
                                        Button(onClick = { cart = cart + (product.id to (line?.copy(quantity = line.quantity + 1) ?: CartLine(product))) }, modifier = Modifier.fillMaxWidth()) { Text(if (line == null) "+ Add" else "+ Add more (${line.quantity})") }
                                    }
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(.40f).fillMaxHeight().background(Color.White, RoundedCornerShape(16.dp)).padding(15.dp).verticalScroll(rememberScrollState())) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Current basket", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold); Text("${cart.values.sumOf { it.quantity }} items", color = Color(0xFF17653D), fontWeight = FontWeight.Bold) }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFFE5EAE6))
                        if (cart.isEmpty()) Text("Select products from the menu.", Modifier.padding(vertical = 28.dp), color = Color(0xFF68716B))
                        cart.values.forEach { line ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text(line.product.name, fontWeight = FontWeight.Bold); Text(formatMoney(line.product.price * line.quantity, line.product.currency), fontSize = 10.sp, color = Color(0xFF68716B)) }
                                    TextButton(onClick = { if (line.quantity <= 1) cart = cart - line.product.id else cart = cart + (line.product.id to line.copy(quantity = line.quantity - 1, ownCups = line.ownCups.coerceAtMost(line.quantity - 1))) }) { Text("−") }
                                    Text(line.quantity.toString(), fontWeight = FontWeight.Bold)
                                    TextButton(onClick = { cart = cart + (line.product.id to line.copy(quantity = line.quantity + 1)) }) { Text("+") }
                                }
                                if (line.product.ownCupEligible) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Own cups · save ${formatMoney(catalog.ownCupDiscount, line.product.currency)}", Modifier.weight(1f), fontSize = 10.sp, color = Color(0xFF17653D)); TextButton(onClick = { cart = cart + (line.product.id to line.copy(ownCups = (line.ownCups - 1).coerceAtLeast(0))) }, enabled = line.ownCups > 0) { Text("−") }; Text("${line.ownCups}/${line.quantity}"); TextButton(onClick = { cart = cart + (line.product.id to line.copy(ownCups = (line.ownCups + 1).coerceAtMost(line.quantity))) }, enabled = line.ownCups < line.quantity) { Text("+") } }
                                OutlinedTextField(line.note, { value -> cart = cart + (line.product.id to line.copy(note = value)) }, label = { Text("Item note") }, modifier = Modifier.fillMaxWidth())
                            }
                            HorizontalDivider(color = Color(0xFFE5EAE6))
                        }
                        OutlinedTextField(customer, { customer = it }, label = { Text("Customer") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                        OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(orderNote, { orderNote = it }, label = { Text("Order note") }, modifier = Modifier.fillMaxWidth())
                        Text("CASH PAYMENT", color = Color(0xFF17653D), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                        OutlinedTextField(cash, { cash = it }, label = { Text("Cash received") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Surface(Modifier.fillMaxWidth().padding(vertical = 10.dp), color = Color(0xFFEAF4ED), shape = RoundedCornerShape(11.dp)) { Column(Modifier.padding(13.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total"); Text(formatMoney(total, "MMK"), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold) }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Change"); Text(formatMoney((received - total).coerceAtLeast(0.0), "MMK"), fontWeight = FontWeight.Bold) } } }
                        Button(onClick = { onSubmit(customer, phone, orderNote, received, cart.values.toList()) }, enabled = !busy && cart.isNotEmpty() && received >= total, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(11.dp)) { Text(if (busy) "Sending…" else "Charge & send to kitchen") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(activity: MainActivity, initial: AppSettings, onDismiss: () -> Unit, onSave: (AppSettings) -> Unit) {
    var server by remember { mutableStateOf(initial.serverUrl) }
    var cashierKey by remember { mutableStateOf(initial.cashierKey) }
    var kitchenKey by remember { mutableStateOf(initial.kitchenKey) }
    var network by remember { mutableStateOf(initial.networkPrinter) }
    var protocol by remember { mutableStateOf(initial.protocol) }
    var labelWidth by remember { mutableStateOf(initial.labelWidthMm.toString()) }
    var textScale by remember { mutableIntStateOf(initial.textScale) }
    var tearMargin by remember { mutableStateOf(initial.tearMarginMm.toString()) }
    val usbPrinters = remember { PrinterBridge.usbPrinters(activity) }
    var usbId by remember { mutableIntStateOf(initial.usbDeviceId.takeIf { saved -> usbPrinters.any { it.deviceId == saved } } ?: -1) }
    var printerMessage by remember { mutableStateOf("") }
    var testBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Staff app setup", fontWeight = FontWeight.ExtraBold) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(server, { server = it }, label = { Text("Odoo server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cashierKey, { cashierKey = it }, label = { Text("Cashier device key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(kitchenKey, { kitchenKey = it }, label = { Text("Kitchen device key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("PRINTER · OPTIONAL", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF17653D))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { PrinterProtocol.entries.forEach { option -> OutlinedButton(onClick = { protocol = option }, colors = if (protocol == option) ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFEAF4ED)) else ButtonDefaults.outlinedButtonColors(), modifier = Modifier.weight(1f)) { Text(when (option) { PrinterProtocol.ESC_POS -> "ESC/POS"; PrinterProtocol.SATO_SBPL -> "SATO"; PrinterProtocol.TSPL -> "TSPL" }) } } }
            if (protocol == PrinterProtocol.TSPL) {
                OutlinedTextField(labelWidth, { labelWidth = it }, label = { Text("Label/roll width (mm)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Print text size", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF17653D))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "Small", 2 to "Medium", 3 to "Large", 4 to "X-Large").forEach { (scale, label) ->
                        OutlinedButton(onClick = { textScale = scale }, colors = if (textScale == scale) ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFEAF4ED)) else ButtonDefaults.outlinedButtonColors(), modifier = Modifier.weight(1f)) { Text(label, fontSize = 11.sp) }
                    }
                }
                OutlinedTextField(tearMargin, { tearMargin = it }, label = { Text("Tear margin (mm)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Continuous stock, no gap sensing. If the next ticket's header prints too close to the tear bar (gets clipped when torn), increase the tear margin and test again.", fontSize = 9.sp, color = Color(0xFF68716B))
            }
            OutlinedTextField(network, { network = it }, label = { Text("Network printer IP:9100") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (network.isBlank()) {
                if (usbPrinters.isEmpty()) Text("No USB printer detected. Connect the powered printer with a USB OTG adapter, then reopen Setup.", color = Color(0xFFA84B2C), fontSize = 10.sp)
                else {
                    usbPrinters.forEach { printer -> Row(Modifier.fillMaxWidth().clickable { usbId = printer.deviceId }.background(if (usbId == printer.deviceId) Color(0xFFEAF4ED) else Color(0xFFF5F7F5), RoundedCornerShape(8.dp)).padding(9.dp)) { Text(if (usbId == printer.deviceId) "● " else "○ "); Text(printer.label, fontSize = 10.sp) } }
                    OutlinedButton(onClick = {
                        val alreadyGranted = PrinterBridge.requestUsbPermission(activity, usbId)
                        printerMessage = if (alreadyGranted) "USB permission already granted" else "Allow USB printer access in the Android popup"
                    }, enabled = usbId >= 0, modifier = Modifier.fillMaxWidth()) { Text("Grant USB access") }
                }
            }
            OutlinedButton(onClick = {
                testBusy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { PrinterBridge.print(activity, PrinterBridge.testReceipt(protocol, labelWidth.toIntOrNull() ?: DEFAULT_LABEL_WIDTH_MM, textScale, tearMargin.toIntOrNull() ?: DEFAULT_TEAR_MARGIN_MM), network, usbId) } }
                        .onSuccess { printerMessage = "Test receipt sent successfully" }
                        .onFailure { printerMessage = friendlyError(it) }
                    testBusy = false
                }
            }, enabled = !testBusy && (network.isNotBlank() || usbId >= 0), modifier = Modifier.fillMaxWidth()) { Text(if (testBusy) "Sending test…" else "Test printer") }
            if (printerMessage.isNotBlank()) Text(printerMessage, color = Color(0xFF17653D), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        } },
        confirmButton = { Button(onClick = { onSave(AppSettings(server.trim(), cashierKey.trim(), kitchenKey.trim(), network.trim(), usbId, protocol, labelWidth.toIntOrNull() ?: DEFAULT_LABEL_WIDTH_MM, textScale, tearMargin.toIntOrNull() ?: DEFAULT_TEAR_MARGIN_MM)) }, enabled = server.isNotBlank() && (cashierKey.isNotBlank() || kitchenKey.isNotBlank())) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable private fun StatusPill(status: String, large: Boolean = false) { val color = when (status) { "pending" -> Color(0xFFF06A32); "preparing" -> Color(0xFFD18A19); "ready", "completed" -> Color(0xFF17653D); "cancelled" -> Color(0xFF9A3E32); else -> Color(0xFF4D6255) }; Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(20.dp)) { Text(status.uppercase(), Modifier.padding(horizontal = if (large) 13.dp else 9.dp, vertical = if (large) 8.dp else 5.dp), color = color, fontSize = if (large) 12.sp else 8.sp, fontWeight = FontWeight.Bold) } }
@Composable private fun EmptyState(title: String, caption: String) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("✓", color = Color(0xFF17653D), fontSize = 38.sp); Text(title, fontWeight = FontWeight.Bold); Text(caption, fontSize = 11.sp, color = Color(0xFF69726C)) } } }
@Composable private fun StatusBanner(message: String, error: Boolean, loading: Boolean, onRefresh: () -> Unit) { val bg = if (error) Color(0xFFFFEEE8) else Color(0xFFEDF6F0); val fg = if (error) Color(0xFFA84B2C) else Color(0xFF17653D); Row(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) { if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp) else Text(if (error) "!" else "●", color = fg); Spacer(Modifier.width(8.dp)); Text(message, Modifier.weight(1f), color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold); TextButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") } } }
@Composable private fun ConnectionChip(label: String, active: Boolean, modifier: Modifier = Modifier) { Row(modifier.background(if (active) Color(0xFFEAF4ED) else Color(0xFFF0F2F0), RoundedCornerShape(9.dp)).padding(9.dp), verticalAlignment = Alignment.CenterVertically) { Text("●", color = if (active) Color(0xFF17653D) else Color(0xFF8A918C), fontSize = 8.sp); Spacer(Modifier.width(6.dp)); Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (active) Color(0xFF17653D) else Color(0xFF69726C)) } }

private fun loadSettings(prefs: SharedPreferences) = AppSettings(
    serverUrl = prefs.getString("server", "http://172.25.85.14:8069") ?: "",
    cashierKey = prefs.getString("cashierKey", prefs.getString("key", "")) ?: "",
    kitchenKey = prefs.getString("kitchenKey", "") ?: "",
    networkPrinter = prefs.getString("printer", "") ?: "",
    usbDeviceId = prefs.getInt("usbDeviceId", -1),
    protocol = runCatching { PrinterProtocol.valueOf(prefs.getString("protocol", PrinterProtocol.ESC_POS.name)!!) }.getOrDefault(PrinterProtocol.ESC_POS),
    labelWidthMm = prefs.getInt("labelWidthMm", DEFAULT_LABEL_WIDTH_MM),
    textScale = prefs.getInt("textScale", DEFAULT_TEXT_SCALE),
    tearMarginMm = prefs.getInt("tearMarginMm", DEFAULT_TEAR_MARGIN_MM),
)

private fun saveSettings(prefs: SharedPreferences, value: AppSettings) { prefs.edit().putString("server", value.serverUrl).putString("cashierKey", value.cashierKey).putString("kitchenKey", value.kitchenKey).putString("printer", value.networkPrinter).putInt("usbDeviceId", value.usbDeviceId).putString("protocol", value.protocol.name).putInt("labelWidthMm", value.labelWidthMm).putInt("textScale", value.textScale).putInt("tearMarginMm", value.tearMarginMm).apply() }
private fun playNewOrderAlert(activity: MainActivity, scope: CoroutineScope) {
    val vibrator = activity.getSystemService(Vibrator::class.java)
    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 450, 150, 450, 150, 650), -1))
    scope.launch {
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        try {
            repeat(3) {
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 520)
                delay(650)
            }
        } finally {
            tone.release()
        }
    }
}
private fun formatMoney(value: Double, currency: String) = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }.format(value) + " " + currency
private fun cleanQuantity(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
private fun friendlyError(error: Throwable): String = when (error.message) { "unauthorized" -> "Device key rejected · check Setup"; "action_not_allowed" -> "This device key cannot use this screen"; else -> error.message ?: "Operation failed" }
