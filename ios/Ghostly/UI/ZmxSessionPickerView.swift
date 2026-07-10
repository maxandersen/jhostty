import SwiftUI

/// Presented when a host has one or more persistent zmx sessions. Lets the user
/// re-attach to a running session, start a fresh one, or bypass zmx entirely.
struct ZmxSessionPickerView: View {
    let sessions: [ZmxSession]
    let onAttach: (ZmxSession) -> Void
    let onNew: () -> Void
    let onPlainShell: () -> Void
    let onKill: (ZmxSession) -> Void

    private var liveSessions: [ZmxSession] { sessions.filter { !$0.ended } }

    var body: some View {
        List {
            Section {
                ForEach(liveSessions) { session in
                    Button { onAttach(session) } label: {
                        HStack {
                            Image(systemName: session.isAttached ? "bolt.horizontal.circle.fill" : "bolt.horizontal.circle")
                                .foregroundStyle(.tint)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(session.friendlyName).font(.headline)
                                Text(detail(for: session))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                        }
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) { onKill(session) } label: {
                            Label("Kill", systemImage: "trash")
                        }
                    }
                }
            } header: {
                Text("Attach to a zmx session")
            } footer: {
                Text("These sessions kept running on the host after your last disconnect. zmx restores their state when you re-attach.")
            }

            Section {
                Button { onNew() } label: {
                    Label("New zmx session", systemImage: "plus.circle")
                }
                Button { onPlainShell() } label: {
                    Label("Plain login shell (no zmx)", systemImage: "terminal")
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private func detail(for session: ZmxSession) -> String {
        var parts: [String] = []
        if session.isAttached {
            parts.append("\(session.clients) client\(session.clients == 1 ? "" : "s") attached")
        } else {
            parts.append("detached")
        }
        if !session.cmd.isEmpty { parts.append(session.cmd) }
        return parts.joined(separator: " · ")
    }
}
