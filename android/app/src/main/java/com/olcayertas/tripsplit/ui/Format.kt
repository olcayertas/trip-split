package com.olcayertas.tripsplit.ui

import com.olcayertas.tripsplit.core.CURRENCIES
import java.text.NumberFormat
import java.util.Locale

/** "1,250.50 EUR" in the locale of the phone. With [signed] a plus or a minus sign comes first. */
fun formatMoney(minor: Long, currency: String, signed: Boolean = false, locale: Locale = Locale.getDefault()): String {
    val exponent = CURRENCIES[currency] ?: 2
    val nf = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = exponent
        maximumFractionDigits = exponent
    }
    val abs = java.math.BigDecimal.valueOf(kotlin.math.abs(minor), exponent)
    val sign = when {
        minor < 0 -> "−"
        signed && minor > 0 -> "+"
        else -> ""
    }
    return "$sign${nf.format(abs)} $currency"
}
