@file:OptIn(ExperimentalMaterial3Api::class)

package com.olcayertas.tripsplit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.olcayertas.tripsplit.R
import com.olcayertas.tripsplit.core.ExpenseState
import com.olcayertas.tripsplit.core.FoldState
import com.olcayertas.tripsplit.core.HistoryEntry
import com.olcayertas.tripsplit.core.MemberState
import com.olcayertas.tripsplit.core.OpBodies
import com.olcayertas.tripsplit.core.buildHistory
import com.olcayertas.tripsplit.data.TripRepository
import com.olcayertas.tripsplit.data.identity.DeviceIdentity
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import java.text.DateFormat
import java.util.Date

@Composable
fun GroupScreen(
    repo: TripRepository,
    groupId: String,
    identity: DeviceIdentity,
    onBack: () -> Unit,
    onNearby: () -> Unit,
    onAddExpense: () -> Unit,
    onEditExpense: (String) -> Unit,
) {
    val state by repo.observeFold(groupId).collectAsState(initial = null)
    val ops by repo.observeOps(groupId).collectAsState(initial = emptyList())
    var tab by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val s = state
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s?.group?.name ?: "…") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = { IconButton(onNearby) { Icon(Icons.Filled.Wifi, stringResource(R.string.nearby_title)) } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    R.string.tab_expenses to Icons.AutoMirrored.Filled.ReceiptLong,
                    R.string.tab_balances to Icons.Filled.AccountBalance,
                    R.string.tab_members to Icons.Filled.Group,
                    R.string.tab_history to Icons.Filled.History,
                )
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, null) }, label = { Text(stringResource(label)) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 0 && s?.group != null) {
                ExtendedFloatingActionButton(
                    onClick = onAddExpense,
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.add_expense)) },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        if (s?.group == null) {
            EmptyState("…", "", modifier)
            return@Scaffold
        }
        when (tab) {
            0 -> ExpensesTab(s, modifier, onEditExpense)
            1 -> BalancesTab(s, repo, groupId, identity.deviceId, modifier)
            2 -> MembersTab(s, repo, groupId, identity.deviceId, modifier) { msg -> scope.launch { snack.showSnackbar(msg) } }
            else -> HistoryTab(buildHistory(ops), s, modifier)
        }
    }
}

private fun nameOf(s: FoldState, id: String) = s.members.firstOrNull { it.id == id }?.name ?: "?"

@Composable
private fun ExpensesTab(s: FoldState, modifier: Modifier, onEdit: (String) -> Unit) {
    val base = s.group!!.baseCurrency
    val list = s.expenses.filter { !it.deleted }.sortedWith(compareByDescending<ExpenseState> { it.date }.thenBy { it.id })
    if (list.isEmpty()) {
        EmptyState(stringResource(R.string.expenses_empty_title), stringResource(R.string.expenses_empty_body), modifier)
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.id }) { e ->
            val amount = e.money["amountMinor"]!!.jsonPrimitive.content.toLong()
            val cur = e.money["currency"]!!.jsonPrimitive.content
            Card(
                Modifier.fillMaxWidth().clickable { onEdit(e.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (e.needsAttention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(formatMoney(amount, cur), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        stringResource(R.string.paid_by_line, nameOf(s, e.paidBy), stringResource(categoryLabel(e.category))) + " · " + e.date,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (cur != base && e.baseAmountMinor != null) {
                        Text(formatMoney(e.baseAmountMinor!!, base), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (e.needsAttention) {
                        Text(stringResource(R.string.needs_attention), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.needs_attention_help), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun BalancesTab(s: FoldState, repo: TripRepository, groupId: String, deviceId: String, modifier: Modifier) {
    val base = s.group!!.baseCurrency
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<PaymentDraft?>(null) }
    val members = s.members.filter { !it.removed || (s.balances[it.id] ?: 0L) != 0L }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        items(members, key = { it.id }) { m ->
            val bal = s.balances[m.id] ?: 0L
            ListItem(
                headlineContent = { Text(m.name + if (m.claimedBy == deviceId) " " + stringResource(R.string.you_suffix) else "") },
                trailingContent = { Text(if (bal == 0L) stringResource(R.string.settled_up) else formatMoney(bal, base, signed = true), color = balanceColor(bal)) },
            )
        }
        item {
            Text(stringResource(R.string.suggested_payments), Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall)
        }
        if (s.suggestedPayments.isEmpty()) {
            item { Text(stringResource(R.string.no_payments_needed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(s.suggestedPayments.size) { i ->
            val p = s.suggestedPayments[i]
            ListItem(
                headlineContent = { Text(stringResource(R.string.pays_line, nameOf(s, p.from), nameOf(s, p.to))) },
                supportingContent = { Text(formatMoney(p.amountMinor, base)) },
                trailingContent = {
                    TextButton({ dialog = PaymentDraft(p.from, p.to, com.olcayertas.tripsplit.core.formatAmountPlain(p.amountMinor, com.olcayertas.tripsplit.core.CURRENCIES.getValue(base))) }) {
                        Text(stringResource(R.string.record_payment))
                    }
                },
            )
        }
        item {
            TextButton({ dialog = PaymentDraft(null, null, "") }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.record_payment)) }
        }
        val settlements = s.settlements.filter { !it.deleted }
        if (settlements.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.payments_recorded), Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall)
            }
            items(settlements, key = { it.id }) { st ->
                ListItem(
                    headlineContent = { Text(stringResource(R.string.payment_line, nameOf(s, st.fromMember), formatMoney(st.amountMinor, st.currency), nameOf(s, st.toMember))) },
                    supportingContent = { Text(st.date) },
                    trailingContent = {
                        TextButton({ scope.launch { repo.append(groupId, "settlement.delete", OpBodies.settlementDelete(st.id)) } }) { Text(stringResource(R.string.delete)) }
                    },
                )
            }
        }
    }
    dialog?.let { d ->
        PaymentDialog(
            members = s.members.filter { !it.removed },
            base = base,
            initial = d,
            onDismiss = { dialog = null },
            onSave = { from, to, minor ->
                dialog = null
                scope.launch {
                    repo.append(
                        groupId, "settlement.add",
                        OpBodies.settlementAdd(repo.device.newId(), from, to, minor, base, "1", java.time.LocalDate.now().toString()),
                    )
                }
            },
        )
    }
}

@Composable
private fun HistoryTab(history: List<HistoryEntry>, s: FoldState, modifier: Modifier) {
    if (history.isEmpty()) {
        EmptyState(stringResource(R.string.history_empty), "", modifier)
        return
    }
    val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    LazyColumn(modifier) {
        items(history.size) { i ->
            val h = history[i]
            val who = s.members.firstOrNull { it.claimedBy == h.author }?.name ?: stringResource(R.string.history_someone)
            ListItem(
                headlineContent = { Text("$who ${historyText(h)}") },
                supportingContent = { Text(df.format(Date(h.millis))) },
            )
        }
    }
}

@Composable
private fun historyText(h: HistoryEntry): String {
    val subject = h.subject ?: "?"
    val res = when (h.type) {
        "group.create" -> R.string.history_group_create
        "group.rename" -> R.string.history_group_rename
        "member.add" -> R.string.history_member_add
        "member.rename" -> R.string.history_member_rename
        "member.claim" -> R.string.history_member_claim
        "member.unclaim" -> R.string.history_member_unclaim
        "member.remove" -> R.string.history_member_remove
        "expense.add" -> R.string.history_expense_add
        "expense.edit" -> R.string.history_expense_edit
        "expense.delete" -> R.string.history_expense_delete
        "settlement.add" -> R.string.history_settlement_add
        else -> R.string.history_settlement_delete
    }
    return stringResource(res, subject)
}

@Composable
private fun MembersTab(
    s: FoldState, repo: TripRepository, groupId: String, deviceId: String, modifier: Modifier, notify: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<MemberState?>(null) }
    val hasExpenses = s.expenses.any { !it.deleted }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        items(s.members, key = { it.id }) { m ->
            ListItem(
                headlineContent = {
                    Text(
                        m.name + if (m.claimedBy == deviceId) " " + stringResource(R.string.you_suffix) else "",
                        textDecoration = if (m.removed) TextDecoration.LineThrough else null,
                    )
                },
                supportingContent = if (m.removed) ({ Text(stringResource(R.string.removed_member)) }) else null,
                trailingContent = {
                    if (!m.removed) TextButton({ renaming = m }) { Text(stringResource(R.string.rename)) }
                },
            )
        }
        item { TextButton({ adding = true }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.add_member)) } }
    }
    if (adding) {
        val context = androidx.compose.ui.platform.LocalContext.current
        AddMemberDialog(
            hasExpenses = hasExpenses,
            onDismiss = { adding = false },
            onAdd = { name, earlier ->
                adding = false
                scope.launch {
                    val r = repo.addMember(groupId, name, earlier)
                    if (r.untouchedExpenses.isNotEmpty()) notify(context.getString(R.string.untouched_exact, r.untouchedExpenses.size))
                }
            },
        )
    }
    renaming?.let { m ->
        NameDialog(
            title = stringResource(R.string.rename), initial = m.name,
            onDismiss = { renaming = null },
            onSave = { name -> renaming = null; scope.launch { repo.append(groupId, "member.rename", OpBodies.memberRename(m.id, name)) } },
        )
    }
}
