import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var themeStore: ThemeStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Default Theme") {
                    Picker("Theme", selection: $themeStore.defaultThemeName) {
                        ForEach(themeStore.themes) { theme in
                            ThemeSwatch(theme: theme).tag(theme.name)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }

                Section {
                    LabeledContent("Terminal engine", value: "SwiftTerm")
                    LabeledContent("SSH", value: "swift-nio-ssh")
                    LabeledContent("Sessions", value: "zmx-aware")
                } header: {
                    Text("About")
                } footer: {
                    Text("Ghostly — a zmx-aware SSH terminal in the jhostty family. Themes are compatible with the desktop jhostty app.")
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

struct ThemeSwatch: View {
    let theme: Theme

    var body: some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 4)
                .fill(Color(hex: theme.background))
                .overlay(
                    RoundedRectangle(cornerRadius: 4).strokeBorder(.quaternary)
                )
                .frame(width: 34, height: 22)
                .overlay(
                    Text("A")
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color(hex: theme.foreground))
                )
            Text(theme.name)
            Spacer()
            HStack(spacing: 3) {
                ForEach(Array(theme.palette.prefix(8).enumerated()), id: \.offset) { _, hex in
                    Circle().fill(Color(hex: hex)).frame(width: 8, height: 8)
                }
            }
        }
    }
}
