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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.sp
import com.foodorder.staff.AppEnvironment
import com.foodorder.staff.core.ApiError
import com.foodorder.staff.core.ApiResult
import com.foodorder.staff.net.StaffOrder
import com.foodorder.staff.ui.components.formatMoney
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A device-key refund is never trusted as "manager-authorized" just
 * because it came from a Cashier-role device (see action_food_refund on
 * the backend: actor_user is always null for device calls, so is_manager
 * is always false there) — a manager PIN is always required here.
 */
@Composable
fun RefundDialog(order: StaffOrder, env: AppEnvironment, onDismiss: () -> Unit, onRefunded: () -> Unit) {
    var amount by remember { mutableStateOf(order.total.toLong().toString()) }
    var reason by remember { mutableStateOf("") }
    var managerPin by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Refund ${order.number}", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column {
                Text("Order total: ${formatMoney(order.total, order.currency)}", fontSize = 12.sp)
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Amount to refund") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(reason, { reason = it.take(200) }, label = { Text("Reason (required)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    managerPin, { managerPin = it.filter(Char::isDigit).take(8) },
                    label = { Text("Manager PIN (required)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text("This is recorded as a cash-out against the current shift.", fontSize = 10.sp, color = Color(0xFF69726C))
                error?.let { Text(it, color = Color(0xFFA84B2C), fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (submitting) return@Button
                    val amountValue = amount.toDoubleOrNull() ?: 0.0
                    if (reason.isBlank() || managerPin.isBlank() || amountValue <= 0) {
                        error = "Enter a reason, manager PIN, and an amount greater than zero."
                        return@Button
                    }
                    submitting = true
                    error = null
                    scope.launch {
                        val requestId = env.idempotency.keyFor("refund:${order.id}")
                        val body = JSONObject().put("amount", amountValue).put("reason", reason).put("manager_pin", managerPin)
                        when (val result = env.apiClient.orderAction(order.id, "refund", requestId, order.stateVersion, body)) {
                            is ApiResult.Success -> {
                                env.idempotency.retire("refund:${order.id}")
                                onRefunded()
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
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA84B2C)),
                enabled = !submitting,
            ) { Text(if (submitting) "Refunding…" else "Confirm refund") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}
