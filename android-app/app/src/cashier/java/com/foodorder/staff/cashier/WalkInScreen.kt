package com.foodorder.staff.cashier

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foodorder.staff.AppEnvironment
import com.foodorder.staff.core.ApiError
import com.foodorder.staff.core.ApiResult
import com.foodorder.staff.net.Catalog
import com.foodorder.staff.net.FoodProduct
import com.foodorder.staff.net.ModifierGroup
import com.foodorder.staff.net.SelectedModifier
import com.foodorder.staff.net.WalkInLine
import com.foodorder.staff.ui.components.MinTouchTarget
import com.foodorder.staff.ui.components.formatMoney
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.ceil

@Composable
fun WalkInScreen(
    env: AppEnvironment,
    catalog: Catalog,
    draft: WalkInDraft,
    onDraftChanged: (WalkInDraft) -> Unit,
    onDismiss: () -> Unit,
    onSubmitted: (com.foodorder.staff.net.StaffOrder) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("all") }
    var cash by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickerProduct by remember { mutableStateOf<FoodProduct?>(null) }
    val scope = rememberCoroutineScope()

    val total = CartMath.total(draft.cart.values, catalog.ownCupDiscount)
    val received = cash.toDoubleOrNull() ?: 0.0

    val visibleProducts = catalog.products.filter {
        (category == "all" || it.category == category) && it.name.contains(search.trim(), ignoreCase = true)
    }

    fun addToCart(product: FoodProduct, modifiers: List<SelectedModifier>) {
        val key = product.id.toString() + ":" + modifiers.map(SelectedModifier::optionId).sorted().joinToString(",")
        val existing = draft.cart[key]
        val updated = draft.cart + (key to (existing?.copy(quantity = existing.quantity + 1) ?: WalkInCartLine(product, modifiers = modifiers)))
        onDraftChanged(draft.copy(cart = updated))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = Color(0xFFF5F7F5)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("New walk-in order", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        Text("CASHIER POS · CASH ONLY", color = Color(0xFF17653D), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = onDismiss) { Text("Back to orders") }
                }
                catalog.promotion?.let { promo ->
                    Surface(color = Color(0xFFFFF6E8), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(promo.headline, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            if (promo.subtext.isNotBlank()) Text(promo.subtext, fontSize = 10.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(.58f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedTextField(search, { search = it }, placeholder = { Text("Search menu") }, singleLine = true, modifier = Modifier.weight(1f))
                            listOf("all" to "All", "drinks" to "Drinks", "meals" to "Meals").forEach { (value, label) ->
                                if (category == value) Button(onClick = { category = value }) { Text(label) }
                                else OutlinedButton(onClick = { category = value }) { Text(label) }
                            }
                        }
                        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            items(visibleProducts, key = FoodProduct::id) { product ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp),
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(product.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        Text(formatMoney(product.price, product.currency), color = Color(0xFF68716B), fontSize = 11.sp)
                                        Button(
                                            onClick = { if (product.modifierGroups.isNotEmpty()) pickerProduct = product else addToCart(product, emptyList()) },
                                            modifier = Modifier.fillMaxWidth().height(MinTouchTarget),
                                        ) { Text(if (product.modifierGroups.isNotEmpty()) "+ Choose options" else "+ Add", fontSize = 11.sp) }
                                    }
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(.42f).fillMaxHeight().background(Color.White, RoundedCornerShape(16.dp)).padding(15.dp).verticalScroll(rememberScrollState())) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Current basket", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            Text("${draft.cart.values.sumOf { it.quantity }} items", color = Color(0xFF17653D), fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        if (draft.cart.isEmpty()) Text("Select products from the menu.", Modifier.padding(vertical = 20.dp), color = Color(0xFF68716B))
                        draft.cart.forEach { (key, line) ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(line.product.name, fontWeight = FontWeight.Bold)
                                        if (line.modifiers.isNotEmpty()) {
                                            Text(line.modifiers.joinToString("; ") { "${it.groupName}: ${it.optionName}" }, fontSize = 10.sp, color = Color(0xFF17653D))
                                        }
                                    }
                                    TextButton(onClick = {
                                        val updated = if (line.quantity <= 1) draft.cart - key else draft.cart + (key to line.copy(quantity = line.quantity - 1, ownCups = line.ownCups.coerceAtMost(line.quantity - 1)))
                                        onDraftChanged(draft.copy(cart = updated))
                                    }) { Text("−") }
                                    Text(line.quantity.toString(), fontWeight = FontWeight.Bold)
                                    TextButton(onClick = { onDraftChanged(draft.copy(cart = draft.cart + (key to line.copy(quantity = line.quantity + 1)))) }) { Text("+") }
                                }
                                if (line.product.ownCupEligible) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Own cups · save ${formatMoney(catalog.ownCupDiscount, line.product.currency)}", Modifier.weight(1f), fontSize = 10.sp, color = Color(0xFF17653D))
                                        TextButton(onClick = { onDraftChanged(draft.copy(cart = draft.cart + (key to line.copy(ownCups = (line.ownCups - 1).coerceAtLeast(0))))) }, enabled = line.ownCups > 0) { Text("−") }
                                        Text("${line.ownCups}/${line.quantity}")
                                        TextButton(onClick = { onDraftChanged(draft.copy(cart = draft.cart + (key to line.copy(ownCups = (line.ownCups + 1).coerceAtMost(line.quantity))))) }, enabled = line.ownCups < line.quantity) { Text("+") }
                                    }
                                }
                                OutlinedTextField(
                                    line.note, { value -> onDraftChanged(draft.copy(cart = draft.cart + (key to line.copy(note = value)))) },
                                    label = { Text("Item note") }, modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            HorizontalDivider()
                        }
                        OutlinedTextField(draft.customerName, { onDraftChanged(draft.copy(customerName = it)) }, label = { Text("Customer") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        OutlinedTextField(draft.phone, { onDraftChanged(draft.copy(phone = it)) }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(draft.orderNote, { onDraftChanged(draft.copy(orderNote = it)) }, label = { Text("Order note") }, modifier = Modifier.fillMaxWidth())
                        Text("CASH PAYMENT", color = Color(0xFF17653D), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                        OutlinedTextField(cash.ifBlank { ceil(total).toLong().toString() }, { cash = it.filter(Char::isDigit) }, label = { Text("Cash received") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Surface(Modifier.fillMaxWidth().padding(vertical = 10.dp), color = Color(0xFFEAF4ED), shape = RoundedCornerShape(11.dp)) {
                            Column(Modifier.padding(13.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total"); Text(formatMoney(total, "MMK"), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Change"); Text(formatMoney((received - total).coerceAtLeast(0.0), "MMK"), fontWeight = FontWeight.Bold) }
                            }
                        }
                        error?.let { Text(it, color = Color(0xFFA84B2C), fontSize = 11.sp) }
                        Button(
                            onClick = {
                                if (submitting) return@Button
                                submitting = true
                                error = null
                                scope.launch {
                                    val idempotencyKey = env.idempotency.keyFor("walkin-submit:${draft.hashCode()}")
                                    val lines = draft.cart.values.map { line ->
                                        WalkInLine(line.product.id, line.quantity, line.ownCups, line.note, line.modifiers.map(SelectedModifier::optionId))
                                    }
                                    when (val result = env.apiClient.createWalkIn(draft.customerName, draft.phone, draft.orderNote, received, lines, idempotencyKey)) {
                                        is ApiResult.Success -> onSubmitted(result.value)
                                        is ApiResult.Failure -> error = result.error.userMessage
                                    }
                                    submitting = false
                                }
                            },
                            enabled = !submitting && draft.cart.isNotEmpty() && received >= total,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text(if (submitting) "Sending…" else "Charge & send to kitchen") }
                    }
                }
            }
        }
    }

    pickerProduct?.let { product ->
        ModifierPickerDialog(
            product = product,
            onDismiss = { pickerProduct = null },
            onConfirm = { modifiers -> addToCart(product, modifiers); pickerProduct = null },
        )
    }
}

@Composable
private fun ModifierPickerDialog(product: FoodProduct, onDismiss: () -> Unit, onConfirm: (List<SelectedModifier>) -> Unit) {
    val selections = remember { mutableStateOf<Map<Int, Set<Int>>>(emptyMap()) }
    fun toggle(group: ModifierGroup, optionId: Int) {
        val current = selections.value[group.id] ?: emptySet()
        val isSingle = group.selectionType == "single" || group.maxSelection <= 1
        val updated = if (isSingle) setOf(optionId)
            else if (current.contains(optionId)) current - optionId
            else if (current.size < group.maxSelection || group.maxSelection <= 0) current + optionId else current
        selections.value = selections.value + (group.id to updated)
    }
    val allSatisfied = product.modifierGroups.all { group ->
        val count = (selections.value[group.id] ?: emptySet()).size
        !group.required || count >= group.minSelection.coerceAtLeast(if (group.required) 1 else 0)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(product.name, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                product.modifierGroups.forEach { group ->
                    Text("${group.name}${if (group.required) " (required)" else ""}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    val selected = selections.value[group.id] ?: emptySet()
                    group.options.forEach { option ->
                        val isSingle = group.selectionType == "single" || group.maxSelection <= 1
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (isSingle) {
                                RadioButton(selected = selected.contains(option.id), onClick = { toggle(group, option.id) })
                            } else {
                                Checkbox(checked = selected.contains(option.id), onCheckedChange = { toggle(group, option.id) })
                            }
                            Text(option.name, Modifier.weight(1f))
                            if (option.priceExtra != 0.0) Text("+" + formatMoney(option.priceExtra, product.currency), fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosen = product.modifierGroups.flatMap { group ->
                        (selections.value[group.id] ?: emptySet()).mapNotNull { optionId ->
                            group.options.find { it.id == optionId }?.let { option ->
                                SelectedModifier(option.id, option.name, group.name, option.priceExtra)
                            }
                        }
                    }
                    onConfirm(chosen)
                },
                enabled = allSatisfied,
            ) { Text("Add to basket") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
