import SwiftUI

/// Add / edit a host. Secrets are written straight to the Keychain on save and
/// are pre-filled from it when editing an existing host.
struct HostEditView: View {
    @EnvironmentObject var hostStore: HostStore
    @EnvironmentObject var themeStore: ThemeStore
    @Environment(\.dismiss) private var dismiss

    @State private var host: Host
    @State private var password: String = ""
    @State private var privateKey: String = ""
    @State private var passphrase: String = ""

    private let isNew: Bool

    init(host: Host) {
        _host = State(initialValue: host)
        isNew = host.hostname.isEmpty && host.username.isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Connection") {
                    TextField("Nickname (optional)", text: $host.name)
                    TextField("Hostname or IP", text: $host.hostname)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    HStack {
                        Text("Port")
                        Spacer()
                        TextField("22", value: $host.port, format: .number)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 80)
                    }
                    TextField("Username", text: $host.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                Section("Authentication") {
                    Picker("Method", selection: $host.authMethod) {
                        ForEach(AuthMethod.allCases) { Text($0.label).tag($0) }
                    }
                    switch host.authMethod {
                    case .password:
                        SecureField("Password", text: $password)
                    case .privateKey:
                        VStack(alignment: .leading) {
                            Text("Private Key (PEM)").font(.caption).foregroundStyle(.secondary)
                            TextEditor(text: $privateKey)
                                .font(.system(.footnote, design: .monospaced))
                                .frame(minHeight: 120)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                        }
                        SecureField("Key passphrase (optional)", text: $passphrase)
                    }
                }

                Section("Terminal") {
                    Toggle(isOn: $host.zmxEnabled) {
                        VStack(alignment: .leading) {
                            Text("zmx session-aware")
                            Text("Detect persistent zmx sessions on connect and offer to attach.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Picker("Theme", selection: Binding(
                        get: { host.themeName ?? themeStore.defaultThemeName },
                        set: { host.themeName = $0 }
                    )) {
                        ForEach(themeStore.themes) { Text($0.name).tag($0.name) }
                    }
                }
            }
            .navigationTitle(isNew ? "New Host" : "Edit Host")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear(perform: loadSecrets)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save).disabled(!isValid)
                }
            }
        }
    }

    private var isValid: Bool {
        !host.hostname.trimmingCharacters(in: .whitespaces).isEmpty &&
        !host.username.trimmingCharacters(in: .whitespaces).isEmpty &&
        host.port > 0
    }

    private func loadSecrets() {
        guard !isNew else { return }
        password = Keychain.get(for: host.id, kind: .password) ?? ""
        privateKey = Keychain.get(for: host.id, kind: .privateKey) ?? ""
        passphrase = Keychain.get(for: host.id, kind: .passphrase) ?? ""
    }

    private func save() {
        hostStore.upsert(
            host,
            password: host.authMethod == .password ? password : nil,
            privateKey: host.authMethod == .privateKey ? privateKey : nil,
            passphrase: host.authMethod == .privateKey ? passphrase : nil
        )
        dismiss()
    }
}
