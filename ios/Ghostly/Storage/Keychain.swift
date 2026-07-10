import Foundation
import Security

/// Minimal Keychain wrapper for host secrets (passwords / private keys).
///
/// Secrets are stored as generic passwords under a per-host account string so
/// they never touch the Codable `Host` value or `UserDefaults`.
enum Keychain {
    private static let service = "dev.jbang.ghostly.ssh"

    static func account(for hostID: UUID, kind: SecretKind) -> String {
        "\(hostID.uuidString).\(kind.rawValue)"
    }

    enum SecretKind: String {
        case password
        case privateKey
        case passphrase
    }

    static func set(_ value: String?, for hostID: UUID, kind: SecretKind) {
        let account = account(for: hostID, kind: kind)
        // Always clear first so a nil/empty value removes the item.
        delete(account: account)
        guard let value, !value.isEmpty, let data = value.data(using: .utf8) else { return }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    static func get(for hostID: UUID, kind: SecretKind) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account(for: hostID, kind: kind),
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let string = String(data: data, encoding: .utf8)
        else { return nil }
        return string
    }

    static func removeAll(for hostID: UUID) {
        for kind in [SecretKind.password, .privateKey, .passphrase] {
            delete(account: account(for: hostID, kind: kind))
        }
    }

    private static func delete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
