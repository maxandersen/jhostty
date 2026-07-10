import UIKit

/// Abstraction over the thing that draws the terminal grid and turns user input
/// into bytes. Today the only implementation is `SwiftTermEngine`; the protocol
/// exists so a libghostty-backed engine (Ghostty's real renderer, via an
/// XCFramework) can be dropped in later without touching the SSH or zmx layers.
///
/// Data flow:
///   remote bytes ──feed──▶ engine ──renders──▶ screen
///   keystrokes   ◀─onInput── engine ◀──types──── user
protocol TerminalEngine: AnyObject {
    /// The view to embed in SwiftUI.
    var view: UIView { get }

    /// Called with bytes the user typed (or pasted). Wire this to the SSH shell.
    var onInput: ((Data) -> Void)? { get set }

    /// Called when the visible grid changes size, in character cells.
    var onResize: ((_ cols: Int, _ rows: Int) -> Void)? { get set }

    /// Current grid size in character cells.
    var gridSize: (cols: Int, rows: Int) { get }

    /// Feed bytes received from the remote into the emulator.
    func feed(_ data: Data)

    /// Apply a colour scheme.
    func apply(theme: Theme)
}
