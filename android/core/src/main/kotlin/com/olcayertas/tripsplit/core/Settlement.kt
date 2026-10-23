package com.olcayertas.tripsplit.core

data class Payment(val from: String, val to: String, val amountMinor: Long)

/** Greedy settlement (SPEC §6). Largest debtor pays largest creditor. Ties: id bytes. */
fun settle(balances: Map<String, Long>): List<Payment> {
    val bal = LinkedHashMap(balances)
    val out = ArrayList<Payment>()
    while (true) {
        val debtor = bal.entries.filter { it.value < 0 }
            .minWithOrNull { a, b -> if (a.value != b.value) a.value.compareTo(b.value) else byteCompare(a.key, b.key) }
            ?.key
        val creditor = bal.entries.filter { it.value > 0 }
            .minWithOrNull { a, b -> if (a.value != b.value) b.value.compareTo(a.value) else byteCompare(a.key, b.key) }
            ?.key
        if (debtor == null || creditor == null) break
        val amount = minOf(-bal.getValue(debtor), bal.getValue(creditor))
        out.add(Payment(debtor, creditor, amount))
        bal[debtor] = bal.getValue(debtor) + amount
        bal[creditor] = bal.getValue(creditor) - amount
    }
    return out
}
