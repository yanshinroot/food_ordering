package com.foodorder.staff.kitchen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foodorder.staff.AppEnvironment
import com.foodorder.staff.core.ApiResult
import com.foodorder.staff.net.OrderEvent
import com.foodorder.staff.net.StaffOrder
import com.foodorder.staff.ui.components.OrderLinesList

/** No payment/cash fields anywhere here — deliberately a separate,
 *  minimal composable from the Cashier flavor's OrderDetailDialog rather
 *  than a shared one with conditional visibility, so there is no code path
 *  that could accidentally render cash data on a Kitchen device. */
@Composable
fun KitchenOrderDetailDialog(order: StaffOrder, env: AppEnvironment, onDismiss: () -> Unit) {
    var events by remember { mutableStateOf<List<OrderEvent>?>(null) }

    LaunchedEffect(order.id) {
        when (val result = env.apiClient.orderEvents(order.id)) {
            is ApiResult.Success -> events = result.value
            is ApiResult.Failure -> { env.sessionManager.reactTo(result.error); events = emptyList() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Order ${order.number}", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("${order.name}")
                Text("${order.department} · ${order.floor}", color = Color(0xFF69726C))
                Text("Source: ${order.source} · Status: ${order.status}")
                order.acceptedAt?.let { Text("Accepted: $it") }
                order.readyAt?.let { Text("Ready: $it") }
                if (order.promotion.isNotBlank()) Text("Promotion: ${order.promotion}", color = Color(0xFF17653D))
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                OrderLinesList(order.lines)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("EVENT HISTORY", fontWeight = FontWeight.Bold, color = Color(0xFF17653D))
                when {
                    events == null -> Text("Loading…", color = Color(0xFF69726C))
                    events!!.isEmpty() -> Text("No events yet.", color = Color(0xFF69726C))
                    else -> events!!.forEach { event ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text("${event.eventType} — ${event.actor.ifBlank { "system" }}", fontWeight = FontWeight.SemiBold)
                            Text(event.createdAt, color = Color(0xFF8A918C))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
