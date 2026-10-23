package com.olcayertas.tripsplit.core

import java.math.BigInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val MAX_AMOUNT_MINOR = 1_000_000_000_000L
const val MAX_BASE_MINOR = 1_000_000_000_000_000L

private val RATE_RE = Regex("[0-9]{1,9}(\\.[0-9]{1,8})?")

/** rate == mantissa / 10^scale */
data class Rate(val mantissa: Long, val scale: Int)

/** Throws [IllegalArgumentException] on bad syntax or zero. */
fun parseRate(rate: String): Rate {
    require(RATE_RE.matches(rate)) { "bad rate" }
    val whole = rate.substringBefore('.')
    val frac = if ('.' in rate) rate.substringAfter('.') else ""
    val mantissa = (whole + frac).toLong()
    require(mantissa != 0L) { "rate must be greater than zero" }
    return Rate(mantissa, frac.length)
}

private fun divRoundHalfEven(num: BigInteger, den: BigInteger): BigInteger {
    val qr = num.divideAndRemainder(den)
    var q = qr[0]
    val twice = qr[1].shiftLeft(1)
    val c = twice.compareTo(den)
    if (c > 0 || (c == 0 && q.testBit(0))) q = q.add(BigInteger.ONE)
    return q
}

/** Expense minor units to base minor units. Throws [IllegalArgumentException] when out of range. */
fun convert(amountMinor: Long, currency: String, rate: String, base: String): Long {
    val (mantissa, scale) = parseRate(rate)
    val shift = CURRENCIES.getValue(base) - CURRENCIES.getValue(currency) - scale
    val num = BigInteger.valueOf(amountMinor).multiply(BigInteger.valueOf(mantissa))
    val result = if (shift >= 0) {
        num.multiply(BigInteger.TEN.pow(shift))
    } else {
        divRoundHalfEven(num, BigInteger.TEN.pow(-shift))
    }
    require(result <= BigInteger.valueOf(MAX_BASE_MINOR)) { "converted amount out of range" }
    return result.toLong()
}

/** Largest-remainder allocation (SPEC §5.3). Ties: larger remainder first, then id bytes. */
fun allocate(total: Long, weights: List<Pair<String, Long>>): Map<String, Long> {
    var wSum = BigInteger.ZERO
    for ((_, w) in weights) wSum = wSum.add(BigInteger.valueOf(w))
    val shares = LinkedHashMap<String, Long>()
    val rems = ArrayList<Pair<String, BigInteger>>()
    for ((id, w) in weights) {
        val qr = BigInteger.valueOf(total).multiply(BigInteger.valueOf(w)).divideAndRemainder(wSum)
        shares[id] = qr[0].toLong()
        rems.add(id to qr[1])
    }
    val leftover = (total - shares.values.sum()).toInt()
    val order = rems.sortedWith { a, b ->
        val c = b.second.compareTo(a.second)
        if (c != 0) c else byteCompare(a.first, b.first)
    }
    for (i in 0 until leftover) {
        val id = order[i].first
        shares[id] = shares.getValue(id) + 1
    }
    return shares
}

fun splitShares(baseTotal: Long, amountMinor: Long, split: JsonObject): Map<String, Long> {
    return when (val mode = (split.getValue("mode") as JsonPrimitive).content) {
        "equal" -> allocate(baseTotal, (split.getValue("members") as JsonArray).map { (it as JsonPrimitive).content to 1L })
        "weights" -> allocate(baseTotal, longEntries(split.getValue("weights") as JsonObject))
        // Shares are in the expense currency. They act as weights over the base total.
        "exact" -> allocate(baseTotal, longEntries(split.getValue("shares") as JsonObject))
        else -> throw IllegalArgumentException(mode)
    }
}

private fun longEntries(obj: JsonObject): List<Pair<String, Long>> =
    obj.entries.map { (k, v) -> k to (v as JsonPrimitive).content.toLong() }
