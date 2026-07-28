package com.foodorder.staff.cashier

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.foodorder.staff.net.StaffOrder

/** A cancellation always requires a typed reason — this is the only way
 *  the cancel action is reachable, so there's no code path that fires the
 *  server call with a blank reason. */
@Composable
fun CancelReasonDialog(order: StaffOrder, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cancel ${order.number}?", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column {
                Text("This removes the order from the live Cashier and Kitchen queues.")
                OutlinedTextField(reason, { reason = it.take(200) }, label = { Text("Reason (required)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = { if (reason.isNotBlank()) onConfirm(reason) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA84B2C)),
                enabled = reason.isNotBlank(),
            ) { Text("Cancel order") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Go back") } },
    )
}
