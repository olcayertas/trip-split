@file:OptIn(ExperimentalMaterial3Api::class)

package com.olcayertas.tripsplit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.olcayertas.tripsplit.R
import com.olcayertas.tripsplit.core.CURRENCIES
import com.olcayertas.tripsplit.core.InputResult
import com.olcayertas.tripsplit.core.MemberState
import com.olcayertas.tripsplit.core.parseAmount

private fun nameOk(s: String) = s.trim().let { it.isNotEmpty() && it.codePointCount(0, it.length) <= 60 }

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
        },
        confirmButton = { TextButton({ onSave(name.trim()) }, enabled = nameOk(name)) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** PLAN §7.1: "From now on" is the default. "Also earlier expenses" is offered only when there are some. */
@Composable
fun AddMemberDialog(hasExpenses: Boolean, onDismiss: () -> Unit, onAdd: (name: String, includeEarlier: Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var earlier by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_member_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it }, label = { Text(stringResource(R.string.member_name)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                if (hasExpenses) {
                    Column(Modifier.selectableGroup()) {
                        ChoiceRow(!earlier, { earlier = false }, stringResource(R.string.add_member_from_now), stringResource(R.string.add_member_from_now_help))
                        ChoiceRow(earlier, { earlier = true }, stringResource(R.string.add_member_earlier), stringResource(R.string.add_member_earlier_help))
                    }
                }
            }
        },
        confirmButton = { TextButton({ onAdd(name.trim(), earlier && hasExpenses) }, enabled = nameOk(name)) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ChoiceRow(selected: Boolean, onClick: () -> Unit, title: String, help: String) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

data class PaymentDraft(val from: String?, val to: String?, val amountText: String)

/** Records a payment in the base currency (rate 1). */
@Composable
fun PaymentDialog(
    members: List<MemberState>, base: String, initial: PaymentDraft,
    onDismiss: () -> Unit, onSave: (from: String, to: String, amountMinor: Long) -> Unit,
) {
    var from by remember { mutableStateOf(initial.from) }
    var to by remember { mutableStateOf(initial.to) }
    var amount by remember { mutableStateOf(initial.amountText) }
    val parsed = parseAmount(amount, CURRENCIES.getValue(base)) as? InputResult.Ok
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.record_payment)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown(stringResource(R.string.payment_from), members.firstOrNull { it.id == from }?.name.orEmpty(), members, { it.name }, { from = it.id }, Modifier.fillMaxWidth())
                Dropdown(stringResource(R.string.payment_to), members.firstOrNull { it.id == to }?.name.orEmpty(), members.filter { it.id != from }, { it.name }, { to = it.id }, Modifier.fillMaxWidth())
                OutlinedTextField(
                    amount, { amount = it }, label = { Text(stringResource(R.string.amount) + " (" + base + ")") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                { onSave(from!!, to!!, parsed!!.value) },
                enabled = from != null && to != null && from != to && parsed != null,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
