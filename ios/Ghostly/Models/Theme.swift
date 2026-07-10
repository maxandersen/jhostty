import SwiftUI

/// A terminal colour scheme. The JSON shape mirrors the theme entries bundled
/// with the desktop jhostty app (`themes/builtin-themes.json`) so schemes can
/// be shared between the two projects. Colours are `#rrggbb` hex strings.
struct Theme: Codable, Identifiable, Hashable {
    var name: String
    var background: String
    var foreground: String
    var cursor: String
    var palette: [String]   // 16 ANSI colours (0-7 normal, 8-15 bright)

    var id: String { name }

    static let fallback = Theme(
        name: "Ghostty Default",
        background: "#1d1f21",
        foreground: "#c5c8c6",
        cursor: "#c5c8c6",
        palette: [
            "#1d1f21", "#cc6666", "#b5bd68", "#f0c674",
            "#81a2be", "#b294bb", "#8abeb7", "#c5c8c6",
            "#666666", "#d54e53", "#b9ca4a", "#e7c547",
            "#7aa6da", "#c397d8", "#70c0b1", "#eaeaea",
        ]
    )
}

extension Color {
    /// Parse a `#rrggbb` (or `#aarrggbb`) hex string. Returns clear on failure.
    init(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        var value: UInt64 = 0
        guard Scanner(string: s).scanHexInt64(&value) else { self = .clear; return }

        let r, g, b, a: Double
        switch s.count {
        case 6:
            r = Double((value >> 16) & 0xff) / 255
            g = Double((value >> 8) & 0xff) / 255
            b = Double(value & 0xff) / 255
            a = 1
        case 8:
            a = Double((value >> 24) & 0xff) / 255
            r = Double((value >> 16) & 0xff) / 255
            g = Double((value >> 8) & 0xff) / 255
            b = Double(value & 0xff) / 255
        default:
            self = .clear
            return
        }
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}
