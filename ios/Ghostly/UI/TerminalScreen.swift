import SwiftUI

/// Full-screen terminal. Renders connection progress, the zmx session picker,
/// or the live terminal depending on the session `phase`.
struct TerminalScreen: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject var session: TerminalSession

    var body: some View {
        NavigationStack {
            content
                .background(Color(hex: session.theme.background).ignoresSafeArea())
                .navigationTitle(session.host.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { toolbar }
                .task { await session.start() }
        }
        .tint(Color(hex: session.theme.foreground))
    }

    @ViewBuilder
    private var content: some View {
        switch session.phase {
        case .connecting:
            statusView("Connecting to \(session.host.hostname)…", systemImage: "network")
        case .discoveringZmx:
            statusView("Looking for zmx sessions…", systemImage: "bolt.horizontal.circle")
        case .choosingZmx(let sessions):
            ZmxSessionPickerView(
                sessions: sessions,
                onAttach: { s in Task { await session.attach(to: s) } },
                onNew: { Task { await session.attach(to: nil) } },
                onPlainShell: { Task { await session.openPlainShell() } },
                onKill: { s in Task { await session.killZmxSession(s) } }
            )
        case .live:
            TerminalViewRepresentable(engine: session.engine)
                .ignoresSafeArea(.container, edges: .bottom)
        case .failed(let message):
            statusView(message, systemImage: "exclamationmark.triangle", isError: true)
        case .closed(let code):
            statusView(
                code.map { "Session ended (exit \($0))." } ?? "Session closed.",
                systemImage: "xmark.circle"
            )
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button("Close") { Task { await session.disconnect(); dismiss() } }
        }
        if session.host.zmxEnabled, case .live = session.phase {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        session.detach()
                    } label: {
                        Label("Detach (leave running)", systemImage: "bolt.horizontal")
                    }
                    Button(role: .destructive) {
                        Task { await session.disconnect(); dismiss() }
                    } label: {
                        Label("Disconnect", systemImage: "xmark.circle")
                    }
                } label: {
                    Image(systemName: "bolt.horizontal.circle")
                }
            }
        }
    }

    private func statusView(_ message: String, systemImage: String, isError: Bool = false) -> some View {
        VStack(spacing: 16) {
            Image(systemName: systemImage)
                .font(.system(size: 42))
                .foregroundStyle(isError ? Color.red : Color(hex: session.theme.foreground))
            Text(message)
                .multilineTextAlignment(.center)
                .foregroundStyle(Color(hex: session.theme.foreground))
                .padding(.horizontal, 32)
            if isError {
                Button("Close") { Task { await session.disconnect(); dismiss() } }
                    .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
