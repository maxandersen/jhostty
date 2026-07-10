import UIKit
import SwiftTerm

/// `TerminalEngine` backed by SwiftTerm's `TerminalView`, a mature xterm-256
/// compatible emulator. SwiftTerm handles parsing, the grid, scrollback,
/// selection and the on-screen keyboard input; we bridge its delegate to our
/// byte-oriented `onInput`/`onResize` callbacks.
final class SwiftTermEngine: NSObject, TerminalEngine, TerminalViewDelegate {
    private let terminalView: TerminalView

    var onInput: ((Data) -> Void)?
    var onResize: ((Int, Int) -> Void)?

    var view: UIView { terminalView }

    var gridSize: (cols: Int, rows: Int) {
        let t = terminalView.getTerminal()
        return (t.cols, t.rows)
    }

    override init() {
        terminalView = TerminalView(frame: .zero)
        super.init()
        terminalView.terminalDelegate = self
        terminalView.translatesAutoresizingMaskIntoConstraints = false
    }

    func feed(_ data: Data) {
        terminalView.feed(byteArray: [UInt8](data)[...])
    }

    func apply(theme: Theme) {
        terminalView.nativeBackgroundColor = UIColor(hex: theme.background)
        terminalView.nativeForegroundColor = UIColor(hex: theme.foreground)
        let ansi = theme.palette.prefix(16).map { SwiftTerm.Color(hex: $0) }
        if ansi.count == 16 {
            terminalView.installColors(ansi)
        }
        terminalView.setNeedsDisplay(terminalView.bounds)
    }

    // MARK: - TerminalViewDelegate

    /// Bytes the user typed / pasted, headed for the remote.
    func send(source: TerminalView, data: ArraySlice<UInt8>) {
        onInput?(Data(data))
    }

    /// The rendered grid changed size — tell the remote PTY to re-flow.
    func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
        onResize?(newCols, newRows)
    }

    func setTerminalTitle(source: TerminalView, title: String) {}
    func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
    func scrolled(source: TerminalView, position: Double) {}
    func requestOpenLink(source: TerminalView, link: String, params: [String: String]) {
        guard let url = URL(string: link) else { return }
        UIApplication.shared.open(url)
    }
    func bell(source: TerminalView) {}
    func clipboardCopy(source: TerminalView, content: Data) {
        if let text = String(data: content, encoding: .utf8) {
            UIPasteboard.general.string = text
        }
    }
    func iTermContent(source: TerminalView, content: ArraySlice<UInt8>) {}
    func rangeChanged(source: TerminalView, startY: Int, endY: Int) {}
}

private func rgbComponents(hex: String) -> (r: CGFloat, g: CGFloat, b: CGFloat) {
    var s = hex
    if s.hasPrefix("#") { s.removeFirst() }
    var value: UInt64 = 0
    Scanner(string: s).scanHexInt64(&value)
    return (
        CGFloat((value >> 16) & 0xff) / 255,
        CGFloat((value >> 8) & 0xff) / 255,
        CGFloat(value & 0xff) / 255
    )
}

private extension SwiftTerm.Color {
    /// Build a SwiftTerm colour (16-bit channels) from a `#rrggbb` hex string.
    convenience init(hex: String) {
        let c = rgbComponents(hex: hex)
        self.init(red: UInt16(c.r * 65535), green: UInt16(c.g * 65535), blue: UInt16(c.b * 65535))
    }
}

private extension UIColor {
    convenience init(hex: String) {
        let c = rgbComponents(hex: hex)
        self.init(red: c.r, green: c.g, blue: c.b, alpha: 1)
    }
}
