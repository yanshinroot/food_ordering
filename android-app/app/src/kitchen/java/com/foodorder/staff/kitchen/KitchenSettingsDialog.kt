package com.foodorder.staff.kitchen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodorder.staff.AppEnvironment
import com.foodorder.staff.PrinterBridge
import com.foodorder.staff.PrinterProtocol
import com.foodorder.staff.PrinterTransport
import com.foodorder.staff.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KitchenSettingsDialog(env: AppEnvironment, onDismiss: () -> Unit) {
    val prefs = env.uiPreferences
    var transport by remember { mutableStateOf(prefs.transport) }
    var network by remember { mutableStateOf(prefs.networkPrinter) }
    var protocol by remember { mutableStateOf(prefs.protocol) }
    var labelWidth by remember { mutableStateOf(prefs.labelWidthMm.toString()) }
    var textScale by remember { mutableIntStateOf(prefs.textScale) }
    var tearMargin by remember { mutableStateOf(prefs.tearMarginMm.toString()) }
    var soundEnabled by remember { mutableStateOf(prefs.soundEnabled) }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val usbPrinters = remember { PrinterBridge.usbPrinters(env.activity) }
    var usbId by remember { mutableIntStateOf(prefs.usbDeviceId.takeIf { saved -> usbPrinters.any { it.deviceId == saved } } ?: -1) }
    var bluetoothGranted by remember { mutableStateOf(PrinterBridge.bluetoothPermissionGranted(env.activity)) }
    var bluetoothPrinters by remember { mutableStateOf(PrinterBridge.bluetoothPrinters(env.activity)) }
    var bluetoothAddress by remember { mutableStateOf(prefs.bluetoothAddress) }
    val scope = rememberCoroutineScope()

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        bluetoothGranted = granted
        if (granted) bluetoothPrinters = PrinterBridge.bluetoothPrinters(env.activity)
    }

    fun currentTransportArgs(): Triple<String, Int, String> = when (transport) {
        PrinterTransport.NETWORK -> Triple(network, -1, "")
        PrinterTransport.USB -> Triple("", usbId, "")
        PrinterTransport.BLUETOOTH -> Triple("", -1, bluetoothAddress)
    }

    fun persist() {
        prefs.transport = transport
        prefs.networkPrinter = network
        prefs.protocol = protocol
        prefs.labelWidthMm = labelWidth.toIntOrNull() ?: prefs.labelWidthMm
        prefs.tearMarginMm = tearMargin.toIntOrNull() ?: prefs.tearMarginMm
        prefs.usbDeviceId = usbId
        prefs.bluetoothAddress = bluetoothAddress
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Setup", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Paired as: ${env.identity.deviceName.ifBlank { env.identity.role }}", fontWeight = FontWeight.SemiBold)
                Text("Server: ${env.identity.serverUrl}", fontSize = 11.sp)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Sound alerts", modifier = Modifier.weight(1f))
                    Switch(checked = soundEnabled, onCheckedChange = { soundEnabled = it; prefs.soundEnabled = it })
                }
                Text("PRINTER", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF17653D))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrinterProtocol.entries.forEach { option ->
                        OutlinedButton(
                            onClick = { protocol = option },
                            colors = if (protocol == option) ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFEAF4ED)) else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.weight(1f),
                        ) { Text(protocolLabel(option)) }
                    }
                }
                if (protocol == PrinterProtocol.TSPL) {
                    OutlinedTextField(labelWidth, { labelWidth = it }, label = { Text("Label width (mm)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(tearMargin, { tearMargin = it }, label = { Text("Tear margin (mm)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                Text("CONNECTION", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF17653D), modifier = Modifier.padding(top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrinterTransport.entries.forEach { option ->
                        OutlinedButton(
                            onClick = { transport = option },
                            colors = if (transport == option) ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFEAF4ED)) else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.weight(1f),
                        ) { Text(option.label, fontSize = 11.sp) }
                    }
                }

                when (transport) {
                    PrinterTransport.NETWORK -> OutlinedTextField(
                        network, { network = it }, label = { Text("Network printer IP:9100") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    PrinterTransport.USB -> Column(Modifier.padding(top = 8.dp)) {
                        if (usbPrinters.isEmpty()) {
                            Text("No USB printer detected. Connect it via USB OTG, then reopen Setup.", fontSize = 10.sp, color = Color(0xFFA84B2C))
                        } else {
                            usbPrinters.forEach { printer ->
                                Text((if (usbId == printer.deviceId) "● " else "○ ") + printer.label, Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                    PrinterTransport.BLUETOOTH -> Column(Modifier.padding(top = 8.dp)) {
                        if (!bluetoothGranted) {
                            Text("Bluetooth permission is needed to see paired printers.", fontSize = 10.sp, color = Color(0xFFA84B2C))
                            OutlinedButton(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                    } else {
                                        bluetoothGranted = true
                                        bluetoothPrinters = PrinterBridge.bluetoothPrinters(env.activity)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            ) { Text("Grant Bluetooth access") }
                        } else if (bluetoothPrinters.isEmpty()) {
                            Text(
                                "No paired Bluetooth printer found. Pair it first in Android Settings → Bluetooth, then reopen Setup.",
                                fontSize = 10.sp, color = Color(0xFFA84B2C),
                            )
                        } else {
                            bluetoothPrinters.forEach { printer ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    OutlinedButton(
                                        onClick = { bluetoothAddress = printer.address },
                                        colors = if (bluetoothAddress == printer.address) ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFEAF4ED)) else ButtonDefaults.outlinedButtonColors(),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text((if (bluetoothAddress == printer.address) "● " else "○ ") + printer.label, fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        persist()
                        testing = true
                        val (net, usb, bt) = currentTransportArgs()
                        scope.launch {
                            val outcome = runCatching {
                                withContext(Dispatchers.IO) {
                                    PrinterBridge.print(env.activity, PrinterBridge.testReceipt(protocol, prefs.labelWidthMm, textScale, prefs.tearMarginMm), net, usb, bt)
                                }
                            }
                            message = outcome.fold({ "Test receipt sent" }, { it.message ?: "Test print failed" })
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) { Text(if (testing) "Sending test…" else "Test printer") }
                if (message.isNotBlank()) Text(message, color = Color(0xFF17653D), fontSize = 10.sp)

                Text("DEVICE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA84B2C), modifier = Modifier.padding(top = 14.dp))
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) { Text("Reset device / re-pair") }
            }
        },
        confirmButton = {
            Button(onClick = { persist(); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset this device?",
            body = "This clears the paired device key. The device will need a new pairing code before it can be used again.",
            confirmLabel = "Reset device",
            onConfirm = { confirmReset = false; env.sessionManager.resetDevice() },
            onDismiss = { confirmReset = false },
        )
    }
}

private fun protocolLabel(protocol: PrinterProtocol) = when (protocol) {
    PrinterProtocol.ESC_POS -> "ESC/POS"
    PrinterProtocol.SATO_SBPL -> "SATO"
    PrinterProtocol.TSPL -> "TSPL"
}
