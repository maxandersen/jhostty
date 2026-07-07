import Foundation

/// Credentials resolved from the Keychain just before connecting. Kept separate
/// from `Host` so secrets have the shortest possible lifetime in memory.
struct SSHCredentials {
    var username: String
    var password: String?
    var privateKeyPEM: String?
    var passphrase: String?
}

enum SSHConnectionError: LocalizedError {
    case authenticationFailed
    case connectionFailed(String)
    case channelError(String)
    case notConnected
    case unsupportedKey

    var errorDescription: String? {
        switch self {
        case .authenticationFailed: return "Authentication failed. Check your username and credentials."
        case .connectionFailed(let m): return "Could not connect: \(m)"
        case .channelError(let m): return "Channel error: \(m)"
        case .notConnected: return "Not connected."
        case .unsupportedKey: return "Unsupported private key format. Use an Ed25519 or ECDSA (P-256) key."
        }
    }
}

/// A live interactive shell over SSH. Output bytes arrive on `onOutput`;
/// the terminal writes user keystrokes with `send`, and reports size changes
/// with `resize` so the remote PTY re-flows.
protocol SSHShell: AnyObject {
    var onOutput: ((Data) -> Void)? { get set }
    var onClose: ((Int32?) -> Void)? { get set }
    func send(_ data: Data)
    func resize(cols: Int, rows: Int)
    func close()
}

/// An SSH connection to a single host. Supports one-shot command execution
/// (used to probe `zmx list`) and opening an interactive PTY shell.
protocol SSHConnection: AnyObject {
    func connect(host: Host, credentials: SSHCredentials) async throws
    /// Run a command to completion and return its combined stdout.
    func run(_ command: String) async throws -> String
    /// Open an interactive shell with a PTY of the given size. If `command` is
    /// non-nil it is exec'd instead of the login shell (used for `zmx attach`).
    func openShell(cols: Int, rows: Int, command: String?) async throws -> SSHShell
    func disconnect() async
}
