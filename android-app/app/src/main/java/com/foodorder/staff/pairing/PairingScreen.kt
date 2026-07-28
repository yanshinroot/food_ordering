package com.foodorder.staff.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodorder.staff.storage.DeviceIdentity
import com.foodorder.staff.ui.components.MinTouchTarget

/**
 * Shared enrollment screen used by both flavors. [expectedRoleLabel] is
 * purely display text ("Cashier" / "Kitchen"); the actual enforcement of
 * "this code must match this build's role" happens in [PairingViewModel]
 * and, ultimately, is only ever *trusted* because the server independently
 * checks device.target on every action — see AppEnvironment/RoleConfig.
 */
@Composable
fun PairingScreen(
    expectedRoleLabel: String,
    viewModel: PairingViewModel,
    initialServerUrl: String,
    deviceUuid: String,
    appVersion: String,
    defaultDeviceName: String,
    onServerUrlChanged: (String) -> Unit,
    onPaired: (DeviceIdentity) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(defaultDeviceName) }

    Box(Modifier.fillMaxSize().background(Color(0xFFF5F7F5)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Pair this $expectedRoleLabel device", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Text(
                "Ask a manager for a $expectedRoleLabel pairing code from Odoo, then enter it below. No Odoo login is needed on this device.",
                fontSize = 12.sp, color = Color(0xFF69726C), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it; onServerUrlChanged(it) },
                label = { Text("Odoo server URL") },
                placeholder = { Text("https://odoo.example.com") },
                singleLine = true,
                enabled = state !is PairingUiState.Loading,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(16) },
                label = { Text("Pairing code") },
                placeholder = { Text("e.g. 7F3KQ9RT") },
                singleLine = true,
                enabled = state !is PairingUiState.Loading,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it.take(120) },
                label = { Text("Device name (optional)") },
                singleLine = true,
                enabled = state !is PairingUiState.Loading,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )

            when (val current = state) {
                is PairingUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 10.dp))
                    Text("Pairing…")
                }
                is PairingUiState.Error -> Surface(
                    color = Color(0xFFFFEEE8), shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Text(current.message, Modifier.padding(12.dp), color = Color(0xFFA84B2C), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                else -> {}
            }

            Button(
                onClick = { viewModel.submit(serverUrl, code, deviceName, deviceUuid, appVersion) },
                enabled = state !is PairingUiState.Loading,
                modifier = Modifier.fillMaxWidth().height(MinTouchTarget + 8.dp),
            ) { Text(if (state is PairingUiState.Error) "Retry pairing" else "Pair device") }

            Text(
                "The device key this creates is stored encrypted on this device and is never shown again after pairing.",
                fontSize = 10.sp, color = Color(0xFF8A918C), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }

    if (state is PairingUiState.Paired) {
        onPaired((state as PairingUiState.Paired).identity)
        viewModel.resetToIdle()
    }
}
