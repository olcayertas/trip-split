@file:OptIn(ExperimentalMaterial3Api::class)

package com.olcayertas.tripsplit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olcayertas.tripsplit.R
import com.olcayertas.tripsplit.core.Invite
import com.olcayertas.tripsplit.core.parseInvite
import com.olcayertas.tripsplit.data.TripRepository
import kotlinx.coroutines.launch

/** What the join screen tells the user after a link or a QR code. */
sealed interface JoinResult {
    data object None : JoinResult
    data object Invalid : JoinResult
    data class Joined(val groupId: String, val already: Boolean) : JoinResult
}

/**
 * Join a group (PLAN §7): scan the QR code or paste the link. The device stores the group id and key. The first sync
 * with any member phone downloads the log, so the screen sends the user to Nearby.
 */
@Composable
fun JoinScreen(repo: TripRepository, initialLink: String?, onBack: () -> Unit, onOpenNearby: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf(initialLink.orEmpty()) }
    var result by remember { mutableStateOf<JoinResult>(JoinResult.None) }
    val scope = rememberCoroutineScope()

    fun join(link: String) {
        val invite: Invite? = parseInvite(link.trim())
        if (invite == null) {
            result = JoinResult.Invalid
            return
        }
        scope.launch {
            val already = repo.device.store.group(invite.groupId) != null
            repo.join(invite)
            result = JoinResult.Joined(invite.groupId, already)
        }
    }
    LaunchedEffect(initialLink) { if (!initialLink.isNullOrBlank()) join(initialLink) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.join_title)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val r = result
            if (r is JoinResult.Joined) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (r.already) R.string.join_already else R.string.join_done), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.join_next), style = MaterialTheme.typography.bodyMedium)
                    Button(onOpenNearby, Modifier.fillMaxWidth().testTag("open_nearby")) { Text(stringResource(R.string.join_open_nearby)) }
                }
                return@Column
            }
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.join_scan)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.join_paste)) })
            }
            if (r is JoinResult.Invalid) {
                Text(stringResource(R.string.join_invalid), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            }
            if (tab == 0) {
                Box(Modifier.fillMaxWidth().height(420.dp)) {
                    QrScanner(onCode = { code -> if (result !is JoinResult.Joined) join(code) })
                }
            } else {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        text, { text = it; result = JoinResult.None }, Modifier.fillMaxWidth().testTag("link"),
                        label = { Text(stringResource(R.string.join_link)) }, minLines = 3,
                    )
                    Button({ join(text) }, Modifier.fillMaxWidth().testTag("join"), enabled = text.isNotBlank()) { Text(stringResource(R.string.join_action)) }
                }
            }
        }
    }
}
