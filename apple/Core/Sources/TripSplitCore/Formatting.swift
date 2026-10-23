// Amount text: parse what the user types, format what the app shows. Integer arithmetic only.

public enum AmountFormat {
    public static let minus: Character = "\u{2212}"

    /// Parses "12", "12.5", "12,50". One separator (`.` or `,`) at most. More fraction digits than the
    /// currency allows gives nil. Gives nil for an empty text, a sign or a letter.
    public static func parse(_ text: String, currency: String) -> Int64? {
        guard let exponent = currencies[currency] else { return nil }
        let trimmed = text.trimmingSpaces()
        guard !trimmed.isEmpty else { return nil }
        var whole = "", fraction = ""
        var seenSeparator = false
        for c in trimmed {
            if c == "." || c == "," {
                if seenSeparator { return nil }
                seenSeparator = true
            } else if let d = c.wholeNumberValue, c.isASCII {
                if seenSeparator { fraction.append(c) } else { whole.append(c) }
                _ = d
            } else {
                return nil
            }
        }
        guard fraction.count <= exponent, !(whole.isEmpty && fraction.isEmpty) else { return nil }
        let padded = fraction + String(repeating: "0", count: exponent - fraction.count)
        guard whole.count + padded.count <= 15, let n = Int64((whole.isEmpty ? "0" : whole) + padded) else { return nil }
        return n
    }

    /// "1234" → "12,34" for EUR with separator ",". No grouping, no sign for a positive value.
    public static func format(_ minor: Int64, currency: String, decimalSeparator: Character = ".",
                              groupingSeparator: Character? = nil) -> String {
        let exponent = currencies[currency] ?? 2
        let magnitude = minor.magnitude
        let digits = String(magnitude)
        let padded = String(repeating: "0", count: max(0, exponent + 1 - digits.count)) + digits
        var whole = String(padded.dropLast(exponent))
        let fraction = String(padded.suffix(exponent))
        if let g = groupingSeparator {
            var out = ""
            for (i, c) in whole.reversed().enumerated() {
                if i > 0, i % 3 == 0 { out.append(g) }
                out.append(c)
            }
            whole = String(out.reversed())
        }
        let body = exponent == 0 ? whole : whole + String(decimalSeparator) + fraction
        return minor < 0 ? String(minus) + body : body
    }

    /// With an explicit "+" or "−" (never colour alone). Zero has no sign.
    public static func signed(_ minor: Int64, currency: String, decimalSeparator: Character = ".",
                              groupingSeparator: Character? = nil) -> String {
        let body = format(abs(minor), currency: currency, decimalSeparator: decimalSeparator, groupingSeparator: groupingSeparator)
        if minor > 0 { return "+" + body }
        if minor < 0 { return String(minus) + body }
        return body
    }
}

extension String {
    func trimmingSpaces() -> String {
        var s = Substring(self)
        while let f = s.first, f == " " { s = s.dropFirst() }
        while let l = s.last, l == " " { s = s.dropLast() }
        return String(s)
    }
}
