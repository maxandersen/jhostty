# Plan: Modularize jhostty

## Approach

Pragmatic split into ~10 source files along natural boundaries. Shared mutable state lives in a simple `AppState` class with package-private fields — no getters/setters ceremony, just direct field access within `dk.xam.jhostty`.

## File Structure

```
jhostty.java                          # JBang entry point — //SOURCES, //DEPS
src/dk/xam/jhostty/
├── JHostty.java                      # Application subclass, main(), start(), quit(), newWindow()
├── AppState.java                     # All shared mutable state (35 fields), debug()
├── WindowFactory.java                # newWindowEmpty(), event filters, key shortcuts, scroll/zoom handlers
├── Sidebar.java                      # SidebarItem sealed interface + records, cell factory, toggle, rebuild
├── MenuFactory.java                  # createMenuBar(), createContextMenu()
├── TerminalManager.java              # createTerminal(), tab/split/close management, tree walking utilities
├── ZmxManager.java                   # ZmxSession record, init, refresh, parse, attach, findTerminal
├── Config.java                       # loadConfig(), saveState(), resolveConfigDir()
├── Layout.java                       # captureLayout(), restoreLayout(), encode/decode, restoreTab()
├── Themes.java                       # ThemeOption record, theme definitions, CSS generation
├── ShellDetection.java               # ShellOption record, detectTerminals(), resolveExecutable()
├── PtyTerminal.java                  # Terminal implementation wrapping pty4j
└── FontManager.java                  # FontOption record, detectFonts(), resolveFont(), defaultFontFamily()
test/dk/xam/jhostty/
├── ZmxManagerTest.java               # parseZmxList, friendlyName, isGeneratedName
├── LayoutTest.java                   # encode/decode roundtrip, captureNode format, splitTabDescs
├── ConfigTest.java                   # appendProp formatting, properties roundtrip
└── ShellDetectionTest.java           # resolveExecutable with mocked PATH
```

## Entry Point (jhostty.java)

Stays at the root as the JBang entry point. Minimal — just directives and delegation:

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS io.github.vlaaad:ghosttyfx:0.1.169
//DEPS org.jetbrains.pty4j:pty4j:0.13.12
//DEPS org.slf4j:slf4j-nop:2.0.13
//DEPS io.smallrye.config:smallrye-config:3.12.4
//SOURCES src/dk/xam/jhostty/*.java
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED

import dk.xam.jhostty.JHostty;
import javafx.application.Application;

public class jhostty {
    public static void main(String[] args) {
        JHostty.run(args);
    }
}
```

## AppState — The Coupling Solution

Simple class with package-private fields. No getters/setters — this is project-internal:

```java
package dk.xam.jhostty;

class AppState {
    // Appearance
    String currentFontFamily;
    String currentThemeName = "Ghostty Default";
    TerminalTheme currentTheme;
    double baseFontSize = 15.0;
    double currentZoom = 15.0;
    Path cssPath;
    String cssUrl;

    // Shell
    List<String> shellCommand;
    List<String> detectedShellCommand;
    String detectedFontFamily;
    Path cwd;

    // Windows
    final List<Stage> windows = new ArrayList<>();
    final List<Menu> windowMenus = new ArrayList<>();
    final Map<Stage, TreeView<SidebarItem>> sidebarsByWindow = new ConcurrentHashMap<>();
    TerminalView activeTerminal;

    // Sidebar
    boolean sidebarVisible = false;
    double sidebarDividerPos = 0.18;
    boolean sidebarRebuildScheduled = false;

    // Config
    Path configDir;
    double savedWindowX = Double.NaN;
    double savedWindowY = Double.NaN;
    String savedLayout;
    boolean debug;

    // Lifecycle
    boolean shuttingDown = false;
    final Set<TerminalView> closingTerminals = ConcurrentHashMap.newKeySet();

    // zmx
    List<ZmxSession> zmxSessions = List.of();
    volatile boolean zmxAvailable = false;
    Timeline zmxRefreshTimer;

    void debug(String msg) {
        if (debug) System.err.println("[jhostty] " + msg);
    }

    static final AppState INSTANCE = new AppState();
}
```

All classes access `AppState.INSTANCE` — simple, explicit, no magic.

## Responsibilities per File

### JHostty.java (~120 lines)
- `run(args)` — static entry, sets up uncaught handler, shutdown hook
- `start(Stage)` — init config, detect shells, create CSS, call `restoreLayout` or `newWindow`
- `quit()` — save state, stop timers, close windows
- `newWindow()` — delegates to `WindowFactory.newWindowEmpty()` + `TerminalManager.newTab()`

### WindowFactory.java (~200 lines)
- `newWindowEmpty()` — creates Stage, BorderPane, SplitPane, TabPane, sidebar, wires event filters
- Key shortcut handling (event filter)
- Scroll/zoom event filters

### Sidebar.java (~200 lines)
- `SidebarItem` sealed interface + WindowItem, TabItem, TerminalItem, SectionHeader, ZmxSessionItem records
- `createSidebar()` — cell factory with icons, double-click handler
- `toggleSidebar()`, `showSidebarIn()`, `hideSidebarIn()`
- `rebuildAllSidebars()`, `rebuildSidebar()`

### MenuFactory.java (~150 lines)
- `createMenuBar(TabPane)` — Shell menu, View menu, Window menu, zmx submenu
- `createContextMenu(TerminalView)` — right-click menu

### TerminalManager.java (~300 lines)
- `createTerminal()` overloads, `createTerminalClean()`
- `newTab()`, `split()`, `splitActive()`, `evenDividers()`
- `closeActive()`, `removeTerminal()`, `closeAllTerminalsIn()`
- `findTabPane()`, `findStage()`, `findTab()`, `findParentSplitPane()`
- `forEachTerminal()`, `terminalAt()`, `bindTabTitle()`, `commandBaseName()`
- `getTerminalCommand()`, zoom/font/theme application methods
- `getTabPane(Stage)`, `getContentSplit(Stage)`

### ZmxManager.java (~180 lines)
- `ZmxSession` record with `displayLabel()`, `friendlyName()`, `isGeneratedName()`
- `initZmx()`, `refreshZmxSessions()`, `parseZmxList()`
- `attachZmxSession()`, `findTerminalForZmxSession()`
- `findActiveTabPane()`

### Config.java (~180 lines)
- `resolveConfigDir()`
- `loadConfig()` — reads properties, populates AppState
- `saveState()` — writes state properties file
- `appendProp()` helpers

### Layout.java (~200 lines)
- `captureLayout()`, `captureNode()`
- `restoreLayout()`, `restoreTab()`
- `encodeCommand()`, `decodeCommand()`, `parseCommand()`
- `splitTabDescs()`, `splitCommands()`, `collectTerminals()`

### Themes.java (~120 lines)
- `ThemeOption` record
- `themes()` — all theme definitions
- `darkTheme()`, `lightTheme()` factory methods
- `writeCss()`, `colorToCss()`, `applyThemeToAll()`

### ShellDetection.java (~60 lines)
- `ShellOption` record
- `detectTerminals()`, `detectWindowsShells()`, `detectUnixShells()`
- `addShell()`, `resolveExecutable()`

### PtyTerminal.java (~40 lines)
- `PtyTerminal` class implementing `Terminal`

### FontManager.java (~50 lines)
- `FontOption` record
- `defaultFontFamily()`, `resolveFont()`, `detectFonts()`

## Tests

Using JBang's test support (`//SOURCES` in test files, run with `jbang test`):

### ZmxManagerTest.java
- `parseZmxList` with real output samples (sessions with/without cwd, ended sessions)
- `friendlyName` for UUID names vs human names
- `isGeneratedName` pattern matching
- `displayLabel` formatting

### LayoutTest.java  
- `encodeCommand` / `decodeCommand` roundtrip for paths with spaces, special chars
- `captureNode` format for single terminal, V2/H3 splits
- `splitTabDescs` with brackets containing commas
- `splitCommands` with escaped pipes
- `parseCommand` for zmx commands vs shell commands

### ConfigTest.java
- `appendProp` formatting (value vs default → commented out)
- Properties file roundtrip (write then read back)

### ShellDetectionTest.java
- `resolveExecutable` with controlled PATH

## Migration Strategy

1. Create directory structure
2. Create AppState.java with all fields moved out
3. Extract pure data types first (PtyTerminal, ShellDetection, FontManager, Themes) — no coupling issues
4. Extract ZmxManager — self-contained concern
5. Extract Config and Layout — coupled to AppState but clear boundaries
6. Extract Sidebar and MenuFactory — UI concerns
7. Extract TerminalManager — the biggest, most coupled piece
8. Extract WindowFactory — depends on everything
9. Rewrite JHostty.java as thin orchestrator
10. Update jhostty.java entry point with //SOURCES
11. Add tests
12. Verify `jbang jhostty.java` still works
