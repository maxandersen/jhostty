# jhostty

A terminal emulator built as a single JBang file using [GhosttyFX](https://github.com/vlaaad/ghosttyfx) — Ghostty's terminal engine in JavaFX.

![jhostty](https://img.shields.io/badge/Java-25-orange) ![GhosttyFX](https://img.shields.io/badge/GhosttyFX-0.1.169-blue)

## Run

```bash
jbang jhostty@maxandersen
```

Or clone and run directly:

```bash
jbang jhostty.java
```

## Features

- **Ghostty-powered** terminal rendering via GhosttyFX
- **Multiple windows** (⌘N), **tabs** (⌘T), **splits** (⌘D horizontal, ⌘⇧D vertical)
- **Per-terminal zoom** — ⌘+/⌘- or Cmd+scroll/pinch, targets terminal under cursor
- **9 built-in themes** — Ghostty Default, Catppuccin Mocha, Dracula, Nord, Tokyo Night, Gruvbox Dark, Monokai, Solarized Dark/Light, GitHub Light
- **All system fonts** available — preferred terminal fonts listed first
- **Native macOS menu bar** with Shell, View, and Window menus
- **Right-click context menu** styled to match the selected theme
- **Drag-and-drop** — drop text, files, or URLs onto any terminal
- **Built-in search** (⌘F) with Ghostty shell integration
- **Link detection** — clickable URLs in terminal output
- **Shell integration** — prompt navigation via Ghostty's shell integration

## Keyboard Shortcuts

| Action | Shortcut |
|---|---|
| New Window | ⌘N |
| New Tab | ⌘T |
| Split Horizontal | ⌘D |
| Split Vertical | ⌘⇧D |
| Close Terminal | ⌘W |
| Zoom In | ⌘+ or Cmd+scroll up |
| Zoom Out | ⌘- or Cmd+scroll down |
| Reset Zoom | ⌘0 |
| Search | ⌘F |

## Requirements

- Java 25 (auto-downloaded by JBang)
- [JBang](https://jbang.dev)

## Font Recommendation

For best nerd font glyph support (powerline symbols, git icons in prompts):

```bash
brew install font-jetbrains-mono-nerd-font
```

## License

MIT
