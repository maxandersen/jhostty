# jhostty — Handoff Notes

## What is this?

A single-file JBang terminal emulator (`jhostty.java`) using [GhosttyFX](https://github.com/vlaaad/ghosttyfx) — Ghostty's terminal engine exposed as a JavaFX control. It's a ~800 line self-contained app with no build system beyond JBang.

## How to run

```bash
jbang jhostty.java           # local
jbang jhostty@maxandersen    # from jbang-catalog
```

Requires Java 25 (auto-downloaded by JBang). JavaFX comes transitively via ghosttyfx — do NOT add a `//JAVAFX` directive (it's not a real JBang directive).

## Architecture

- **Single file**: Everything is in `jhostty.java` — no separate classes, no resources
- **No Application subclass**: Uses `Platform.startup()` directly
- **Multi-window**: Each window has its own `Stage`, `TabPane`, `MenuBar`. Windows tracked in static `List<Stage> windows`
- **Split panes**: Tabs contain either a bare `TerminalView` or nested `SplitPane` trees. Splitting wraps the existing view.
- **Per-terminal zoom**: Font size stored in `view.getProperties()` via `ZOOM_KEY`. Font/theme are global, zoom is per-terminal.
- **macOS menu bar**: Uses `menuBar.setUseSystemMenuBar(true)`. App name set via Objective-C runtime FFM call (`NSMenu setTitle:`)

## Key dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `io.github.vlaaad:ghosttyfx` | 0.1.169 | Terminal control (pulls in JavaFX transitively) |
| `org.jetbrains.pty4j:pty4j` | 0.13.12 | PTY backend |
| `org.slf4j:slf4j-nop` | 2.0.13 | Silence SLF4J warnings from pty4j |

## GhosttyFX API quirks learned the hard way

### SplitPane parent lookup
`view.getParent()` inside a `SplitPane` returns `SplitPaneSkin$Content`, NOT the `SplitPane` itself. Must walk up the scene graph with `findParentSplitPane()`. This affects both splitting and terminal removal (unwrapping splits).

### Font resolution
`Font.font("JetBrains Mono", 15)` picks **ExtraBold** weight on some systems. Must use exact font name: `new Font("JetBrains Mono Regular", 15)`. The `resolveFont()` helper finds the Regular variant via `Font.getFontNames(family)`.

Also: `Font.getFamilies()` lists "JetBrainsMono Nerd Font" but `Font.getFamily()` on the resolved font returns "JetBrainsMono NF" — different strings. Don't compare resolved family names against the families list.

### Keyboard shortcuts
TerminalView consumes ALL key events. Menu accelerators never fire. Must use `scene.addEventFilter(KeyEvent.KEY_PRESSED, ...)` to intercept shortcuts before the terminal sees them.

### Context menu
TerminalView extends AnchorPane (not Control) — no `setContextMenu()`. Use `view.setOnContextMenuRequested()` instead. Do NOT use `setOnMousePressed` — it overrides terminal's mouse handling and breaks keyboard focus.

### macOS system menu bar
- `setOnShowing` doesn't fire for native macOS menus — must rebuild menu items explicitly
- MenuBar with `setUseSystemMenuBar(true)` still reserves layout space — set maxHeight/prefHeight/minHeight to 0
- App name shows "java" — fixed via Objective-C runtime: call `[[NSApp mainMenu] itemAtIndex:0] submenu] setTitle:]` using java.lang.foreign FFM

### Font rendering
- Do NOT set `-Dprism.text=t2k` — the T2K rasterizer breaks monospace kerning on macOS
- Do NOT set `-Dprism.lcdtext=false` — makes rendering worse
- Let JavaFX use the native CoreText renderer (default on macOS)
- FiraCode Nerd Font renders poorly in GhosttyFX due to its 9.23pt cell width (vs JetBrains Mono's clean 9.00pt). This is NOT an integer rounding issue in CellMetrics (both get cellWidthPx=9). Root cause unknown — possibly JavaFX Canvas fillText behavior with that specific font.

### CSS
- CSS is written to a temp file and loaded via `scene.getStylesheets()`. No `data:` URL support in JavaFX.
- CSS is regenerated on theme change with colors derived from the current `TerminalTheme`.
- To reload CSS: remove and re-add the URL from stylesheets.

## Known issues / TODO

- **FiraCode Nerd Font** renders with extra spacing — root cause not fully understood. JetBrains Mono works perfectly.
- **Link clicking** works on hover (hand cursor appears) but some users don't notice it — could add a visual hint
- **Tab bar** hide/show uses CSS class toggle `.single-tab` — occasionally flickers on rapid tab add/remove
- **No persist/restore** of window positions, theme, font preferences
- **No configuration file** — everything is hardcoded defaults
- Could add: tab reordering via drag, move terminal between tabs/windows, broadcast input to all terminals

## Related repos

- **GhosttyFX**: https://github.com/vlaaad/ghosttyfx — the terminal control library
- **jbang-catalog**: https://github.com/maxandersen/jbang-catalog — has `jhostty` alias pointing here
- **Development history**: Full commit history is in the jbang-catalog repo on the `jhostty` branch under `jhostty/jhostty.java`
