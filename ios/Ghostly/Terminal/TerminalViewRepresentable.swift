import SwiftUI

/// Embeds a `TerminalEngine`'s UIKit view in SwiftUI.
struct TerminalViewRepresentable: UIViewRepresentable {
    let engine: TerminalEngine

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        let terminal = engine.view
        terminal.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(terminal)
        NSLayoutConstraint.activate([
            terminal.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            terminal.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            terminal.topAnchor.constraint(equalTo: container.topAnchor),
            terminal.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])
        return container
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}
