@file:OptIn(ExperimentalMaterial3Api::class)

package com.olcayertas.tripsplit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.olcayertas.tripsplit.R
import com.olcayertas.tripsplit.core.OpBodies
import com.olcayertas.tripsplit.data.TripRepository
import kotlinx.coroutines.launch

private fun validName(s: String) = s.trim().let { it.isNotEmpty() && it.codePointCount(0, it.length) <= 60 }

@Composable
fun CreateGroupScreen(repo: TripRepository, myName: String, onBack: () -> Unit, onCreated: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(defaultCurrency()) }
    var me by remember { mutableStateOf(myName) }
    var next by remember { mutableStateOf("") }
    val others = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.create_group_title)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val caps = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.group_name)) }, singleLine = true, keyboardOptions = caps)
            CurrencyDropdown(stringResource(R.string.base_currency), currency, { currency = it }, Modifier.fillMaxWidth())
            OutlinedTextField(me, { me = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.your_member_name)) }, singleLine = true, keyboardOptions = caps)
            Text(stringResource(R.string.members_you_know), style = MaterialTheme.typography.titleSmall)
            others.forEachIndexed { i, n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n, Modifier.weight(1f))
                    IconButton({ others.removeAt(i) }) { Icon(Icons.Filled.Close, stringResource(R.string.delete)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(next, { next = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.member_name)) }, singleLine = true, keyboardOptions = caps)
                IconButton(
                    onClick = { others.add(next.trim()); next = "" },
                    enabled = validName(next),
                ) { Icon(Icons.Filled.Add, stringResource(R.string.add_member)) }
            }
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        val g = repo.createGroup(name.trim(), currency, me.trim())
                        for (n in others) repo.append(g.groupId, "member.add", OpBodies.memberAdd(repo.device.newId(), n))
                        onCreated(g.groupId)
                    }
                },
                enabled = !busy && validName(name) && validName(me),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.create)) }
            TextButton(onBack, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
}
