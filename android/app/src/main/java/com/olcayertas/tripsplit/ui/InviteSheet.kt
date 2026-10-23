@file:OptIn(ExperimentalMaterial3Api::class)

package com.olcayertas.tripsplit.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olcayertas.tripsplit.R

/**
 * The invite (PLAN §7): a QR code is the main path. The link is a fallback. The share sheet shows a warning first,
 * and the app never copies the link to the clipboard.
 */
@Composable
fun InviteSheet(invite: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(invite) { qrBitmap(invite).asImageBitmap() }
    var warning by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.invite_title), style = MaterialTheme.typography.titleLarge)
            Image(bitmap, stringResource(R.string.invite_qr_description), Modifier.size(280.dp).testTag("qr"))
            Text(stringResource(R.string.invite_scan_help), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton({ warning = true }, Modifier.fillMaxWidth().testTag("share")) { Text(stringResource(R.string.invite_share_link)) }
            Button(onDismiss, Modifier.fillMaxWidth()) { Text(stringResource(R.string.ok)) }
        }
    }
    if (warning) {
        AlertDialog(
            onDismissRequest = { warning = false },
            title = { Text(stringResource(R.string.invite_warning_title)) },
            text = { Text(stringResource(R.string.invite_warning), Modifier.testTag("warning")) },
            confirmButton = {
                TextButton({
                    warning = false
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, invite)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.invite_share_anyway)) }
            },
            dismissButton = { TextButton({ warning = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
