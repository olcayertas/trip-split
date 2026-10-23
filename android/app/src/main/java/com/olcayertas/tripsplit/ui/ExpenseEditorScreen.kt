@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.olcayertas.tripsplit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.olcayertas.tripsplit.R
import com.olcayertas.tripsplit.core.CURRENCIES
import com.olcayertas.tripsplit.core.DraftError
import com.olcayertas.tripsplit.core.DraftResult
import com.olcayertas.tripsplit.core.EXPENSE_CATEGORIES
import com.olcayertas.tripsplit.core.ExpenseDraft
import com.olcayertas.tripsplit.core.ExpenseValues
import com.olcayertas.tripsplit.core.InputResult
import com.olcayertas.tripsplit.core.MemberState
import com.olcayertas.tripsplit.core.OpBodies
import com.olcayertas.tripsplit.core.SplitMode
import com.olcayertas.tripsplit.core.convert
import com.olcayertas.tripsplit.core.editFields
import com.olcayertas.tripsplit.core.parseAmount
import com.olcayertas.tripsplit.core.parseRateInput
import com.olcayertas.tripsplit.data.TripRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset

/** Loads the group, then shows the editor for a new expense or for [expenseId]. */
@Composable
fun ExpenseRoute(repo: TripRepository, groupId: String, expenseId: String?, onDone: () -> Unit) {
    val state by repo.observeFold(groupId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val s = state ?: return
    val base = s.group?.baseCurrency ?: return
    val existing = expenseId?.let { id -> s.expenses.firstOrNull { it.id == id } }
    val members = s.members.filter { !it.removed || (existing != null && it.id in ExpenseDraft.from(existing, base).selected) }
    val me = s.members.firstOrNull { it.claimedBy == repo.device.deviceId }?.id
    val initial = remember(existing?.id) {
        existing?.let { ExpenseDraft.from(it, base) }
            ?: ExpenseDraft(baseCurrency = base, paidBy = me, selected = members.map { it.id }.toSet())
    }
    ExpenseEditorScreen(
        members = members, initial = initial, editing = existing != null,
        onBack = onDone,
        onSave = { values ->
            scope.launch {
                if (existing == null) {
                    repo.append(
                        groupId, "expense.add",
                        OpBodies.expenseAdd(repo.device.newId(), values.title, values.date, values.category, values.paidBy, values.money),
                    )
                } else {
                    val fields = editFields(existing, values)
                    if (fields.isNotEmpty()) repo.append(groupId, "expense.edit", OpBodies.expenseEdit(existing.id, fields))
                }
                onDone()
            }
        },
        onDelete = existing?.let { e -> { scope.launch { repo.append(groupId, "expense.delete", OpBodies.expenseDelete(e.id)); onDone() } } },
    )
}

@Composable
fun ExpenseEditorScreen(
    members: List<MemberState>,
    initial: ExpenseDraft,
    editing: Boolean,
    onBack: () -> Unit,
    onSave: (ExpenseValues) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var draft by remember { mutableStateOf(initial) }
    var errors by remember { mutableStateOf<Set<DraftError>>(emptySet()) }
    var datePicker by remember { mutableStateOf(false) }
    val exponent = CURRENCIES[draft.currency] ?: 2
    fun err(e: DraftError) = e in errors
    val names = members.associate { it.id to it.name }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (editing) R.string.edit_expense else R.string.add_expense)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                draft.title, { draft = draft.copy(title = it) }, Modifier.fillMaxWidth().testTag("title"),
                label = { Text(stringResource(R.string.expense_title)) }, singleLine = true, isError = err(DraftError.TITLE),
                supportingText = if (err(DraftError.TITLE)) ({ Text(stringResource(R.string.error_title)) }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    draft.amountText, { draft = draft.copy(amountText = it) }, Modifier.weight(1f).testTag("amount"),
                    label = { Text(stringResource(R.string.amount)) }, singleLine = true, isError = err(DraftError.AMOUNT),
                    supportingText = if (err(DraftError.AMOUNT)) ({ Text(stringResource(R.string.error_amount)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                CurrencyDropdown(stringResource(R.string.currency), draft.currency, { draft = draft.copy(currency = it) }, Modifier.weight(0.7f))
            }
            if (draft.foreign) {
                OutlinedTextField(
                    draft.rateText, { draft = draft.copy(rateText = it) }, Modifier.fillMaxWidth().testTag("rate"),
                    label = { Text(stringResource(R.string.rate_label, draft.currency, draft.baseCurrency)) }, singleLine = true,
                    isError = err(DraftError.RATE),
                    supportingText = {
                        Text(if (err(DraftError.RATE)) stringResource(R.string.error_rate) else preview(draft, exponent))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            Dropdown(
                stringResource(R.string.paid_by), names[draft.paidBy].orEmpty(), members, { it.name },
                { draft = draft.copy(paidBy = it.id) }, Modifier.fillMaxWidth().testTag("paidBy"),
            )
            if (err(DraftError.PAID_BY)) ErrorText(R.string.error_paid_by)

            Text(stringResource(R.string.split), style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = listOf(SplitMode.EQUAL to R.string.split_equal, SplitMode.WEIGHTS to R.string.split_weights, SplitMode.EXACT to R.string.split_exact)
                modes.forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = draft.mode == m, onClick = { draft = draft.copy(mode = m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(stringResource(label)) }
                }
            }
            Text(stringResource(R.string.split_between), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in members) {
                    FilterChip(
                        selected = m.id in draft.selected,
                        onClick = {
                            draft = draft.copy(selected = if (m.id in draft.selected) draft.selected - m.id else draft.selected + m.id)
                        },
                        label = { Text(m.name) },
                    )
                }
            }
            if (err(DraftError.SPLIT_EMPTY)) ErrorText(R.string.error_split_empty)
            if (draft.mode != SplitMode.EQUAL) {
                for (m in members.filter { it.id in draft.selected }) {
                    val weights = draft.mode == SplitMode.WEIGHTS
                    OutlinedTextField(
                        value = (if (weights) draft.weightText else draft.exactText)[m.id].orEmpty(),
                        onValueChange = {
                            draft = if (weights) draft.copy(weightText = draft.weightText + (m.id to it))
                            else draft.copy(exactText = draft.exactText + (m.id to it))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(m.name + " · " + stringResource(if (weights) R.string.weight else R.string.share)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if (weights) KeyboardType.Number else KeyboardType.Decimal),
                    )
                }
                if (draft.mode == SplitMode.WEIGHTS && err(DraftError.WEIGHT)) ErrorText(R.string.error_weight)
                if (draft.mode == SplitMode.EXACT) {
                    if (err(DraftError.EXACT_AMOUNT)) ErrorText(R.string.error_exact_amount)
                    if (err(DraftError.EXACT_SUM)) ErrorText(R.string.error_exact_sum)
                    remaining(draft, exponent)?.let { Text(stringResource(R.string.exact_remaining, it), style = MaterialTheme.typography.bodySmall) }
                }
            }

            OutlinedButton({ datePicker = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.date) + ": " + draft.date) }
            if (err(DraftError.DATE)) ErrorText(R.string.error_date)
            Dropdown(
                stringResource(R.string.category), stringResource(categoryLabel(draft.category)), EXPENSE_CATEGORIES,
                { stringResource(categoryLabel(it)) }, { draft = draft.copy(category = it) }, Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    when (val r = draft.validate()) {
                        is DraftResult.Ok -> onSave(r.values)
                        is DraftResult.Invalid -> errors = r.errors
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("save"),
            ) { Text(stringResource(R.string.save)) }
            if (onDelete != null) {
                TextButton(onDelete, Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (datePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = runCatching { java.time.LocalDate.parse(draft.date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull(),
        )
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { draft = draft.copy(date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    datePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton({ datePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun ErrorText(res: Int) =
    Text(stringResource(res), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

private fun preview(d: ExpenseDraft, exponent: Int): String {
    val amount = (parseAmount(d.amountText, exponent) as? InputResult.Ok)?.value ?: return ""
    val rate = (parseRateInput(d.rateText) as? InputResult.Ok)?.value ?: return ""
    return try {
        "≈ " + formatMoney(convert(amount, d.currency, rate, d.baseCurrency), d.baseCurrency)
    } catch (_: IllegalArgumentException) {
        ""
    }
}

private fun remaining(d: ExpenseDraft, exponent: Int): String? {
    val total = (parseAmount(d.amountText, exponent) as? InputResult.Ok)?.value ?: return null
    val sum = d.selected.sumOf { (parseAmount(d.exactText[it].orEmpty(), exponent, allowZero = true) as? InputResult.Ok)?.value ?: 0L }
    return formatMoney(total - sum, d.currency)
}
