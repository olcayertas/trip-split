import Foundation
import TripSplitCore

/// Locale-aware amounts. The core does the integer work; this adds the separators of the user.
enum Fmt {
    static var decimal: Character { Locale.current.decimalSeparator?.first ?? "." }
    static var grouping: Character? { Locale.current.groupingSeparator?.first }

    static func amount(_ minor: Int64, _ currency: String) -> String {
        AmountFormat.format(minor, currency: currency, decimalSeparator: decimal, groupingSeparator: grouping)
    }

    static func signed(_ minor: Int64, _ currency: String) -> String {
        AmountFormat.signed(minor, currency: currency, decimalSeparator: decimal, groupingSeparator: grouping)
    }

    static func money(_ minor: Int64, _ currency: String) -> String { amount(minor, currency) + " " + currency }

    static func signedMoney(_ minor: Int64, _ currency: String) -> String { signed(minor, currency) + " " + currency }

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func dayString(_ date: Date) -> String { dayFormatter.string(from: date) }
    static func day(_ text: String) -> Date { dayFormatter.date(from: text) ?? Date() }

    static func displayDate(_ text: String) -> String {
        day(text).formatted(date: .abbreviated, time: .omitted)
    }

    static func currencyName(_ code: String) -> String {
        Locale.current.localizedString(forCurrencyCode: code) ?? code
    }
}

enum Category {
    static let all = ["food", "transport", "lodging", "activities", "shopping", "other"]

    static func symbol(_ id: String) -> String {
        switch id {
        case "food": "fork.knife"
        case "transport": "car.fill"
        case "lodging": "bed.double.fill"
        case "activities": "figure.hiking"
        case "shopping": "bag.fill"
        default: "ellipsis.circle.fill"
        }
    }

    static func title(_ id: String) -> String {
        switch id {
        case "food": String(localized: "Food")
        case "transport": String(localized: "Transport")
        case "lodging": String(localized: "Lodging")
        case "activities": String(localized: "Activities")
        case "shopping": String(localized: "Shopping")
        default: String(localized: "Other")
        }
    }
}

extension GroupState {
    func memberName(_ id: String) -> String { members.first { $0.id == id }?.name ?? String(localized: "Unknown member") }
    var activeMembers: [Member] { members.filter { !$0.removed } }
}
