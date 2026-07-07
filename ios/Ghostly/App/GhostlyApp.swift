import SwiftUI

@main
struct GhostlyApp: App {
    @StateObject private var hostStore = HostStore()
    @StateObject private var themeStore = ThemeStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(hostStore)
                .environmentObject(themeStore)
                .preferredColorScheme(.dark)
        }
    }
}
