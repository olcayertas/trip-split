import Foundation
import TripSplitCore
import TripSplitStore

/// Sample group for screenshots (`-seed-demo`). Not used in normal runs.
@MainActor
enum DemoData {
    /// A fixed invite for the live LAN test between a simulator and a second process (`-demo-fixed`).
    static let fixedInvite = Invite(groupId: "00000000-0000-4000-8000-0000000000aa", groupKey: (0..<32).map { UInt8($0) })

    static func seed(_ repo: GroupRepository, fixed: Bool = false) async {
        guard repo.groups.isEmpty else { return }
        try? repo.setDisplayName("Ali")
        let id: String
        if fixed {
            try? await repo.join(fixedInvite)
            id = fixedInvite.groupId
            _ = try? await repo.append(groupId: id, type: "group.create", body: .object([
                "name": .string("Kapadokya 2026"), "baseCurrency": .string("EUR"),
                "creatorMemberId": .string(UUID().uuidString.lowercased()), "creatorName": .string("Ali"),
            ]))
        } else {
            guard let created = try? await repo.createGroup(name: "Kapadokya 2026", baseCurrency: "EUR", creatorName: "Ali") else { return }
            id = created
        }
        let ali = repo.state(of: id).members[0].id
        var ids: [String] = [ali]
        for name in ["Ayşe", "Mehmet", "Zeynep"] {
            let m = UUID().uuidString.lowercased()
            ids.append(m)
            _ = try? await repo.append(groupId: id, type: "member.add", body: .object(["memberId": .string(m), "name": .string(name)]))
        }
        func expense(_ title: String, _ cat: String, _ payer: Int, _ amount: Int64, _ currency: String = "EUR", rate: String = "1") async {
            let body: JSONValue = .object([
                "expenseId": .string(UUID().uuidString.lowercased()), "title": .string(title), "date": .string("2026-10-20"),
                "category": .string(cat), "paidBy": .string(ids[payer]),
                "money": .object([
                    "amountMinor": .int(amount), "currency": .string(currency), "rate": .string(rate),
                    "split": .object(["mode": .string("equal"), "members": .array(ids.map { .string($0) })]),
                ]),
            ])
            _ = try? await repo.append(groupId: id, type: "expense.add", body: body)
        }
        await expense("Cave hotel", "lodging", 0, 48000)
        await expense("Dinner in Göreme", "food", 1, 9650)
        await expense("Balloon ride", "activities", 2, 32000)
        await expense("Taxi", "transport", 3, 120000, "TRY", rate: "0.0235")
        await expense("Breakfast", "food", 1, 3840)
    }
}
