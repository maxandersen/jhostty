import Foundation

/// How to authenticate to a host. The secret material itself is never stored on
/// the `Host` value — passwords and private keys live in the Keychain, keyed by
/// the host's `id`. See `Keychain`.
enum AuthMethod: String, Codable, CaseIterable, Identifiable {
    case password
    case privateKey

    var id: String { rawValue }

    var label: String {
        switch self {
        case .password: return "Password"
        case .privateKey: return "Private Key"
        }
    }
}

/// A saved SSH destination.
struct Host: Identifiable, Codable, Hashable {
    var id: UUID
    var name: String
    var hostname: String
    var port: Int
    var username: String
    var authMethod: AuthMethod

    /// When true, on connect the app runs `zmx list` and offers the session
    /// picker before opening a shell. When false it opens a plain login shell.
    var zmxEnabled: Bool

    /// Optional theme override; falls back to the global default when nil.
    var themeName: String?

    init(
        id: UUID = UUID(),
        name: String = "",
        hostname: String = "",
        port: Int = 22,
        username: String = "",
        authMethod: AuthMethod = .password,
        zmxEnabled: Bool = true,
        themeName: String? = nil
    ) {
        self.id = id
        self.name = name
        self.hostname = hostname
        self.port = port
        self.username = username
        self.authMethod = authMethod
        self.zmxEnabled = zmxEnabled
        self.themeName = themeName
    }

    /// Display title, falling back to `user@host` when no nickname is set.
    var title: String {
        name.isEmpty ? "\(username.isEmpty ? "" : "\(username)@")\(hostname)" : name
    }

    var subtitle: String {
        "\(username)@\(hostname):\(port)"
    }
}
