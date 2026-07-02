# Scout Context: jhostty Modularization

## Current State
- **Single file**: `jhostty.java` — 1931 lines, 125+ static methods
- **JBang project** with `jbang-catalog.json` pointing to `jhostty.java`
- **Package**: currently none (default package)
- **Target package**: `dk.xam.jhostty`

## Logical Sections (by `// ---` markers)

| Section | Methods/Types | Lines (approx) | Description |
|---------|--------------|-----------------|-------------|
| Static fields | 35 fields | 59-94 | All shared mutable state |
| Sidebar model item | sealed interface + 5 records | 96-127 | `SidebarItem` hierarchy |
| zmx integration | `ZmxSession` record + 3 fields | 129-168 | zmx data model |
| Application lifecycle | `main`, `start`, `quit`, `newWindow`, `debug` | 170-245 | Entry point, window creation |
| Menu Bar | `createMenuBar` | 249-352 | Menu bar with Shell, View, Window menus |
| Right-click Context Menu | `createContextMenu` | 354-396 | Context menu creation |
| Tab Management | 6 methods | 399-457 | `newTab`, `rebuildWindowMenus`, `findTabPane`, etc. |
| Sidebar | 10 methods | 460-608 | Toggle, rebuild, find, show/hide sidebar |
| Split Management | 3 methods | 611-656 | `split`, `splitActive`, `evenDividers` |
| Close Management | 4 methods | 659-721 | `removeTerminal`, `findTab`, `containsNode` |
| Terminal Factory | 3 overloads | 724-807 | `createTerminal` + `createTerminalClean` |
| Apply settings globally | 17 methods | 810-1042 | Zoom, font, theme, CSS, tree walking utilities |
| PTY Terminal | `PtyTerminal` inner class | 1045-1076 | `Terminal` implementation wrapping pty4j |
| Shell detection | 5 methods + `ShellOption` record | 1080-1128 | Detect available shells |
| Themes | 3 methods + theme data | 1131-1196 | Theme definitions and factory methods |
| Config | 6 methods | 1199-1335 | `loadConfig`, `saveState`, `appendProp`, config dir |
| Layout save/restore | 12 methods | 1338-1733 | Layout capture, encode/decode, restore, `newWindowEmpty` |
| zmx session management | 7 methods | 1735-1853 | `initZmx`, `refreshZmxSessions`, `parseZmxList`, `attachZmxSession` |
| Font handling | 3 methods + `FontOption` record | 1857-1927 | Font detection and resolution |
| macOS app name | 1 method + 2 records | 1893-1931 | Native macOS naming via FFI |

## Dependency Map (key cross-section calls)

- **Everything** reads the static fields (currentTheme, windows, activeTerminal, etc.)
- **Layout save/restore** calls Terminal Factory, Tab Management, Split Management, Close Management
- **Sidebar** calls Tab Management, zmx session management
- **Terminal Factory** calls Shell detection, PTY Terminal
- **Config** calls Themes (to resolve theme name)
- **Menu Bar** calls Tab Management, Split Management, Settings, Themes, Font, zmx
- **zmx session management** calls Terminal Factory, Tab Management

## Records/Inner Types (extraction candidates)

1. `SidebarItem` (sealed interface + 5 records) — tightly coupled to sidebar UI
2. `ZmxSession` record — standalone data model, used by sidebar + zmx management
3. `PtyTerminal` class — implements `Terminal`, standalone
4. `ShellOption` record — standalone
5. `FontOption` record — standalone  
6. `ThemeOption` record — standalone

## Shared Mutable State (35 static fields)

All state is static — this is the biggest coupling. Key groups:
- **Appearance**: `currentFontFamily`, `currentThemeName`, `currentTheme`, `baseFontSize`, `currentZoom`, `cssPath`, `cssUrl`
- **Shell**: `shellCommand`, `detectedShellCommand`, `detectedFontFamily`, `cwd`
- **Windows**: `windows`, `windowMenus`, `sidebarsByWindow`, `activeTerminal`
- **Sidebar**: `sidebarVisible`, `sidebarDividerPos`, `sidebarRebuildScheduled`
- **Config**: `configDir`, `savedWindowX/Y`, `savedLayout`, `debug`
- **Lifecycle**: `shuttingDown`, `closingTerminals`
- **zmx**: `zmxSessions`, `zmxAvailable`, `zmxRefreshTimer`

## JBang Multi-file Support

JBang supports `//SOURCES` directive to include additional .java files:
```java
//SOURCES src/SomeClass.java
//SOURCES src/**/*.java  // glob patterns supported
```

Files can be in subdirectories and use packages. The main file remains the entry point.

## Key Observations

1. **`newWindowEmpty` is ~190 lines** — the biggest single method, creates the entire window with sidebar, event filters, etc.
2. **"Apply settings globally"** is a catch-all bucket (17 methods) — needs splitting
3. **Themes are pure data** — easy extraction, no dependencies
4. **Config and Layout are closely coupled** — both save/restore state
5. **The 35 static fields are the main coupling barrier** — need a shared state object or keep them in main class
6. **No tests exist currently**
