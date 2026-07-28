package com.foodorder.staff.cashier

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodorder.staff.AppEnvironment
import com.foodorder.staff.core.ApiError
import com.foodorder.staff.core.ApiResult
import com.foodorder.staff.core.PaymentMath
import com.foodorder.staff.net.StaffOrder
import com.foodorder.staff.ui.components.NumericKeypad
import com.foodorder.staff.ui.components.QuickAmountRow
import com.foodorder.staff.ui.components.formatMoney
import kotlin.math.ceil
import kotlinx.coroutines.launch

/** Amount entered is preserved across a recoverable error (network/server)
 *  since [amount] state lives in this composable and only clears on
 *  successful dismissal — a failed submit leaves the typed value intact
 *  for the cashier to just retry. */
@Composable
fun PaymentDialog(order: StaffOrder, env: AppEnvironment, onDismiss: () -> Unit, onPaid: (StaffOrder) -> Unit) {
    var amount by remember { mutableStateOf(ceil(order.total).toLong().toString()) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val received = amount.toDoubleOrNull() ?: 0.0

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Cash payment · ${order.number}", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column {
                Text(formatMoney(order.total, order.currency), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text("Amount due", fontSize = 10.sp, color = Color(0xFF69726C))
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Cash received") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                QuickAmountRow(order.total, order.currency) { amount = it.toLong().toString() }
                NumericKeypad(
                    onDigit = { digit -> amount = (amount + digit).trimStart('0').ifBlank { "0" } },
                    onClear = { amount = "" },
                    onBackspace = { amount = amount.dropLast(1) },
                )
                Text("Change: ${formatMoney(PaymentMath.changeDue(received, order.total), order.currency)}", color = Color(0xFF17653D), fontWeight = FontWeight.Bold)
                error?.let { Text(it, color = Color(0xFFA84B2C), fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (submitting) return@Button
                    submitting = true
                    error = null
                    scope.launch {
                        val requestId = env.idempotency.keyFor("payment:${order.id}")
                        when (val result = env.apiClient.collectPayment(order.id, received, requestId, order.stateVersion)) {
                            is ApiResult.Success -> {
                                env.idempotency.retire("payment:${order.id}")
                                onPaid(result.value)
                            }
                            is ApiResult.Failure -> {
                                env.sessionManager.reactTo(result.error)
                                error = if (result.error is ApiError.StaleVersion) {
                                    "This order changed on the server. Close this dialog and try again."
                                } else result.error.userMessage
                            }
                        }
                        submitting = false
                    }
                },
                enabled = !submitting && PaymentMath.canConfirmPayment(received, order.total),
            ) { Text(if (submitting) "Charging…" else "Confirm cash payment") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}
