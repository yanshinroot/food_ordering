package com.foodorder.staff.cashier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import com.foodorder.staff.AppEnvironment
import com.foodorder.staff.core.ApiResult
import com.foodorder.staff.net.CashSession
import com.foodorder.staff.ui.components.formatMoney
import kotlinx.coroutines.launch

@Composable
fun CashSessionDialog(env: AppEnvironment, session: CashSession?, onDismiss: () -> Unit, onChanged: (CashSession?) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var openingCash by remember { mutableStateOf("0") }
    var movementAmount by remember { mutableStateOf("") }
    var movementReason by remember { mutableStateOf("") }
    var closingActual by remember { mutableStateOf("") }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            block()
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (session == null) "Open shift" else "Shift", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column {
                if (session == null) {
                    Text("Count the float in the drawer and enter it below to open the shift.")
                    OutlinedTextField(openingCash, { openingCash = it.filter(Char::isDigit) }, label = { Text("Opening cash") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Opening cash: ${formatMoney(session.openingCash, "MMK")}")
                    Text("Sales: ${formatMoney(session.saleTotal, "MMK")}")
                    Text("Refunds: ${formatMoney(session.refundTotal, "MMK")}")
                    Text("Cash in: ${formatMoney(session.cashInTotal, "MMK")}")
                    Text("Cash out: ${formatMoney(session.cashOutTotal, "MMK")}")
                    Text("Expected in drawer: ${formatMoney(session.closingCashExpected, "MMK")}", fontWeight = FontWeight.Bold)
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("Add a cash movement", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(movementAmount, { movementAmount = it.filter(Char::isDigit) }, label = { Text("Amount") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(movementReason, { movementReason = it.take(200) }, label = { Text("Reason") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val amount = movementAmount.toDoubleOrNull() ?: 0.0
                                run {
                                    when (val result = env.apiClient.recordMovement("cash_in", amount, movementReason)) {
                                        is ApiResult.Success -> { onChanged(result.value); movementAmount = ""; movementReason = "" }
                                        is ApiResult.Failure -> { env.sessionManager.reactTo(result.error); error = result.error.userMessage }
                                    }
                                }
                            },
                            enabled = !busy && (movementAmount.toDoubleOrNull() ?: 0.0) > 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("Cash in") }
                        OutlinedButton(
                            onClick = {
                                val amount = movementAmount.toDoubleOrNull() ?: 0.0
                                run {
                                    when (val result = env.apiClient.recordMovement("cash_out", amount, movementReason)) {
                                        is ApiResult.Success -> { onChanged(result.value); movementAmount = ""; movementReason = "" }
                                        is ApiResult.Failure -> { env.sessionManager.reactTo(result.error); error = result.error.userMessage }
                                    }
                                }
                            },
                            enabled = !busy && (movementAmount.toDoubleOrNull() ?: 0.0) > 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("Cash out") }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("Close shift", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(closingActual, { closingActual = it.filter(Char::isDigit) }, label = { Text("Actual cash counted") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = Color(0xFFA84B2C)) }
            }
        },
        confirmButton = {
            if (session == null) {
                Button(onClick = {
                    run {
                        when (val result = env.apiClient.openSession(openingCash.toDoubleOrNull() ?: 0.0)) {
                            is ApiResult.Success -> { onChanged(result.value); onDismiss() }
                            is ApiResult.Failure -> { env.sessionManager.reactTo(result.error); error = result.error.userMessage }
                        }
                    }
                }, enabled = !busy) { Text("Open shift") }
            } else {
                Button(onClick = {
                    run {
                        when (val result = env.apiClient.closeSession(closingActual.toDoubleOrNull() ?: 0.0)) {
                            is ApiResult.Success -> { onChanged(null); onDismiss() }
                            is ApiResult.Failure -> { env.sessionManager.reactTo(result.error); error = result.error.userMessage }
                        }
                    }
                }, enabled = !busy && closingActual.isNotBlank()) { Text("Close shift") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
    )
}
