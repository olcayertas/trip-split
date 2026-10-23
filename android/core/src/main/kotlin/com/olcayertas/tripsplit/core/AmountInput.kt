package com.olcayertas.tripsplit.core

/** Why a typed amount or rate was refused. The UI maps each one to a message. */
enum class InputError { EMPTY, SYNTAX, TOO_MANY_DECIMALS, OUT_OF_RANGE }

sealed interface InputResult<out T> {
    data class Ok<T>(val value: T) : InputResult<T>
    data class Bad(val error: InputError) : InputResult<Nothing>
}

private val SPACES = Regex("[\\s\\u00a0\\u202f]")

/**
 * Reads a typed amount into minor units. Accepts "," and "." as the decimal separator (Turkish keyboards
 * type ","). With both marks, the last one is the decimal mark and the other one groups thousands. A
 * mark that repeats is a grouping mark. A single mark is a decimal mark, unless the currency has no minor
 * units and exactly three digits follow it ("1.000" is one thousand yen). More decimals than the
 * currency allows is an error, never a silent rounding. With [allowZero] an empty text and zero are valid
 * (an exact share can be zero).
 */
fun parseAmount(text: String, exponent: Int, allowZero: Boolean = false): InputResult<Long> {
    val s = text.replace(SPACES, "")
    if (s.isEmpty()) return if (allowZero) InputResult.Ok(0L) else InputResult.Bad(InputError.EMPTY)
    if (!s.all { it.isDigit() && it.code < 128 || it == '.' || it == ',' }) return InputResult.Bad(InputError.SYNTAX)
    val lastDot = s.lastIndexOf('.')
    val lastComma = s.lastIndexOf(',')
    val decimalAt = when {
        lastDot < 0 && lastComma < 0 -> -1
        lastDot >= 0 && lastComma >= 0 -> maxOf(lastDot, lastComma)
        else -> {
            val mark = if (lastDot >= 0) '.' else ','
            val last = s.lastIndexOf(mark)
            val repeats = s.count { it == mark } > 1
            val after = s.length - last - 1
            when {
                repeats -> -1
                exponent == 0 && after == 3 -> -1
                else -> last
            }
        }
    }
    val whole = (if (decimalAt < 0) s else s.substring(0, decimalAt)).filter { it.isDigit() }
    val frac = if (decimalAt < 0) "" else s.substring(decimalAt + 1)
    if (decimalAt >= 0 && !frac.all { it.isDigit() }) return InputResult.Bad(InputError.SYNTAX)
    if (decimalAt >= 0 && s.substring(0, decimalAt).any { it != '.' && it != ',' && !it.isDigit() }) {
        return InputResult.Bad(InputError.SYNTAX)
    }
    if (whole.isEmpty() && frac.isEmpty()) return InputResult.Bad(InputError.SYNTAX)
    if (frac.length > exponent) return InputResult.Bad(InputError.TOO_MANY_DECIMALS)
    if (whole.length > 13) return InputResult.Bad(InputError.OUT_OF_RANGE)
    val minor = ((whole.ifEmpty { "0" }) + frac.padEnd(exponent, '0')).toLong()
    return if (minor in (if (allowZero) 0L else 1L)..MAX_AMOUNT_MINOR) InputResult.Ok(minor) else InputResult.Bad(InputError.OUT_OF_RANGE)
}

/** Digits with "." as the mark, no grouping: 1250 with exponent 2 gives "12.50". */
fun formatAmountPlain(minor: Long, exponent: Int): String {
    val neg = minor < 0
    val abs = if (neg) -minor else minor
    val digits = abs.toString().padStart(exponent + 1, '0')
    val body = if (exponent == 0) digits else digits.dropLast(exponent) + "." + digits.takeLast(exponent)
    return if (neg) "-$body" else body
}

/** A typed exchange rate as the canonical string of SPEC §5.1 ("34,75" gives "34.75"). */
fun parseRateInput(text: String): InputResult<String> {
    val s = text.replace(SPACES, "").replace(',', '.')
    if (s.isEmpty()) return InputResult.Bad(InputError.EMPTY)
    if (!Regex("[0-9]+(\\.[0-9]*)?|\\.[0-9]+").matches(s)) return InputResult.Bad(InputError.SYNTAX)
    var whole = s.substringBefore('.').trimStart('0')
    val frac = (if ('.' in s) s.substringAfter('.') else "").trimEnd('0')
    if (whole.isEmpty()) whole = "0"
    if (whole.length > 9 || frac.length > 8) return InputResult.Bad(InputError.OUT_OF_RANGE)
    val out = if (frac.isEmpty()) whole else "$whole.$frac"
    return try {
        parseRate(out)
        InputResult.Ok(out)
    } catch (_: IllegalArgumentException) {
        InputResult.Bad(InputError.OUT_OF_RANGE)
    }
}
