@file:OptIn(ExperimentalMaterial3Api::class)

package com.olcayertas.tripsplit.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.olcayertas.tripsplit.core.CURRENCIES
import com.olcayertas.tripsplit.ui.theme.LocalExtendedColors

/** A text field that opens a menu of options. */
@Composable
fun <T> Dropdown(
    label: String,
    selectedText: String,
    options: List<T>,
    optionText: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in options) {
                DropdownMenuItem(text = { Text(optionText(o)) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}

@Composable
fun CurrencyDropdown(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Dropdown(label, value, CURRENCIES.keys.toList(), { it }, onChange, modifier)
}

/** Positive balances use the "positive" role, negative ones the error role. The sign is always in the text. */
@Composable
fun balanceColor(minor: Long): Color = when {
    minor > 0 -> LocalExtendedColors.current.positive
    minor < 0 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun defaultCurrency(): String {
    val code = try {
        java.util.Currency.getInstance(java.util.Locale.getDefault()).currencyCode
    } catch (_: Exception) {
        "EUR"
    }
    return if (code in CURRENCIES) code else "EUR"
}
