import Foundation

/// zmx-awareness: discovers persistent sessions on the remote and builds the
/// commands to attach to / create / manage them.
///
/// zmx (https://zmx.sh) keeps a daemon-per-session on the remote host, so an
/// SSH drop does not kill your work — you reconnect and re-attach, and zmx
/// restores the terminal state. This controller makes Ghostly a first-class
/// zmx client: on connect it runs `zmx list`, and attaching opens a shell that
/// execs `zmx attach <name>`.
enum ZmxController {
    /// Detach key sequence recognised by zmx (Ctrl+\, i.e. FS / 0x1c).
    static let detachSequence = Data([0x1c])

    /// Probe the remote for zmx and its current sessions.
    ///
    /// Returns `nil` when zmx is not installed (so the UI can fall back to a
    /// plain login shell), or a possibly-empty list when it is.
    static func discover(using connection: SSHConnection) async -> [ZmxSession]? {
        // `command -v zmx` keeps the probe quiet when zmx is absent.
        guard let probe = try? await connection.run("command -v zmx >/dev/null 2>&1 && echo yes || echo no"),
              probe.contains("yes")
        else { return nil }

        guard let listing = try? await connection.run("zmx list 2>/dev/null") else { return [] }
        return ZmxSession.parseList(listing)
    }

    /// The command to exec for attaching to (or creating) a session.
    ///
    /// `zmx attach` is an upsert: it attaches to `name` if it exists, otherwise
    /// creates it. Passing `nil` starts a fresh auto-named session.
    static func attachCommand(session name: String?) -> String {
        if let name, !name.isEmpty {
            return "zmx attach \(shellQuote(name))"
        }
        return "zmx attach \(shellQuote(defaultSessionName()))"
    }

    /// Kill a session outright (used by the picker's swipe action).
    static func kill(_ name: String, using connection: SSHConnection) async throws {
        _ = try await connection.run("zmx kill \(shellQuote(name)) --force")
    }

    /// A friendly default name for a brand-new session: `ios-<short-random>`.
    static func defaultSessionName() -> String {
        "ios-" + UUID().uuidString.prefix(4).lowercased()
    }

    /// Single-quote a value for safe interpolation into a remote shell command.
    static func shellQuote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}
