package com.olcayertas.tripsplit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olcayertas.tripsplit.R

/**
 * The first time a phone takes a member (PLAN §7.1): "From now on" is the default. "Also earlier expenses" puts the
 * member into the split of the earlier expenses.
 */
@Composable
fun FirstClaimDialog(memberName: String, onDismiss: () -> Unit, onConfirm: (includeEarlier: Boolean) -> Unit) {
    var earlier by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.claim_title, memberName)) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceRow(!earlier, { earlier = false }, stringResource(R.string.add_member_from_now), stringResource(R.string.add_member_from_now_help))
                ChoiceRow(earlier, { earlier = true }, stringResource(R.string.add_member_earlier), stringResource(R.string.add_member_earlier_help))
            }
        },
        confirmButton = { TextButton({ onConfirm(earlier) }, Modifier.testTag("claim_confirm")) { Text(stringResource(R.string.claim_confirm)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * A member that another phone holds. [fromEarlierId] is true when the holder is an earlier device id of this
 * install, which a restore leaves behind: the member was this user's.
 */
@Composable
fun TakeOverDialog(memberName: String, fromEarlierId: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.takeover_title, memberName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fromEarlierId) Text(stringResource(R.string.takeover_restore_note), Modifier.testTag("restore_note"))
                Text(stringResource(R.string.takeover_body), Modifier.testTag("takeover_body"))
            }
        },
        confirmButton = { TextButton(onConfirm, Modifier.testTag("takeover_confirm")) { Text(stringResource(R.string.takeover_confirm)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
