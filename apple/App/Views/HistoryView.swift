import SwiftUI
import TripSplitCore
import TripSplitStore

struct HistoryView: View {
    let repository: GroupRepository
    let groupId: String
    @State private var entries: [HistoryEntry] = []

    private var state: GroupState { repository.state(of: groupId) }

    var body: some View {
        List(entries) { entry in
            VStack(alignment: .leading, spacing: M3Spacing.xxs) {
                Text(sentence(entry)).m3(M3Type.bodyLarge).foregroundStyle(M3Color.onSurface)
                Text(Date(timeIntervalSince1970: Double(entry.millis) / 1000).formatted(date: .abbreviated, time: .shortened))
                    .m3(M3Type.bodySmall).foregroundStyle(M3Color.onSurfaceVariant)
            }
            .frame(minHeight: 48)
            .m3Row()
        }
        .m3Screen()
        .navigationTitle("History")
        .task(id: state.expenses.count + state.members.count + state.settlements.count + (state.group?.name.count ?? 0)) {
            entries = (try? await repository.history(of: groupId)) ?? []
        }
        .overlay {
            if entries.isEmpty { Text("Nothing yet.").foregroundStyle(M3Color.onSurfaceVariant) }
        }
    }

    private func who(_ entry: HistoryEntry) -> String {
        state.members.first { $0.claimedBy == entry.author }?.name ?? String(localized: "A phone")
    }

    private func title(_ entry: HistoryEntry) -> String {
        entry.text ?? entry.subjectId.flatMap { id in state.expenses.first { $0.id == id }?.title } ?? String(localized: "an expense")
    }

    private func sentence(_ e: HistoryEntry) -> String {
        let name = who(e)
        switch e.type {
        case "group.create": return String(localized: "\(name) created the group \(e.text ?? "")")
        case "group.rename": return String(localized: "\(name) renamed the group to \(e.text ?? "")")
        case "member.add": return String(localized: "\(name) added the member \(e.text ?? "")")
        case "member.rename": return String(localized: "\(name) renamed a member to \(e.text ?? "")")
        case "member.remove": return String(localized: "\(name) removed \(e.subjectId.map(state.memberName) ?? "")")
        case "member.claim": return String(localized: "\(name) claimed \(e.subjectId.map(state.memberName) ?? "")")
        case "member.unclaim": return String(localized: "\(name) released \(e.subjectId.map(state.memberName) ?? "")")
        case "expense.add": return String(localized: "\(name) added the expense \(title(e))")
        case "expense.edit": return String(localized: "\(name) changed the expense \(title(e)): \(e.fields.map(fieldName).joined(separator: ", "))")
        case "expense.delete": return String(localized: "\(name) deleted the expense \(title(e))")
        case "settlement.add": return String(localized: "\(name) recorded a payment")
        case "settlement.delete": return String(localized: "\(name) deleted a payment")
        default: return String(localized: "\(name) made a change that this version does not know")
        }
    }

    private func fieldName(_ f: String) -> String {
        switch f {
        case "title": String(localized: "title")
        case "date": String(localized: "date")
        case "category": String(localized: "category")
        case "paidBy": String(localized: "payer")
        case "money": String(localized: "amount and split")
        default: f
        }
    }
}
