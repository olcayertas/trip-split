package com.olcayertas.tripsplit.ui

import androidx.annotation.StringRes
import com.olcayertas.tripsplit.R
import com.olcayertas.tripsplit.core.DraftError

@StringRes
fun categoryLabel(category: String): Int = when (category) {
    "food" -> R.string.category_food
    "transport" -> R.string.category_transport
    "lodging" -> R.string.category_lodging
    "activities" -> R.string.category_activities
    "shopping" -> R.string.category_shopping
    else -> R.string.category_other
}

@StringRes
fun errorLabel(e: DraftError): Int = when (e) {
    DraftError.TITLE -> R.string.error_title
    DraftError.AMOUNT -> R.string.error_amount
    DraftError.RATE -> R.string.error_rate
    DraftError.DATE -> R.string.error_date
    DraftError.PAID_BY -> R.string.error_paid_by
    DraftError.SPLIT_EMPTY -> R.string.error_split_empty
    DraftError.WEIGHT -> R.string.error_weight
    DraftError.EXACT_AMOUNT -> R.string.error_exact_amount
    DraftError.EXACT_SUM -> R.string.error_exact_sum
}
