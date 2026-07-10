import SwiftUI

struct RootView: View {
    @EnvironmentObject var hostStore: HostStore
    @EnvironmentObject var themeStore: ThemeStore

    @State private var editingHost: Host?
    @State private var connectingHost: Host?
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            Group {
                if hostStore.hosts.isEmpty {
                    emptyState
                } else {
                    hostList
                }
            }
            .navigationTitle("Ghostly")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showingSettings = true } label: { Image(systemName: "gearshape") }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { editingHost = Host() } label: { Image(systemName: "plus") }
                }
            }
            .sheet(item: $editingHost) { host in
                HostEditView(host: host)
            }
            .sheet(isPresented: $showingSettings) {
                SettingsView()
            }
            .fullScreenCover(item: $connectingHost) { host in
                TerminalScreen(
                    session: TerminalSession(host: host, theme: themeStore.theme(named: host.themeName))
                )
            }
        }
    }

    private var hostList: some View {
        List {
            ForEach(hostStore.hosts) { host in
                HostRow(host: host)
                    .contentShape(Rectangle())
                    .onTapGesture { connectingHost = host }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) { hostStore.delete(host) } label: {
                            Label("Delete", systemImage: "trash")
                        }
                        Button { editingHost = host } label: {
                            Label("Edit", systemImage: "pencil")
                        }.tint(.blue)
                    }
            }
            .onMove { hostStore.move(from: $0, to: $1) }
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("No Hosts", systemImage: "terminal")
        } description: {
            Text("Add an SSH host to open a Ghostty-style terminal — zmx sessions are detected automatically.")
        } actions: {
            Button("Add Host") { editingHost = Host() }
                .buttonStyle(.borderedProminent)
        }
    }
}

struct HostRow: View {
    let host: Host

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "server.rack")
                .foregroundStyle(.secondary)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(host.title).font(.headline)
                Text(host.subtitle).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            if host.zmxEnabled {
                Image(systemName: "bolt.horizontal.circle")
                    .foregroundStyle(.tint)
                    .help("zmx session-aware")
            }
        }
        .padding(.vertical, 4)
    }
}
