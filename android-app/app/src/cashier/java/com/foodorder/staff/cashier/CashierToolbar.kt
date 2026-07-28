package com.foodorder.staff.cashier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodorder.staff.net.CashSession
import com.foodorder.staff.ui.components.ConnectionChip
import com.foodorder.staff.ui.components.MinTouchTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CashierToolbar(
    session: CashSession?,
    connected: Boolean,
    failedPrintCount: Int,
    shiftEnabled: Boolean,
    lastSyncMillis: Long?,
    onOpenSession: () -> Unit,
    onWalkIn: () -> Unit,
    onSettings: () -> Unit,
    onManualRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Cashier", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                lastSyncMillis?.let { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it)) } ?: "—",
                fontSize = 11.sp,
            )
        }
        LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Button(onClick = onWalkIn, modifier = Modifier.height(MinTouchTarget)) { Text("+ New walk-in", fontSize = 11.sp) }
            }
            if (shiftEnabled) {
                item {
                    OutlinedButton(onClick = onOpenSession, modifier = Modifier.height(MinTouchTarget)) {
                        Text(if (session != null) "Shift open" else "Shift closed — tap to open", fontSize = 10.sp)
                    }
                }
            }
            item { ConnectionChip(if (connected) "Live" else "Offline", connected) }
            item { ConnectionChip(if (failedPrintCount > 0) "$failedPrintCount print failed" else "Printer OK", failedPrintCount == 0) }
            item { OutlinedButton(onClick = onManualRefresh, modifier = Modifier.height(MinTouchTarget)) { Text("Refresh", fontSize = 11.sp) } }
            item { OutlinedButton(onClick = onSettings, modifier = Modifier.height(MinTouchTarget)) { Text("Setup", fontSize = 11.sp) } }
        }
    }
}
