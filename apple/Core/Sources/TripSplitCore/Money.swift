// Currencies, rates, conversion, allocation and settlement (SPEC §5, §6).
// Integer arithmetic only. Int128 holds amountMinor × mantissa (< 10^30).

import CryptoKit

/// ISO 4217 code → minor-unit exponent. Must equal protocol/vectors/currencies.json.
public let currencies: [String: Int] = [
    "AED": 2, "ARS": 2, "AUD": 2, "BGN": 2, "BHD": 3, "BRL": 2, "CAD": 2, "CHF": 2, "CLP": 0,
    "CNY": 2, "COP": 2, "CZK": 2, "DKK": 2, "EGP": 2, "EUR": 2, "GBP": 2, "GEL": 2, "HKD": 2,
    "HUF": 2, "IDR": 2, "ILS": 2, "INR": 2, "ISK": 0, "JOD": 3, "JPY": 0, "KRW": 0, "KWD": 3,
    "MAD": 2, "MXN": 2, "MYR": 2, "NOK": 2, "NZD": 2, "OMR": 3, "PHP": 2, "PLN": 2, "QAR": 2,
    "RON": 2, "RSD": 2, "RUB": 2, "SAR": 2, "SEK": 2, "SGD": 2, "THB": 2, "TND": 3, "TRY": 2,
    "TWD": 2, "UAH": 2, "USD": 2, "VND": 0, "ZAR": 2,
]

public let maxAmountMinor: Int64 = 1_000_000_000_000
public let maxBaseMinor: Int64 = 1_000_000_000_000_000

public enum MoneyError: Error, Equatable {
    case badRate
    case outOfRange
}

/// `"34.75"` → `(3475, 2)`. Syntax `^[0-9]{1,9}(\.[0-9]{1,8})?$`, value > 0.
public func parseRate(_ rate: String) throws -> (mantissa: Int64, scale: Int) {
    let parts = rate.split(separator: ".", maxSplits: 2, omittingEmptySubsequences: false)
    guard parts.count <= 2 else { throw MoneyError.badRate }
    let whole = parts[0]
    let frac = parts.count == 2 ? parts[1] : ""
    let isDigits: (Substring) -> Bool = { $0.utf8.allSatisfy { (0x30...0x39).contains($0) } }
    guard (1...9).contains(whole.utf8.count), isDigits(whole) else { throw MoneyError.badRate }
    if parts.count == 2 {
        guard (1...8).contains(frac.utf8.count), isDigits(frac) else { throw MoneyError.badRate }
    }
    guard let mantissa = Int64(whole + frac), mantissa > 0 else { throw MoneyError.badRate }
    return (mantissa, frac.utf8.count)
}

private func pow10(_ n: Int) -> Int128 {
    var r: Int128 = 1
    for _ in 0..<n { r *= 10 }
    return r
}

private func divRoundHalfEven(_ num: Int128, _ den: Int128) -> Int128 {
    var q = num / den
    let twice = 2 * (num % den)
    if twice > den || (twice == den && q % 2 == 1) { q += 1 }
    return q
}

/// Expense minor units → base minor units, round half even.
public func convert(amountMinor: Int64, currency: String, rate: String, base: String) throws -> Int64 {
    guard let baseExp = currencies[base], let curExp = currencies[currency] else { throw MoneyError.outOfRange }
    let (mantissa, scale) = try parseRate(rate)
    let shift = baseExp - curExp - scale
    let num = Int128(amountMinor) * Int128(mantissa)
    let result = shift >= 0 ? num * pow10(shift) : divRoundHalfEven(num, pow10(-shift))
    guard result <= Int128(maxBaseMinor) else { throw MoneyError.outOfRange }
    return Int64(result)
}

/// Largest-remainder allocation. Ties: larger remainder first, then id bytes.
public func allocate(_ total: Int64, _ weights: [(String, Int64)]) -> [String: Int64] {
    let wSum = Int128(weights.reduce(0) { $0 + $1.1 })
    var shares: [String: Int64] = [:]
    var rems: [(rem: Int128, id: String)] = []
    for (id, w) in weights {
        let p = Int128(total) * Int128(w)
        shares[id] = Int64(p / wSum)
        rems.append((p % wSum, id))
    }
    let leftover = Int(total - shares.values.reduce(0, +))
    rems.sort { $0.rem != $1.rem ? $0.rem > $1.rem : byteLess($0.id, $1.id) }
    for k in 0..<leftover { shares[rems[k].id]! += 1 }
    return shares
}

public func splitShares(baseTotal: Int64, split: JSONValue) -> [String: Int64] {
    switch split["mode"]?.stringValue {
    case "equal":
        return allocate(baseTotal, (split["members"]?.arrayValue ?? []).compactMap { $0.stringValue }.map { ($0, 1) })
    case "weights":
        return allocate(baseTotal, (split["weights"]?.objectValue ?? [:]).map { ($0.key, $0.value.intValue ?? 0) })
    default:
        return allocate(baseTotal, (split["shares"]?.objectValue ?? [:]).map { ($0.key, $0.value.intValue ?? 0) })
    }
}

public struct Payment: Equatable, Sendable {
    public let from: String
    public let to: String
    public let amountMinor: Int64
}

/// Greedy settlement: the largest debtor pays the largest creditor; ties by id bytes.
public func settle(_ balances: [String: Int64]) -> [Payment] {
    var bal = balances
    var out: [Payment] = []
    func pick(_ sign: Int64) -> String? {
        bal.filter { $0.value * sign > 0 }
            .min { a, b in abs(a.value) != abs(b.value) ? abs(a.value) > abs(b.value) : byteLess(a.key, b.key) }?
            .key
    }
    while let d = pick(-1), let c = pick(1) {
        let amount = min(-bal[d]!, bal[c]!)
        out.append(Payment(from: d, to: c, amountMinor: amount))
        bal[d]! += amount
        bal[c]! -= amount
    }
    return out
}

/// Lowercase hex SHA-256 of the canonical op without its `hash` key (SPEC §4.2).
public func opHash(_ op: JSONValue) -> String {
    var o = op.objectValue ?? [:]
    o["hash"] = nil
    let digest = SHA256.hash(data: JSONValue.object(o).canonicalBytes)
    return digest.map { b in (b < 16 ? "0" : "") + String(b, radix: 16) }.joined()
}
