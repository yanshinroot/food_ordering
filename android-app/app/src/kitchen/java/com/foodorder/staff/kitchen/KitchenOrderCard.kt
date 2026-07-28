package com.foodorder.staff.kitchen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodorder.staff.net.StaffOrder
import com.foodorder.staff.ui.components.MinTouchTarget
import com.foodorder.staff.ui.components.OrderLinesList
import com.foodorder.staff.ui.components.SlaBadge
import com.foodorder.staff.ui.components.slaStateFor

/** Deliberately has no payment/cash/refund fields or actions anywhere in
 *  this composable — see Step 7's exclusion list. The [StaffOrder] model
 *  itself still carries paymentStatus/amountReceived/changeAmount (the
 *  Cashier flavor needs those), but this card simply never reads them. */
@Composable
fun KitchenOrderCard(
    order: StaffOrder,
    busy: Boolean,
    warnMinutes: Int,
    lateMinutes: Int,
    onDetails: () -> Unit,
    onStartPreparing: () -> Unit,
    onMarkReady: () -> Unit,
    onReprint: () -> Unit,
) {
    val slaState = slaStateFor(order.elapsedMinutes, warnMinutes, lateMinutes)
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column {
                    Text("ORDER", fontSize = 8.sp, color = Color(0xFF8A918C), fontWeight = FontWeight.Bold)
                    Text(order.number, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                }
                SlaBadge(order.elapsedMinutes, slaState)
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFFEDF0EE))
            Text(order.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("${order.department} · ${order.floor}", color = Color(0xFF69726C), fontSize = 11.sp)
            OrderLinesList(order.lines)
            if (order.note.isNotBlank()) {
                Text(
                    "NOTE: ${order.note}",
                    Modifier.fillMaxWidth().padding(top = 10.dp).background(Color(0xFFFFF6E8), RoundedCornerShape(8.dp)).padding(10.dp),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF79531D),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(color = Color(0xFFF4F6F4), shape = RoundedCornerShape(8.dp)) {
                    Text(order.source.uppercase(), Modifier.padding(horizontal = 7.dp, vertical = 5.dp), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                val kitchenPrint = order.printJobs["kitchen"]
                Surface(color = if (kitchenPrint == "printed") Color(0xFFEAF4ED) else Color(0xFFF4F6F4), shape = RoundedCornerShape(8.dp)) {
                    Text("Print: ${(kitchenPrint ?: "not queued").replace('_', ' ')}", Modifier.padding(horizontal = 7.dp, vertical = 5.dp), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDetails, modifier = Modifier.height(MinTouchTarget)) { Text("Details", fontSize = 11.sp) }
                when (order.status) {
                    "accepted" -> KitchenActionButton("Start preparing", busy, Modifier.weight(1f), onStartPreparing)
                    "preparing" -> KitchenActionButton("Mark ready", busy, Modifier.weight(1f), onMarkReady)
                    "ready" -> Surface(Modifier.weight(1f), color = Color(0xFFEAF4ED), shape = RoundedCornerShape(10.dp)) {
                        Text(
                            "Ready for cashier",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = Color(0xFF17653D), fontWeight = FontWeight.Bold,
                        )
                    }
                }
                OutlinedButton(onClick = onReprint, enabled = !busy, modifier = Modifier.height(MinTouchTarget)) { Text("Reprint", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun KitchenActionButton(label: String, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.material3.Button(onClick = onClick, enabled = !busy, modifier = modifier.height(56.dp)) {
        if (busy) CircularProgressIndicator(Modifier.padding(2.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
