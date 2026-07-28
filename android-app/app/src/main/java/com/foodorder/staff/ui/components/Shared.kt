package com.foodorder.staff.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat

val MinTouchTarget = 44.dp

fun formatMoney(value: Double, currency: String): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }.format(value) + " " + currency

fun cleanQuantity(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

/** Status is always conveyed via this uppercase text label, never by color
 *  alone, so the UI stays usable for color-blind users and reads correctly
 *  on a black-and-white printout of a screenshot. */
@Composable
fun StatusPill(status: String, large: Boolean = false) {
    val color = when (status) {
        "pending" -> Color(0xFFF06A32)
        "preparing" -> Color(0xFFD18A19)
        "ready", "completed" -> Color(0xFF17653D)
        "cancelled" -> Color(0xFF9A3E32)
        else -> Color(0xFF4D6255)
    }
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(20.dp)) {
        Text(
            status.uppercase(),
            Modifier.padding(horizontal = if (large) 13.dp else 9.dp, vertical = if (large) 8.dp else 5.dp),
            color = color, fontSize = if (large) 12.sp else 8.sp, fontWeight = FontWeight.Bold,
        )
    }
}

enum class SlaState { NORMAL, APPROACHING, OVERDUE }

fun slaStateFor(elapsedMinutes: Int, warnMinutes: Int, lateMinutes: Int): SlaState = when {
    elapsedMinutes >= lateMinutes -> SlaState.OVERDUE
    elapsedMinutes >= warnMinutes -> SlaState.APPROACHING
    else -> SlaState.NORMAL
}

/** Always shows the numeric elapsed minutes alongside the NORMAL /
 *  APPROACHING / OVERDUE text label — never color-only. */
@Composable
fun SlaBadge(elapsedMinutes: Int, state: SlaState, modifier: Modifier = Modifier) {
    val (bg, fg, label) = when (state) {
        SlaState.NORMAL -> Triple(Color(0xFFF4F6F4), Color(0xFF4D6255), "NORMAL")
        SlaState.APPROACHING -> Triple(Color(0xFFFFF4E4), Color(0xFFA66408), "APPROACHING")
        SlaState.OVERDUE -> Triple(Color(0xFFFFF0EB), Color(0xFFB44E28), "OVERDUE")
    }
    Row(
        modifier
            .background(bg, RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { contentDescription = "$elapsedMinutes minutes elapsed, $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$elapsedMinutes min", color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 8.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun EmptyState(title: String, caption: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✓", color = Color(0xFF17653D), fontSize = 38.sp)
            Text(title, fontWeight = FontWeight.Bold)
            Text(caption, fontSize = 11.sp, color = Color(0xFF69726C))
        }
    }
}

@Composable
fun LoadingState(message: String) {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(message, fontSize = 12.sp)
    }
}

/** role="status" + a polite live region so a screen reader announces
 *  status/error text changes without needing focus moved to it. */
@Composable
fun StatusBanner(message: String, error: Boolean, loading: Boolean, onRefresh: () -> Unit) {
    val bg = if (error) Color(0xFFFFEEE8) else Color(0xFFEDF6F0)
    val fg = if (error) Color(0xFFA84B2C) else Color(0xFF17653D)
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
        else Text(if (error) "!" else "●", color = fg)
        Spacer(Modifier.width(8.dp))
        Text(message, Modifier.weight(1f), color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.height(MinTouchTarget)) { Text("Refresh") }
    }
}

@Composable
fun OfflineBanner(lastSyncLabel: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFEEE8), RoundedCornerShape(12.dp))
            .padding(10.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", color = Color(0xFFA84B2C))
        Spacer(Modifier.width(8.dp))
        Text(
            if (lastSyncLabel != null) "Offline — showing data from $lastSyncLabel" else "Offline — no data loaded yet",
            color = Color(0xFFA84B2C), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun ConnectionChip(label: String, active: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(if (active) Color(0xFFEAF4ED) else Color(0xFFF0F2F0), RoundedCornerShape(9.dp))
            .padding(9.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("●", color = if (active) Color(0xFF17653D) else Color(0xFF8A918C), fontSize = 8.sp)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (active) Color(0xFF17653D) else Color(0xFF69726C))
    }
}

/** Every destructive action (cancel, refund, reset device) routes through
 *  this so none of them fire on a single accidental tap. */
@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, destructive: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.ExtraBold) },
        text = { Text(body) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = Color(0xFFA84B2C)) else ButtonDefaults.buttonColors(),
                modifier = Modifier.height(MinTouchTarget),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.height(MinTouchTarget)) { Text("Go back") } },
    )
}

/** 12-key numeric keypad, each key sized to at least [MinTouchTarget]. */
@Composable
fun NumericKeypad(onDigit: (Char) -> Unit, onClear: () -> Unit, onBackspace: () -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "⌫")
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.height(220.dp)) {
        items(keys) { key ->
            OutlinedButton(
                onClick = {
                    when (key) {
                        "C" -> onClear()
                        "⌫" -> onBackspace()
                        else -> onDigit(key[0])
                    }
                },
                modifier = Modifier.padding(4.dp).fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(10.dp),
            ) { Text(key, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Shared line-item renderer (product, modifiers, own-cup count, note) used
 *  by both the Cashier and Kitchen order cards — kept in one place instead
 *  of duplicated per flavor. */
@Composable
fun OrderLinesList(lines: List<com.foodorder.staff.net.StaffOrderLine>, compact: Boolean = false) {
    Column {
        lines.forEach { line ->
            Row(Modifier.fillMaxWidth().padding(top = if (compact) 7.dp else 12.dp)) {
                Text(
                    "${cleanQuantity(line.quantity)}×",
                    Modifier.width(if (compact) 28.dp else 40.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = if (compact) 12.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Column {
                    Text(line.name, fontSize = if (compact) 12.sp else 16.sp, fontWeight = FontWeight.SemiBold)
                    val modifierText = line.modifiers.joinToString("; ") { "${it.group}: ${it.option}" }
                        .ifBlank { line.modifiersSummary }
                    if (modifierText.isNotBlank()) {
                        Text(modifierText, fontSize = if (compact) 9.sp else 12.sp, color = Color(0xFF17653D))
                    }
                    if (line.ownCupQuantity > 0) {
                        Text("Own cup × ${line.ownCupQuantity}", fontSize = if (compact) 9.sp else 12.sp, color = Color(0xFF17653D))
                    }
                    if (line.note.isNotBlank()) {
                        Text("Note: ${line.note}", fontSize = if (compact) 9.sp else 12.sp, color = Color(0xFFA66408), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun QuickAmountRow(due: Double, currency: String, onPick: (Double) -> Unit) {
    fun roundUp(value: Double, step: Double) = kotlin.math.ceil(value / step) * step
    val options = listOf(
        kotlin.math.ceil(due),
        roundUp(due, 1000.0),
        roundUp(due, 5000.0),
        roundUp(due, 10000.0),
    ).distinct().filter { it > 0 }.take(4)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { amount ->
            OutlinedButton(onClick = { onPick(amount) }, modifier = Modifier.weight(1f).height(MinTouchTarget)) {
                Text(formatMoney(amount, currency), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
