package com.foodorder.staff.cashier

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodorder.staff.net.StaffOrder
import com.foodorder.staff.ui.components.MinTouchTarget
import com.foodorder.staff.ui.components.OrderLinesList
import com.foodorder.staff.ui.components.StatusPill
import com.foodorder.staff.ui.components.formatMoney

@Composable
fun CashierOrderCard(
    order: StaffOrder,
    busy: Boolean,
    onDetails: () -> Unit,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
    onPayment: () -> Unit,
    onComplete: () -> Unit,
    onReprint: () -> Unit,
    onRefund: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(order.number, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${order.source.uppercase()} · ${order.elapsedMinutes} MIN", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    StatusPill(order.status)
                    Text(formatMoney(order.total, order.currency), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFFEDF0EE))
            Text(order.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("${order.phone} · ${order.department} · ${order.floor}", color = Color(0xFF69726C), fontSize = 11.sp)
            if (order.promotion.isNotBlank()) {
                Text("Promotion: ${order.promotion}", color = Color(0xFF17653D), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            OrderLinesList(order.lines, compact = true)
            if (order.note.isNotBlank()) {
                Text(
                    "Note · ${order.note}",
                    Modifier.fillMaxWidth().padding(top = 8.dp).background(Color(0xFFFFF6E8), RoundedCornerShape(8.dp)).padding(8.dp),
                    fontSize = 10.sp,
                )
            }
            if (order.status == "cancelled" && order.note.isBlank()) {
                Text("Cancelled", Modifier.padding(top = 6.dp), color = Color(0xFF9A3E32), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PrintStatusChip("Cashier", order.printJobs["cashier"])
                PrintStatusChip("Kitchen", order.printJobs["kitchen"])
                Surface(color = if (order.paymentStatus == "paid") Color(0xFFEAF4ED) else Color(0xFFFFF4E9), shape = RoundedCornerShape(8.dp)) {
                    Text(order.paymentStatus.uppercase(), Modifier.padding(horizontal = 7.dp, vertical = 5.dp), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = if (order.paymentStatus == "paid") Color(0xFF17653D) else Color(0xFFA96308))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f).height(MinTouchTarget)) { Text("Details", fontSize = 11.sp) }
                when (order.status) {
                    "pending" -> {
                        CashierActionButton("Accept & print", busy, Modifier.weight(1f), onAccept)
                        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.height(MinTouchTarget).semantics { contentDescription = "Cancel order ${order.number}" }) { Text("×") }
                    }
                    "accepted", "preparing" -> {
                        if (order.paymentStatus != "paid") CashierActionButton("Take payment", busy, Modifier.weight(1f), onPayment)
                        else OutlinedButton(onClick = onReprint, enabled = !busy, modifier = Modifier.weight(1f).height(MinTouchTarget)) { Text("Reprint", fontSize = 11.sp) }
                        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.height(MinTouchTarget)) { Text("×") }
                    }
                    "ready" -> {
                        if (order.paymentStatus != "paid") CashierActionButton("Take payment", busy, Modifier.weight(1f), onPayment)
                        else CashierActionButton("Complete handoff", busy, Modifier.weight(1f), onComplete)
                        OutlinedButton(onClick = onReprint, enabled = !busy, modifier = Modifier.height(MinTouchTarget)) { Text("⎙", fontSize = 14.sp) }
                    }
                    "completed", "cancelled" -> {
                        OutlinedButton(onClick = onReprint, enabled = !busy, modifier = Modifier.weight(1f).height(MinTouchTarget)) { Text("Reprint", fontSize = 11.sp) }
                        if (order.paymentStatus == "paid" || order.paymentStatus == "partially_refunded") {
                            OutlinedButton(onClick = onRefund, enabled = !busy, modifier = Modifier.height(MinTouchTarget)) { Text("Refund", fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrintStatusChip(label: String, state: String?) {
    val displayState = state ?: "not queued"
    val ok = state == "printed"
    Surface(color = if (ok) Color(0xFFEAF4ED) else Color(0xFFF4F6F4), shape = RoundedCornerShape(8.dp)) {
        Text("$label: ${displayState.replace('_', ' ')}", Modifier.padding(horizontal = 7.dp, vertical = 5.dp), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = if (ok) Color(0xFF17653D) else Color(0xFF69726C))
    }
}

@Composable
private fun CashierActionButton(label: String, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.material3.Button(onClick = onClick, enabled = !busy, modifier = modifier.height(MinTouchTarget)) {
        if (busy) CircularProgressIndicator(Modifier.padding(2.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(label, fontSize = 11.sp)
    }
}

