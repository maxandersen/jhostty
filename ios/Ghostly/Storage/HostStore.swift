import Foundation
import Combine

/// Persists the list of saved hosts as JSON in Application Support and keeps an
/// observable copy for SwiftUI. Secrets are handled separately by `Keychain`.
@MainActor
final class HostStore: ObservableObject {
    @Published private(set) var hosts: [Host] = []

    private let fileURL: URL

    init(filename: String = "hosts.json") {
        let base = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Ghostly", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        fileURL = base.appendingPathComponent(filename)
        load()
    }

    func upsert(_ host: Host, password: String? = nil, privateKey: String? = nil, passphrase: String? = nil) {
        if let idx = hosts.firstIndex(where: { $0.id == host.id }) {
            hosts[idx] = host
        } else {
            hosts.append(host)
        }
        if let password { Keychain.set(password, for: host.id, kind: .password) }
        if let privateKey { Keychain.set(privateKey, for: host.id, kind: .privateKey) }
        if let passphrase { Keychain.set(passphrase, for: host.id, kind: .passphrase) }
        save()
    }

    func delete(_ host: Host) {
        hosts.removeAll { $0.id == host.id }
        Keychain.removeAll(for: host.id)
        save()
    }

    func move(from source: IndexSet, to destination: Int) {
        hosts.move(fromOffsets: source, toOffset: destination)
        save()
    }

    // MARK: - Persistence

    private func load() {
        guard let data = try? Data(contentsOf: fileURL) else { return }
        hosts = (try? JSONDecoder().decode([Host].self, from: data)) ?? []
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(hosts) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}
