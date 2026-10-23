@file:OptIn(ExperimentalMaterial3Api::class)

package com.olcayertas.tripsplit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olcayertas.tripsplit.R
import com.olcayertas.tripsplit.data.TripRepository

@Composable
fun GroupsScreen(repo: TripRepository, deviceId: String, onCreate: () -> Unit, onJoin: () -> Unit, onOpen: (String) -> Unit) {
    val ids by repo.observeGroupIds().collectAsState(initial = emptyList())
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.groups_title)) },
                actions = { androidx.compose.material3.TextButton(onJoin) { Text(stringResource(R.string.join_group)) } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.create_group)) },
            )
        },
    ) { padding ->
        if (ids.isEmpty()) {
            EmptyState(stringResource(R.string.groups_empty_title), stringResource(R.string.groups_empty_body), Modifier.padding(padding))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ids, key = { it }) { id -> GroupCard(repo, id, deviceId) { onOpen(id) } }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GroupCard(repo: TripRepository, groupId: String, deviceId: String, onClick: () -> Unit) {
    val state by repo.observeFold(groupId).collectAsState(initial = null)
    val s = state
    val me = s?.members?.firstOrNull { it.claimedBy == deviceId }
    val base = s?.group?.baseCurrency
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s?.group?.name ?: "…", style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.your_balance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s != null && base != null) {
                if (me == null) {
                    Text(stringResource(R.string.not_claimed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val bal = s.balances[me.id] ?: 0L
                    Text(
                        if (bal == 0L) stringResource(R.string.settled_up) else formatMoney(bal, base, signed = true),
                        style = MaterialTheme.typography.titleMedium,
                        color = balanceColor(bal),
                    )
                }
            }
        }
    }
}
