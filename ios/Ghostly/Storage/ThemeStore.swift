import Foundation
import Combine

/// Loads bundled terminal themes from `themes.json` and tracks the default
/// selection. The JSON format is compatible with the desktop jhostty app's
/// theme entries, so additional schemes can be dropped in unchanged.
@MainActor
final class ThemeStore: ObservableObject {
    @Published private(set) var themes: [Theme] = []
    @Published var defaultThemeName: String {
        didSet { UserDefaults.standard.set(defaultThemeName, forKey: Self.defaultsKey) }
    }

    private static let defaultsKey = "ghostly.defaultTheme"

    init() {
        defaultThemeName = UserDefaults.standard.string(forKey: Self.defaultsKey) ?? Theme.fallback.name
        themes = Self.loadBundled()
        if !themes.contains(where: { $0.name == defaultThemeName }) {
            defaultThemeName = themes.first?.name ?? Theme.fallback.name
        }
    }

    func theme(named name: String?) -> Theme {
        if let name, let match = themes.first(where: { $0.name == name }) { return match }
        return themes.first { $0.name == defaultThemeName }
            ?? themes.first
            ?? .fallback
    }

    private static func loadBundled() -> [Theme] {
        guard let url = Bundle.main.url(forResource: "themes", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([Theme].self, from: data),
              !decoded.isEmpty
        else { return [.fallback] }
        return decoded
    }
}
