import SwiftUI
import Combine

/// Drives one terminal tab: opens the SSH connection, runs zmx discovery, wires
/// the terminal engine to the shell channel, and exposes state to SwiftUI.
@MainActor
final class TerminalSession: ObservableObject {
    enum Phase: Equatable {
        case connecting
        case discoveringZmx
        case choosingZmx([ZmxSession])
        case live
        case failed(String)
        case closed(Int32?)
    }

    @Published private(set) var phase: Phase = .connecting

    let host: Host
    let engine: TerminalEngine
    let theme: Theme

    private let connection: SSHConnection
    private var shell: SSHShell?

    init(host: Host, theme: Theme, connection: SSHConnection = NIOSSHConnection(), engine: TerminalEngine = SwiftTermEngine()) {
        self.host = host
        self.theme = theme
        self.connection = connection
        self.engine = engine
        engine.apply(theme: theme)
    }

    /// Establish the SSH connection and decide how to open the shell.
    func start() async {
        phase = .connecting
        let creds = SSHCredentials(
            username: host.username,
            password: Keychain.get(for: host.id, kind: .password),
            privateKeyPEM: Keychain.get(for: host.id, kind: .privateKey),
            passphrase: Keychain.get(for: host.id, kind: .passphrase)
        )

        do {
            try await connection.connect(host: host, credentials: creds)
        } catch {
            phase = .failed((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
            return
        }

        guard host.zmxEnabled else {
            await openShell(command: nil)
            return
        }

        phase = .discoveringZmx
        if let sessions = await ZmxController.discover(using: connection) {
            // zmx present: let the user pick, unless there is nothing to pick.
            if sessions.filter({ !$0.ended }).isEmpty {
                await openShell(command: ZmxController.attachCommand(session: nil))
            } else {
                phase = .choosingZmx(sessions)
            }
        } else {
            // zmx not installed on this host — fall back to a login shell.
            await openShell(command: nil)
        }
    }

    /// Attach to an existing zmx session (or `nil` to create a fresh one).
    func attach(to session: ZmxSession?) async {
        await openShell(command: ZmxController.attachCommand(session: session?.name))
    }

    /// Start a plain login shell, ignoring zmx.
    func openPlainShell() async {
        await openShell(command: nil)
    }

    func killZmxSession(_ session: ZmxSession) async {
        try? await ZmxController.kill(session.name, using: connection)
        if case .choosingZmx = phase, let refreshed = await ZmxController.discover(using: connection) {
            phase = .choosingZmx(refreshed)
        }
    }

    /// Send the zmx detach sequence (Ctrl+\) — leaves the session running remotely.
    func detach() {
        shell?.send(ZmxController.detachSequence)
    }

    func disconnect() async {
        shell?.close()
        await connection.disconnect()
    }

    // MARK: - Wiring

    private func openShell(command: String?) async {
        let size = engine.gridSize
        let cols = max(size.cols, 1)
        let rows = max(size.rows, 1)
        do {
            let shell = try await connection.openShell(cols: cols, rows: rows, command: command)
            self.shell = shell

            // remote → terminal
            shell.onOutput = { [weak self] data in self?.engine.feed(data) }
            shell.onClose = { [weak self] code in self?.phase = .closed(code) }

            // terminal → remote
            engine.onInput = { [weak shell] data in shell?.send(data) }
            engine.onResize = { [weak shell] cols, rows in shell?.resize(cols: cols, rows: rows) }

            phase = .live
        } catch {
            phase = .failed((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }
}
