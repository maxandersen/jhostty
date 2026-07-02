///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS org.jetbrains.pty4j:pty4j:0.13.12
//DEPS org.slf4j:slf4j-nop:2.0.13
//DEPS io.smallrye.config:smallrye-config:3.12.4
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED --enable-native-access=javafx.graphics
//DEPS io.github.vlaaad:ghosttyfx:0.1.173

import java.lang.foreign.*;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import io.github.vlaaad.ghosttyfx.Shell;
import io.github.vlaaad.ghosttyfx.Terminal;
import io.github.vlaaad.ghosttyfx.TerminalState;
import io.github.vlaaad.ghosttyfx.TerminalTheme;
import io.github.vlaaad.ghosttyfx.TerminalView;

// (java.lang.foreign.* imported above for macOS app name)
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javafx.animation.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

public class jhostty extends Application {

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean IS_MAC = OS_NAME.contains("mac");
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final String SHORTCUT_SYMBOL = IS_MAC ? "\u2318" : "Ctrl+";
    private static final String SHIFT_SYMBOL = IS_MAC ? "\u21E7" : "Shift+";
    private static final double DEFAULT_SIZE = 15.0;
    private static final String ZOOM_KEY = "jhostty.fontSize";
    private static final String COMMAND_KEY = "jhostty.command";
    private static final String WINDOW_KEY = "jhostty.window";

    private static final Set<TerminalView> closingTerminals = ConcurrentHashMap.newKeySet();
    private static String currentFontFamily;
    private static String currentThemeName;
    private static TerminalTheme currentTheme;
    private static double baseFontSize = DEFAULT_SIZE;  // from config, what "reset zoom" returns to
    private static double currentZoom = DEFAULT_SIZE;    // actual size for new terminals, saved to state
    private static Path configDir;
    private static double savedWindowX = Double.NaN; // NaN = let OS decide
    private static double savedWindowY = Double.NaN;
    // Auto-detected defaults (for comparison when saving)
    private static String detectedFontFamily;
    private static List<String> detectedShellCommand;
    private static TerminalView activeTerminal;
    private static Path cwd;
    private static List<String> shellCommand;
    private static Path cssPath;
    private static String cssUrl;
    private static boolean debug;
    private static final List<Stage> windows = new ArrayList<>();
    private static final List<Menu> windowMenus = new ArrayList<>();
    private static boolean sidebarVisible = false;
    private static double sidebarDividerPos = 0.18; // default sidebar width ratio
    private static boolean shuttingDown = false;
    private static String savedLayout = null; // restored from state, consumed on first use
    private static final Map<Stage, TreeView<SidebarItem>> sidebarsByWindow = new ConcurrentHashMap<>();
    private static boolean sidebarRebuildScheduled = false;

    // --- Sidebar model item ---

    sealed interface SidebarItem {
        String label();
        record WindowItem(Stage stage, int index) implements SidebarItem {
            public String label() { return stage.getTitle() != null ? stage.getTitle() : "Window " + (index + 1); }
            @Override public String toString() { return label(); }
        }
        record TabItem(Tab tab, String title) implements SidebarItem {
            public String label() { return title != null && !title.isBlank() ? title : "Terminal"; }
            @Override public String toString() { return label(); }
        }
        record TerminalItem(TerminalView view, String title, List<String> command) implements SidebarItem {
            public String label() { return title != null && !title.isBlank() ? title : commandLabel(); }
            private String commandLabel() {
                if (command == null || command.isEmpty()) return "Terminal";
                var exe = Path.of(command.getFirst()).getFileName().toString();
                return command.size() > 1 ? exe + " " + String.join(" ", command.subList(1, command.size())) : exe;
            }
            @Override public String toString() { return label(); }
        }
        record SectionHeader(String title) implements SidebarItem {
            public String label() { return title; }
            @Override public String toString() { return label(); }
        }
        record ZmxSessionItem(ZmxSession session) implements SidebarItem {
            public String label() { return session.displayLabel(); }
            @Override public String toString() { return label(); }
        }
    }

    // --- zmx integration ---

    record ZmxSession(String name, int pid, int clients, String startDir, String cwd, String cmd, boolean ended, int exitCode) {
        String displayLabel() {
            var status = ended ? " \u2718" : (clients > 0 ? " (" + clients + ")" : "");
            return friendlyName() + status;
        }

        String friendlyName() {
            var displayName = name;
            // Derive a useful name from the directory for UUID-style names
            if (isGeneratedName()) {
                var dir = cwd != null && !cwd.isBlank() ? cwd : startDir;
                if (dir != null && !dir.isBlank()) {
                    var p = Path.of(dir);
                    // Use last 2 path components for context (e.g. "jbang/numid")
                    var home = Path.of(System.getProperty("user.home", ""));
                    if (p.equals(home)) {
                        displayName = "~";
                    } else if (p.startsWith(home) && p.getNameCount() > home.getNameCount()) {
                        var rel = home.relativize(p);
                        var count = rel.getNameCount();
                        displayName = count >= 2
                                ? rel.getName(count - 2) + "/" + rel.getName(count - 1)
                                : rel.getFileName().toString();
                    } else {
                        displayName = p.getFileName().toString();
                    }
                }
            }
            return displayName;
        }

        /** True if the name looks auto-generated (UUID pattern). */
        boolean isGeneratedName() {
            return name.matches(".*[0-9a-f]{8}-[0-9a-f]{4}-.*");
        }
    }

    private static List<ZmxSession> zmxSessions = List.of();
    private static volatile boolean zmxAvailable = false;
    private static Timeline zmxRefreshTimer;

    public static void main(String[] args) {
        // Ensure uncaught exceptions are visible
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("Uncaught exception in thread \"" + t.getName() + "\"");
            e.printStackTrace();
        });
        debug = List.of(args).contains("--debug");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> saveState(), "jhostty-shutdown"));
        Application.launch(jhostty.class, args);
    }

    private static void debug(String msg) {
        if (debug) System.err.println("[jhostty] " + msg);
    }

    @Override
    public void start(Stage _ignored) {
        cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        configDir = resolveConfigDir();

        // Detect defaults
        detectedFontFamily = defaultFontFamily();
        currentFontFamily = detectedFontFamily;
        currentThemeName = "Ghostty Default";
        currentTheme = themes().getFirst().theme();

        var shells = detectTerminals();
        detectedShellCommand = shells.isEmpty()
                ? List.of(IS_WINDOWS ? "cmd.exe" : "/bin/sh")
                : shells.getFirst().command();
        shellCommand = detectedShellCommand;

        // Load config (overrides defaults)
        loadConfig();
        System.err.println("[jhostty] shell: " + shellCommand);

        // Shared CSS
        try {
            cssPath = Files.createTempFile("jhostty", ".css");
            cssPath.toFile().deleteOnExit();
            cssUrl = cssPath.toUri().toString();
            writeCss();
        } catch (IOException _) {}

        Platform.setImplicitExit(false); // we handle quit explicitly
        initZmx();
        restoreLayout();
        if (IS_MAC) {
            Platform.runLater(() -> setMacAppName("jhostty"));
        }
    }

    private static void quit() {
        debug("quit: saving state before exit");
        // Save while all windows are still alive, then block further saves
        saveState();
        shuttingDown = true;
        if (zmxRefreshTimer != null) zmxRefreshTimer.stop();
        // Now close all windows
        var allWindows = new ArrayList<>(windows);
        for (var w : allWindows) {
            var tp = getTabPane(w);
            if (tp != null) closeAllTerminalsIn(tp);
            w.close();
        }
        Platform.exit();
    }

    private static Stage newWindow() {
        var stage = newWindowEmpty();
        if (stage == null) return null;
        var tabs = getTabPane(stage);
        if (tabs == null) return null;
        newTab(tabs);
        return stage;
    }

    // --- Menu Bar ---

    private static MenuBar createMenuBar(TabPane tabs) {
        // Shell menu
        var newWindowItem = new MenuItem("New Window");
        newWindowItem.setAccelerator(KeyCombination.keyCombination("Shortcut+N"));
        newWindowItem.setOnAction(_ -> newWindow());

        var newTabItem = new MenuItem("New Tab");
        newTabItem.setAccelerator(KeyCombination.keyCombination("Shortcut+T"));
        newTabItem.setOnAction(_ -> newTab(tabs));

        var splitH = new MenuItem("Split Horizontal");
        splitH.setAccelerator(KeyCombination.keyCombination("Shortcut+D"));
        splitH.setOnAction(_ -> splitActive(Orientation.VERTICAL));

        var splitV = new MenuItem("Split Vertical");
        splitV.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+D"));
        splitV.setOnAction(_ -> splitActive(Orientation.HORIZONTAL));

        var closeItem = new MenuItem("Close Terminal");
        closeItem.setAccelerator(KeyCombination.keyCombination("Shortcut+W"));
        closeItem.setOnAction(_ -> { if (activeTerminal != null) closeActive(findTabPane(activeTerminal), findStage(findTabPane(activeTerminal))); });

        // zmx submenu
        var zmxMenu = new Menu("Attach zmx Session");
        zmxMenu.setOnShowing(_ -> {
            zmxMenu.getItems().clear();
            if (!zmxAvailable) {
                zmxMenu.getItems().add(new MenuItem("(zmx not found)"));
            } else if (zmxSessions.isEmpty()) {
                zmxMenu.getItems().add(new MenuItem("(no sessions)"));
            } else {
                for (var s : zmxSessions) {
                    if (s.ended()) continue;
                    var item = new MenuItem(s.displayLabel());
                    item.setOnAction(_ -> attachZmxSession(s.name()));
                    zmxMenu.getItems().add(item);
                }
                if (zmxMenu.getItems().isEmpty()) zmxMenu.getItems().add(new MenuItem("(no live sessions)"));
            }
        });

        var shellMenu = new Menu("Shell", null, newWindowItem, newTabItem, splitH, splitV, new SeparatorMenuItem(), zmxMenu, new SeparatorMenuItem(), closeItem);

        // Window menu
        var windowMenu = new Menu("Window");
        windowMenus.add(windowMenu);

        // View menu
        var zoomIn = new MenuItem("Zoom In");
        zoomIn.setAccelerator(KeyCombination.keyCombination("Shortcut+Plus"));
        zoomIn.setOnAction(_ -> { if (activeTerminal != null) zoomTerminal(activeTerminal, 1); });

        var zoomOut = new MenuItem("Zoom Out");
        zoomOut.setAccelerator(KeyCombination.keyCombination("Shortcut+Minus"));
        zoomOut.setOnAction(_ -> { if (activeTerminal != null) zoomTerminal(activeTerminal, -1); });

        var zoomReset = new MenuItem("Reset Zoom");
        zoomReset.setAccelerator(KeyCombination.keyCombination("Shortcut+0"));
        zoomReset.setOnAction(_ -> { if (activeTerminal != null) setTerminalZoom(activeTerminal, baseFontSize); });

        // Theme submenu
        var themeToggle = new ToggleGroup();
        var themeMenu = new Menu("Theme");
        for (var t : themes()) {
            var item = new RadioMenuItem(t.label());
            item.setToggleGroup(themeToggle);
            if (t.theme().equals(currentTheme)) item.setSelected(true);
            item.setOnAction(_ -> { currentThemeName = t.label(); currentTheme = t.theme(); applyThemeToAll(); saveState(); });
            themeMenu.getItems().add(item);
        }

        // Font submenu
        var fontToggle = new ToggleGroup();
        var fontMenu = new Menu("Font");
        for (var f : detectFonts()) {
            var item = new RadioMenuItem(f.family());
            item.setToggleGroup(fontToggle);
            if (f.family().equals(currentFontFamily)) item.setSelected(true);
            item.setOnAction(_ -> { currentFontFamily = f.family(); applyFontToAll(); saveState(); });
            fontMenu.getItems().add(item);
        }

        var reloadConfig = new MenuItem("Reload Config");
        reloadConfig.setOnAction(_ -> {
            loadConfig();
            applyThemeToAll();
            // Apply new zoom/font to all terminals
            forEachTerminal(v -> {
                v.getProperties().put(ZOOM_KEY, currentZoom);
                v.setFont(resolveFont(currentFontFamily, currentZoom));
            });
            updateTitle();
        });

        var toggleSidebarItem = new MenuItem("Toggle Sidebar");
        toggleSidebarItem.setAccelerator(new KeyCodeCombination(KeyCode.BACK_SLASH, KeyCombination.SHORTCUT_DOWN));
        toggleSidebarItem.setOnAction(_ -> toggleSidebar());

        var viewMenu = new Menu("View", null, toggleSidebarItem, new SeparatorMenuItem(), zoomIn, zoomOut, zoomReset, new SeparatorMenuItem(), themeMenu, fontMenu, new SeparatorMenuItem(), reloadConfig);

        return new MenuBar(shellMenu, viewMenu, windowMenu);
    }

    // --- Right-click Context Menu ---

    private static ContextMenu createContextMenu(TerminalView view) {
        var sc = SHORTCUT_SYMBOL;
        var sh = SHIFT_SYMBOL;
        var newWindowItem = new MenuItem("New Window           " + sc + "N");
        newWindowItem.setOnAction(_ -> newWindow());

        var newTabItem = new MenuItem("New Tab                 " + sc + "T");
        newTabItem.setOnAction(_ -> newTab(findTabPane(view)));

        var splitH = new MenuItem("Split Horizontal    " + sc + "D");
        splitH.setOnAction(_ -> split(view, Orientation.VERTICAL));

        var splitV = new MenuItem("Split Vertical        " + sc + sh + "D");
        splitV.setOnAction(_ -> split(view, Orientation.HORIZONTAL));

        var zoomIn = new MenuItem("Zoom In                 " + sc + "+");
        zoomIn.setOnAction(_ -> zoomTerminal(view, 1));

        var zoomOut = new MenuItem("Zoom Out              " + sc + "\u2212");
        zoomOut.setOnAction(_ -> zoomTerminal(view, -1));

        var zoomReset = new MenuItem("Reset Zoom           " + sc + "0");
        zoomReset.setOnAction(_ -> setTerminalZoom(view, baseFontSize));

        var copy = new MenuItem("Copy                      " + sc + "C");
        copy.setOnAction(_ -> view.copySelection());

        var paste = new MenuItem("Paste                     " + sc + "V");
        paste.setOnAction(_ -> view.pasteClipboard());

        var toggleSidebar = new MenuItem("Toggle Sidebar       " + sc + "\\");
        toggleSidebar.setOnAction(_ -> toggleSidebar());

        return new ContextMenu(
                newWindowItem, newTabItem, splitH, splitV,
                new SeparatorMenuItem(),
                zoomIn, zoomOut, zoomReset,
                new SeparatorMenuItem(),
                copy, paste,
                new SeparatorMenuItem(),
                toggleSidebar);
    }

    // --- Tab Management ---

    private static void newTab(TabPane tabPane) {
        var view = createTerminal();
        if (view == null) return;
        var tab = new Tab();
        bindTabTitle(tab, view);
        tab.setContent(view);
        tab.setClosable(true);
        tab.setOnClosed(_ -> closeTerminalsIn(view));
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        Platform.runLater(jhostty::saveState);
    }

    private static void rebuildWindowMenus() {
        for (var menu : windowMenus) {
            menu.getItems().clear();
            for (int i = 0; i < windows.size(); i++) {
                var w = windows.get(i);
                var item = new MenuItem(w.getTitle() != null ? w.getTitle() : "Window " + (i + 1));
                item.setOnAction(_ -> { w.toFront(); w.requestFocus(); });
                menu.getItems().add(item);
            }
            if (menu.getItems().isEmpty()) menu.getItems().add(new MenuItem("(no windows)"));
        }
    }

    /** Find the TabPane that contains this view. */
    private static TabPane findTabPane(Node node) {
        var p = node.getParent();
        while (p != null) {
            if (p instanceof TabPane tp) return tp;
            p = p.getParent();
        }
        // Fallback: find via windows
        for (var w : windows) {
            var tp = getTabPane(w);
            if (tp != null) return tp;
        }
        return null;
    }

    /** Find the Stage for a TabPane. */
    private static Stage findStage(TabPane tp) {
        if (tp != null && tp.getScene() != null && tp.getScene().getWindow() instanceof Stage s) return s;
        return null;
    }

    /** Find the Stage for a TerminalView. */
    private static Stage findStageFor(TerminalView view) {
        var tp = findTabPane(view);
        return tp != null ? findStage(tp) : null;
    }

    private static void updateTabBarVisibility(TabPane tp) {
        var hide = tp.getTabs().size() <= 1;
        if (hide) { if (!tp.getStyleClass().contains("single-tab")) tp.getStyleClass().add("single-tab"); }
        else tp.getStyleClass().remove("single-tab");
    }

    // --- Sidebar ---

    private static void toggleSidebar() {
        sidebarVisible = !sidebarVisible;
        debug("toggleSidebar: visible=" + sidebarVisible);
        for (var w : windows) {
            if (w.getScene().getRoot() instanceof BorderPane bp && bp.getCenter() instanceof SplitPane sp) {
                var sidebar = sidebarsByWindow.get(w);
                if (sidebar == null) continue;
                if (sidebarVisible) {
                    showSidebarIn(sp, sidebar);
                } else {
                    hideSidebarIn(sp, sidebar);
                }
            }
        }
        if (sidebarVisible) rebuildAllSidebars();
    }

    /** Extract the TabPane from a window's scene graph (handles SplitPane wrapper). */
    private static TabPane getTabPane(Stage w) {
        if (w.getScene() == null) return null;
        if (!(w.getScene().getRoot() instanceof BorderPane bp)) return null;
        var center = bp.getCenter();
        if (center instanceof TabPane tp) return tp;
        if (center instanceof SplitPane sp) {
            for (var item : sp.getItems()) {
                if (item instanceof TabPane tp) return tp;
            }
        }
        return null;
    }

    /** Extract the content SplitPane from a window (the one holding sidebar + tabs). */
    private static SplitPane getContentSplit(Stage w) {
        if (w.getScene() == null) return null;
        if (w.getScene().getRoot() instanceof BorderPane bp && bp.getCenter() instanceof SplitPane sp) return sp;
        return null;
    }

    private static void showSidebarIn(SplitPane sp, TreeView<SidebarItem> sidebar) {
        if (!sp.getItems().contains(sidebar)) {
            sp.getItems().addFirst(sidebar);
            Platform.runLater(() -> sp.setDividerPositions(sidebarDividerPos));
        }
    }

    private static void hideSidebarIn(SplitPane sp, TreeView<SidebarItem> sidebar) {
        if (sp.getItems().contains(sidebar)) {
            // Save divider position before hiding
            if (sp.getDividers().size() > 0) {
                sidebarDividerPos = sp.getDividerPositions()[0];
            }
            sp.getItems().remove(sidebar);
        }
    }

    /** Find the TabPane that contains a given Tab. */
    private static TabPane findTabPaneForTab(Tab tab) {
        for (var w : windows) {
            var tp = getTabPane(w);
            if (tp != null && tp.getTabs().contains(tab)) return tp;
        }
        return null;
    }

    private static void rebuildAllSidebars() {
        if (!sidebarVisible) return;
        // Debounce: coalesce multiple rebuild requests into one
        if (sidebarRebuildScheduled) return;
        sidebarRebuildScheduled = true;
        Platform.runLater(() -> {
            sidebarRebuildScheduled = false;
            if (!sidebarVisible) return;
            for (var w : windows) {
                var sidebar = sidebarsByWindow.get(w);
                var tp = getTabPane(w);
                if (sidebar != null && tp != null) {
                    rebuildSidebar(sidebar, w, tp);
                }
            }
        });
    }

    private static void rebuildSidebar(TreeView<SidebarItem> sidebar, Stage stage, TabPane tabs) {
        // Clear selection before replacing the root to avoid JavaFX IndexOutOfBoundsException
        sidebar.getSelectionModel().clearSelection();

        var root = new TreeItem<SidebarItem>(new SidebarItem.WindowItem(stage, windows.indexOf(stage)));
        root.setExpanded(true);

        // Show all windows, expand their tabs
        for (int wi = 0; wi < windows.size(); wi++) {
            var w = windows.get(wi);
            var tp = getTabPane(w);
            if (tp == null) continue;
            var windowNode = new TreeItem<SidebarItem>(new SidebarItem.WindowItem(w, wi));
            windowNode.setExpanded(true);

            for (var tab : tp.getTabs()) {
                var tabTitle = tab.getText() != null ? tab.getText() : "Terminal";
                var tabNode = new TreeItem<SidebarItem>(new SidebarItem.TabItem(tab, tabTitle));
                tabNode.setExpanded(true);
                addTerminalNodes(tabNode, tab.getContent());
                windowNode.getChildren().add(tabNode);
            }
            root.getChildren().add(windowNode);
        }

        // If only one window, flatten: make tabs direct children of root
        TreeItem<SidebarItem> finalRoot;
        if (root.getChildren().size() == 1) {
            var onlyWindow = root.getChildren().getFirst();
            finalRoot = new TreeItem<>(onlyWindow.getValue());
            finalRoot.setExpanded(true);
            finalRoot.getChildren().addAll(onlyWindow.getChildren());
        } else {
            finalRoot = root;
        }

        // Add zmx sessions section
        if (zmxAvailable && !zmxSessions.isEmpty()) {
            var zmxHeader = new TreeItem<SidebarItem>(new SidebarItem.SectionHeader("zmx sessions"));
            zmxHeader.setExpanded(true);
            for (var session : zmxSessions) {
                if (!session.ended()) { // only show live sessions
                    zmxHeader.getChildren().add(new TreeItem<>(new SidebarItem.ZmxSessionItem(session)));
                }
            }
            if (!zmxHeader.getChildren().isEmpty()) {
                finalRoot.getChildren().add(zmxHeader);
            }
        }

        sidebar.setRoot(finalRoot);
    }

    /** Focus the first TerminalView found in a node tree. */
    private static void focusFirstTerminal(Node node) {
        if (node instanceof TerminalView v) { v.requestFocus(); }
        else if (node instanceof SplitPane sp && !sp.getItems().isEmpty()) { focusFirstTerminal(sp.getItems().getFirst()); }
    }

    private static void addTerminalNodes(TreeItem<SidebarItem> parent, Node node) {
        if (node instanceof TerminalView v) {
            parent.getChildren().add(new TreeItem<>(new SidebarItem.TerminalItem(v, v.getTitle(), getTerminalCommand(v))));
        } else if (node instanceof SplitPane sp) {
            for (var item : sp.getItems()) addTerminalNodes(parent, item);
        }
    }

    // --- Split Management ---

    private static void splitActive(Orientation orientation) {
        if (activeTerminal != null) split(activeTerminal, orientation);
    }

    private static void split(TerminalView existing, Orientation orientation) {
        var newView = createTerminal();
        if (newView == null) return;

        var sp = findParentSplitPane(existing);
        if (sp != null) {
            // Already in a split — find the existing view's index and add next to it
            var items = sp.getItems();
            int idx = items.indexOf(existing);
            if (idx >= 0) {
                if (sp.getOrientation() == orientation) {
                    // Same orientation — just add another item
                    items.add(idx + 1, newView);
                    evenDividers(sp);
                } else {
                    // Different orientation — wrap in a new nested SplitPane
                    var nested = new SplitPane(existing, newView);
                    nested.setOrientation(orientation);
                    items.set(idx, nested);
                    evenDividers(sp);
                }
            }
        } else {
            // Currently the sole content of a tab — wrap in SplitPane
            var tab = findTab(existing);
            if (tab != null) {
                var newSp = new SplitPane(existing, newView);
                newSp.setOrientation(orientation);
                tab.setContent(newSp);
            }
        }
        Platform.runLater(newView::requestFocus);
    }

    private static void evenDividers(SplitPane sp) {
        int n = sp.getItems().size();
        if (n <= 1) return;
        double[] positions = new double[n - 1];
        for (int i = 0; i < positions.length; i++) positions[i] = (i + 1.0) / n;
        Platform.runLater(() -> sp.setDividerPositions(positions));
    }

    // --- Close Management ---

    private static void closeActive(TabPane tabPane, Stage stage) {
        if (activeTerminal != null) removeTerminal(activeTerminal, tabPane, stage);
    }

    private static void removeTerminal(TerminalView view, TabPane tabPane, Stage stage) {
        if (!closingTerminals.add(view)) return; // guard against double-removal
        Thread.ofVirtual().name("jhostty-close").start(view::close);

        if (tabPane == null) tabPane = findTabPane(view);
        if (stage == null && tabPane != null) stage = findStage(tabPane);

        var sp = findParentSplitPane(view);
        if (sp != null) {
            sp.getItems().remove(view);
            if (sp.getItems().size() == 1) {
                var remaining = sp.getItems().getFirst();
                sp.getItems().clear();
                var gsp = findParentSplitPane(sp);
                if (gsp != null) {
                    int idx = gsp.getItems().indexOf(sp);
                    if (idx >= 0) gsp.getItems().set(idx, remaining);
                    evenDividers(gsp);
                } else {
                    var tab = findTab(sp);
                    if (tab != null) tab.setContent(remaining);
                }
                if (remaining instanceof TerminalView tv) tv.requestFocus();
            } else if (sp.getItems().isEmpty()) {
                var tab = findTab(sp);
                if (tab != null && tabPane != null) tabPane.getTabs().remove(tab);
            } else {
                evenDividers(sp);
                if (sp.getItems().getFirst() instanceof TerminalView tv) tv.requestFocus();
            }
        } else {
            var tab = findTab(view);
            if (tab != null && tabPane != null) tabPane.getTabs().remove(tab);
        }

        if (tabPane != null && tabPane.getTabs().isEmpty() && stage != null) stage.close();
    }

    private static Tab findTab(Node node) {
        var tp = findTabPane(node);
        if (tp == null) return null;
        for (var tab : tp.getTabs()) {
            if (tab.getContent() == node) return tab;
            if (containsNode(tab.getContent(), node)) return tab;
        }
        return null;
    }

    private static boolean containsNode(Node container, Node target) {
        if (container == target) return true;
        if (container instanceof SplitPane sp) {
            for (var item : sp.getItems()) {
                if (containsNode(item, target)) return true;
            }
        }
        return false;
    }

    // --- Terminal Factory ---

    /** Create a terminal running the default shell. */
    private static TerminalView createTerminal() {
        return createTerminal(shellCommand, System.getenv());
    }

    private static TerminalView createTerminal(List<String> command) {
        return createTerminal(command, System.getenv());
    }

    private static TerminalView createTerminal(List<String> command, Map<String, String> env) {
        try {
            var view = new TerminalView((columns, rows) -> {
                var launcher = Shell.integrate(command, env);
                return new PtyTerminal(launcher.command(), cwd, launcher.environment(), columns, rows);
            });
            view.getProperties().put(ZOOM_KEY, currentZoom);
            view.getProperties().put(COMMAND_KEY, List.copyOf(command));
            view.setFont(resolveFont(currentFontFamily, currentZoom));
            view.setTheme(currentTheme);

            // Track active terminal on focus
            view.focusedProperty().addListener((_, _, focused) -> {
                if (focused) {
                    activeTerminal = view;
                    var stg = findStageFor(view);
                    if (stg != null) { stg.setTitle(view.getTitle() != null ? view.getTitle() : "jhostty"); rebuildWindowMenus(); }
                    rebuildAllSidebars();
                }
            });
            view.titleProperty().addListener((_, _, title) -> {
                if (view == activeTerminal) {
                    var stg = findStageFor(view);
                    if (stg != null) { stg.setTitle(title != null ? title : "jhostty"); rebuildWindowMenus(); }
                }
                rebuildAllSidebars();
            });

            // Auto-remove on process exit
            view.terminalStateProperty().addListener((_, _, state) -> {
                if (!(state instanceof TerminalState.Running)) {
                    Platform.runLater(() -> removeTerminal(view, null, null));
                }
            });

            // Right-click context menu
            var ctx = createContextMenu(view);
            view.setOnContextMenuRequested(e -> ctx.show(view, e.getScreenX(), e.getScreenY()));

            // Drag-and-drop: accept text, files, URLs
            view.setOnDragOver(e -> {
                var db = e.getDragboard();
                if (db.hasString() || db.hasFiles() || db.hasUrl()) {
                    e.acceptTransferModes(TransferMode.COPY);
                }
                e.consume();
            });
            view.setOnDragDropped(e -> {
                var db = e.getDragboard();
                if (db.hasFiles()) {
                    var paths = db.getFiles().stream()
                            .map(f -> quotePath(f.getAbsolutePath()))
                            .toList();
                    view.sendText(String.join(" ", paths));
                } else if (db.hasString()) {
                    view.sendText(db.getString());
                } else if (db.hasUrl()) {
                    view.sendText(db.getUrl());
                }
                e.setDropCompleted(true);
                e.consume();
            });

            return view;
        } catch (RuntimeException e) {
            var alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("jhostty");
            alert.setHeaderText("Failed to start terminal");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
            return null;
        }
    }

    // --- Apply settings globally ---

    /** Get the command a terminal was started with. */
    @SuppressWarnings("unchecked")
    private static List<String> getTerminalCommand(TerminalView v) {
        var cmd = v.getProperties().get(COMMAND_KEY);
        return cmd instanceof List<?> l ? (List<String>) l : shellCommand;
    }

    /** Bind a tab's text to a terminal's title, with a fallback based on the command. */
    private static void bindTabTitle(Tab tab, TerminalView view) {
        var cmd = getTerminalCommand(view);
        var fallback = commandBaseName(cmd);
        // Set initial title
        var title = view.getTitle();
        tab.setText(title != null && !title.isBlank() ? title : fallback);
        // Update when title changes
        view.titleProperty().addListener((_, _, newTitle) -> {
            tab.setText(newTitle != null && !newTitle.isBlank() ? newTitle : fallback);
        });
    }

    /** Derive a short display name from a command. */
    private static String commandBaseName(List<String> cmd) {
        if (cmd == null || cmd.isEmpty()) return "Terminal";
        var exe = Path.of(cmd.getFirst()).getFileName().toString();
        if (exe.equals("zmx") && cmd.size() >= 3 && cmd.get(1).equals("attach")) {
            var sessionName = cmd.get(2);
            // Try to find a friendly name from the cached zmx sessions
            for (var s : zmxSessions) {
                if (s.name().equals(sessionName)) return "zmx: " + s.friendlyName();
            }
            return "zmx: " + sessionName;
        }
        return cmd.size() > 1 ? exe + " " + String.join(" ", cmd.subList(1, cmd.size())) : exe;
    }

    private static double getTerminalSize(TerminalView v) {
        var s = v.getProperties().get(ZOOM_KEY);
        return s instanceof Double d ? d : currentZoom;
    }

    private static void zoomTerminal(TerminalView v, double delta) {
        var oldSize = getTerminalSize(v);
        var newSize = Math.max(8, oldSize + delta);
        debug("zoom: " + oldSize + " -> " + newSize + " (delta=" + delta + ")");
        setTerminalZoom(v, newSize);
    }

    private static void setTerminalZoom(TerminalView v, double size) {
        v.getProperties().put(ZOOM_KEY, size);
        v.setFont(resolveFont(currentFontFamily, size));
        currentZoom = size;
        updateTitle();
        saveState();
    }

    private static void applyFontToAll() {
        forEachTerminal(v -> v.setFont(resolveFont(currentFontFamily, getTerminalSize(v))));
        updateTitle();
        updateTitle();
    }

    private static void updateTitle() {
        if (activeTerminal == null) return;
        var stg = findStageFor(activeTerminal);
        if (stg == null) return;
        var base = activeTerminal.getTitle() != null ? activeTerminal.getTitle() : "jhostty";
        var pct = Math.round(getTerminalSize(activeTerminal) / baseFontSize * 100);
        stg.setTitle(pct == 100 ? base : base + " (" + pct + "%)");
    }

    private static void applyThemeToAll() {
        forEachTerminal(v -> v.setTheme(currentTheme));
        writeCss();
        // Force CSS reload on all scenes
        for (var w : windows) {
            var sheets = w.getScene().getStylesheets();
            if (sheets.contains(cssUrl)) { sheets.remove(cssUrl); sheets.add(cssUrl); }
        }
    }

    private static String colorToCss(Color c) {
        return String.format("rgba(%d,%d,%d,%.2f)",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255), c.getOpacity());
    }

    private static void writeCss() {
        if (cssPath == null) return;
        var bg = currentTheme.background();
        var fg = currentTheme.foreground();
        // Determine if theme is dark or light
        var lum = bg.getRed() * 0.299 + bg.getGreen() * 0.587 + bg.getBlue() * 0.114;
        var dark = lum < 0.5;
        // Menu bg: slightly offset from terminal bg
        var menuBg = dark ? bg.brighter().brighter() : bg.darker();
        var menuBgCss = String.format("rgba(%d,%d,%d,0.95)",
                (int)(menuBg.getRed()*255), (int)(menuBg.getGreen()*255), (int)(menuBg.getBlue()*255));
        var fgCss = colorToCss(fg);
        var borderCss = dark ? "rgba(255,255,255,0.12)" : "rgba(0,0,0,0.15)";
        var sepCss = dark ? "rgba(255,255,255,0.1)" : "rgba(0,0,0,0.1)";
        var selBg = currentTheme.selectionColor();
        var selCss = String.format("rgba(%d,%d,%d,0.8)",
                (int)(selBg.getRed()*255), (int)(selBg.getGreen()*255), (int)(selBg.getBlue()*255));
        var selText = dark ? "white" : "black";
        var dividerCss = dark ? "#555555" : "#bbbbbb";
        try {
            Files.writeString(cssPath, """
                    .single-tab > .tab-header-area {
                        -fx-max-height: 0;
                        -fx-pref-height: 0;
                        -fx-min-height: 0;
                        visibility: hidden;
                    }
                    .split-pane > .split-pane-divider {
                        -fx-background-color: %s;
                        -fx-padding: 1;
                    }
                    .context-menu {
                        -fx-background-color: %s;
                        -fx-background-radius: 8;
                        -fx-border-radius: 8;
                        -fx-border-color: %s;
                        -fx-border-width: 0.5;
                        -fx-padding: 4 0 4 0;
                        -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 12, 0, 0, 4);
                    }
                    .context-menu .menu-item {
                        -fx-padding: 4 16 4 16;
                    }
                    .context-menu .menu-item .label {
                        -fx-text-fill: %s;
                        -fx-font-size: 13;
                    }
                    .context-menu .menu-item:focused {
                        -fx-background-color: %s;
                        -fx-background-radius: 4;
                        -fx-background-insets: 0 4 0 4;
                    }
                    .context-menu .menu-item:focused .label {
                        -fx-text-fill: %s;
                    }
                    .context-menu .separator {
                        -fx-padding: 4 8 4 8;
                    }
                    .context-menu .separator .line {
                        -fx-border-color: %s;
                        -fx-border-width: 0.5 0 0 0;
                    }
                    .jhostty-content-split > .split-pane-divider {
                        -fx-background-color: %s;
                        -fx-padding: 0 1 0 1;
                    }
                    .jhostty-sidebar {
                        -fx-background-color: %s;
                    }
                    .jhostty-sidebar .tree-cell {
                        -fx-text-fill: %s;
                        -fx-background-color: transparent;
                        -fx-font-size: 12;
                        -fx-padding: 3 8 3 4;
                    }
                    .jhostty-sidebar .tree-cell:selected {
                        -fx-background-color: %s;
                        -fx-text-fill: %s;
                    }
                    .jhostty-sidebar .tree-cell:empty {
                        -fx-background-color: transparent;
                    }
                    .jhostty-sidebar .tree-disclosure-node .arrow {
                        -fx-background-color: %s;
                    }
                    """.formatted(dividerCss, menuBgCss, borderCss, fgCss, selCss, selText, sepCss,
                            borderCss, menuBgCss, fgCss, selCss, selText, fgCss));
        } catch (IOException _) {}
    }

    private static void forEachTerminal(java.util.function.Consumer<TerminalView> action) {
        for (var w : windows) {
            var tp = getTabPane(w);
            if (tp != null) {
                for (var tab : tp.getTabs()) forEachTerminalIn(tab.getContent(), action);
            }
        }
    }

    /** Walk up the scene graph to find the SplitPane containing this node. */
    private static SplitPane findParentSplitPane(Node node) {
        var p = node.getParent();
        while (p != null) {
            if (p instanceof SplitPane sp && sp.getItems().contains(node)
                    && !sp.getStyleClass().contains("jhostty-content-split")) return sp;
            // SplitPane wraps items in SplitPaneSkin$Content — check if parent's parent is SplitPane
            if (p.getParent() instanceof SplitPane sp && sp.getItems().contains(node)
                    && !sp.getStyleClass().contains("jhostty-content-split")) return sp;
            node = p;
            p = p.getParent();
        }
        return null;
    }

    /** Find the TerminalView under the given screen coordinates, or null. */
    private static TerminalView terminalAt(TabPane tabPane, double screenX, double screenY) {
        for (var tab : tabPane.getTabs()) {
            var hit = terminalAtIn(tab.getContent(), screenX, screenY);
            if (hit != null) return hit;
        }
        return null;
    }

    private static TerminalView terminalAtIn(Node node, double screenX, double screenY) {
        if (node instanceof TerminalView v) {
            var bounds = v.localToScreen(v.getBoundsInLocal());
            return bounds != null && bounds.contains(screenX, screenY) ? v : null;
        }
        if (node instanceof SplitPane sp) {
            for (var item : sp.getItems()) {
                var hit = terminalAtIn(item, screenX, screenY);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static void forEachTerminalIn(Node node, java.util.function.Consumer<TerminalView> action) {
        if (node instanceof TerminalView v) action.accept(v);
        else if (node instanceof SplitPane sp) sp.getItems().forEach(n -> forEachTerminalIn(n, action));
    }

    private static void closeAllTerminalsIn(TabPane tp) {
        for (var tab : tp.getTabs()) closeTerminalsIn(tab.getContent());
    }

    private static void closeTerminalsIn(Node node) {
        forEachTerminalIn(node, v -> Thread.ofVirtual().name("jhostty-close").start(v::close));
    }

    // --- PTY Terminal ---

    static final class PtyTerminal implements Terminal {
        private final PtyProcess process;
        PtyTerminal(List<String> command, Path cwd, Map<String, String> env, int columns, int rows) throws IOException {
            process = (PtyProcess) new PtyProcessBuilder()
                    .setCommand(command.toArray(String[]::new))
                    .setConsole(false)
                    .setRedirectErrorStream(true)
                    .setDirectory(cwd.toString())
                    .setEnvironment(env)
                    .setInitialColumns(columns)
                    .setInitialRows(rows)
                    .setUseWinConPty(true)
                    .start();
        }

        @Override public InputStream output() { return process.getInputStream(); }
        @Override public OutputStream input() { return process.getOutputStream(); }
        @Override public void resize(int columns, int rows) { process.setWinSize(new WinSize(columns, rows)); }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // --- Shell detection ---

    private static List<ShellOption> detectTerminals() {
        return IS_WINDOWS ? detectWindowsShells() : detectUnixShells();
    }

    private static List<ShellOption> detectWindowsShells() {
        var result = new ArrayList<ShellOption>();
        var seen = new LinkedHashSet<Path>();
        addShell(result, seen, "PowerShell", "pwsh.exe");
        addShell(result, seen, "Windows PowerShell", "powershell.exe");
        addShell(result, seen, "Command Prompt", "cmd.exe");
        return result;
    }

    private static List<ShellOption> detectUnixShells() {
        var result = new ArrayList<ShellOption>();
        var seen = new LinkedHashSet<Path>();
        var shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank()) {
            addShell(result, seen, Path.of(shell).getFileName().toString(), shell);
        }
        addShell(result, seen, "bash", "bash");
        addShell(result, seen, "zsh", "zsh");
        addShell(result, seen, "fish", "fish");
        addShell(result, seen, "sh", "sh");
        return result;
    }

    private static void addShell(List<ShellOption> list, LinkedHashSet<Path> seen, String label, String cmd) {
        var path = resolveExecutable(cmd);
        if (path != null && seen.add(path)) {
            list.add(new ShellOption(label, List.of(path.toString())));
        }
    }

    private static Path resolveExecutable(String candidate) {
        var p = Path.of(candidate);
        if (p.isAbsolute()) return Files.isRegularFile(p) ? p : null;
        for (var entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            var resolved = Path.of(entry).resolve(candidate);
            if (Files.isExecutable(resolved)) return resolved.toAbsolutePath().normalize();
        }
        return null;
    }

    record ShellOption(String label, List<String> command) {
        @Override public String toString() { return label; }
    }

    // --- Themes ---

    private static List<ThemeOption> themes() {
        return List.of(
            new ThemeOption("Ghostty Default", TerminalTheme.defaults()),
            darkTheme("Catppuccin Mocha", "#1e1e2e", "#cdd6f4", List.of(
                "#45475a", "#f38ba8", "#a6e3a1", "#f9e2af",
                "#89b4fa", "#f5c2e7", "#94e2d5", "#bac2de",
                "#585b70", "#f38ba8", "#a6e3a1", "#f9e2af",
                "#89b4fa", "#f5c2e7", "#94e2d5", "#a6adc8"), "#313244", "#cdd6f4"),
            darkTheme("Dracula", "#282a36", "#f8f8f2", List.of(
                "#000000", "#ff5555", "#50fa7b", "#f1fa8c",
                "#bd93f9", "#ff79c6", "#8be9fd", "#bbbbbb",
                "#555555", "#ff5555", "#50fa7b", "#f1fa8c",
                "#bd93f9", "#ff79c6", "#8be9fd", "#ffffff"), "#44475a", "#f8f8f2"),
            darkTheme("Nord", "#2e3440", "#d8dee9", List.of(
                "#3b4252", "#bf616a", "#a3be8c", "#ebcb8b",
                "#81a1c1", "#b48ead", "#88c0d0", "#e5e9f0",
                "#4c566a", "#bf616a", "#a3be8c", "#ebcb8b",
                "#81a1c1", "#b48ead", "#8fbcbb", "#eceff4"), "#434c5e", "#eceff4"),
            darkTheme("Tokyo Night", "#1a1b26", "#c0caf5", List.of(
                "#15161e", "#f7768e", "#9ece6a", "#e0af68",
                "#7aa2f7", "#bb9af7", "#7dcfff", "#a9b1d6",
                "#414868", "#f7768e", "#9ece6a", "#e0af68",
                "#7aa2f7", "#bb9af7", "#7dcfff", "#c0caf5"), "#28344a", "#c0caf5"),
            darkTheme("Gruvbox Dark", "#282828", "#ebdbb2", List.of(
                "#282828", "#cc241d", "#98971a", "#d79921",
                "#458588", "#b16286", "#689d6a", "#a89984",
                "#928374", "#fb4934", "#b8bb26", "#fabd2f",
                "#83a598", "#d3869b", "#8ec07c", "#ebdbb2"), "#504945", "#ebdbb2"),
            darkTheme("Monokai", "#272822", "#f8f8f2", List.of(
                "#272822", "#f92672", "#a6e22e", "#f4bf75",
                "#66d9ef", "#ae81ff", "#a1efe4", "#f8f8f2",
                "#75715e", "#f92672", "#a6e22e", "#f4bf75",
                "#66d9ef", "#ae81ff", "#a1efe4", "#f9f8f5"), "#49483e", "#f8f8f2"),
            darkTheme("Solarized Dark", "#002b36", "#839496", List.of(
                "#073642", "#dc322f", "#859900", "#b58900",
                "#268bd2", "#d33682", "#2aa198", "#eee8d5",
                "#002b36", "#cb4b16", "#586e75", "#657b83",
                "#839496", "#6c71c4", "#93a1a1", "#fdf6e3"), "#073642", "#93a1a1"),
            lightTheme("Solarized Light", "#fdf6e3", "#657b83", List.of(
                "#073642", "#dc322f", "#859900", "#b58900",
                "#268bd2", "#d33682", "#2aa198", "#eee8d5",
                "#002b36", "#cb4b16", "#586e75", "#657b83",
                "#839496", "#6c71c4", "#93a1a1", "#fdf6e3"), "#eee8d5", "#586e75"),
            lightTheme("GitHub Light", "#ffffff", "#24292f", List.of(
                "#24292f", "#cf222e", "#116329", "#4d2d00",
                "#0969da", "#8250df", "#1b7c83", "#6e7781",
                "#57606a", "#a40e26", "#1a7f37", "#633c01",
                "#218bff", "#a475f9", "#3192aa", "#8c959f"), "#d0d7de", "#24292f")
        );
    }

    private static ThemeOption darkTheme(String name, String bg, String fg, List<String> palette, String sel, String cursorText) {
        return lightTheme(name, bg, fg, palette, sel, cursorText);
    }

    private static ThemeOption lightTheme(String name, String bg, String fg, List<String> palette, String sel, String cursorText) {
        var fgColor = Color.web(fg);
        return new ThemeOption(name, new TerminalTheme(
                Color.web(bg), fgColor,
                palette.stream().map(Color::web).toList(),
                fgColor, Color.web(cursorText), Color.web(sel), fgColor, 0.5,
                fgColor.deriveColor(0, 1, 1, 0.45),
                fgColor.deriveColor(0, 1, 1, 0.18),
                fgColor.deriveColor(0, 1, 1, 0.35)));
    }

    // --- Config (Pkl) ---

    private static Path resolveConfigDir() {
        var xdg = System.getenv("XDG_CONFIG_HOME");
        var base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("jhostty");
    }

    private static void loadConfig() {
        var stateFile = configDir.resolve("jhostty-state.properties");
        var userFile = configDir.resolve("jhostty.properties");
        var hasState = Files.isRegularFile(stateFile);
        var hasUser = Files.isRegularFile(userFile);
        if (!hasState && !hasUser) {
            debug("no config found in " + configDir);
            return;
        }
        try {
            var builder = new io.smallrye.config.SmallRyeConfigBuilder();
            // State file at lower ordinal, user file at higher (wins)
            if (hasState) {
                debug("loading state: " + stateFile);
                builder.withSources(new io.smallrye.config.PropertiesConfigSource(stateFile.toUri().toURL(), 100));
            }
            if (hasUser) {
                debug("loading user config: " + userFile);
                builder.withSources(new io.smallrye.config.PropertiesConfigSource(userFile.toUri().toURL(), 200));
            }
            var config = builder.build();

            // Reset to defaults, then apply what's in config
            currentThemeName = "Ghostty Default";
            currentFontFamily = detectedFontFamily;
            baseFontSize = DEFAULT_SIZE;
            currentZoom = DEFAULT_SIZE;
            shellCommand = detectedShellCommand;
            savedWindowX = Double.NaN;
            savedWindowY = Double.NaN;
            sidebarVisible = false;
            savedLayout = null;

            config.getOptionalValue("theme", String.class).filter(s -> !s.isBlank()).ifPresent(v -> currentThemeName = v);
            config.getOptionalValue("font", String.class).filter(s -> !s.isBlank()).ifPresent(v -> currentFontFamily = v);
            config.getOptionalValue("font-size", Double.class).ifPresent(v -> { baseFontSize = v; currentZoom = v; });
            config.getOptionalValue("zoom", Double.class).ifPresent(v -> currentZoom = v);
            config.getOptionalValue("shell", String.class).filter(s -> !s.isBlank()).ifPresent(v -> shellCommand = List.of(v.split("\\s+")));
            config.getOptionalValue("window-x", Double.class).ifPresent(v -> savedWindowX = v);
            config.getOptionalValue("window-y", Double.class).ifPresent(v -> savedWindowY = v);
            config.getOptionalValue("sidebar", Boolean.class).ifPresent(v -> sidebarVisible = v);
            config.getOptionalValue("sidebar-width", Double.class).ifPresent(v -> sidebarDividerPos = v);
            config.getOptionalValue("layout", String.class).filter(s -> !s.isBlank()).ifPresent(v -> savedLayout = v);

            // Resolve theme name to TerminalTheme
            for (var t : themes()) {
                if (t.label().equals(currentThemeName)) {
                    currentTheme = t.theme();
                    break;
                }
            }
            debug("config loaded: theme=" + currentThemeName + " font=" + currentFontFamily + " fontSize=" + baseFontSize + " zoom=" + currentZoom);
        } catch (Exception e) {
            System.err.println("[jhostty] failed to load config: " + e.getMessage());
        }
    }

    private static void appendProp(StringBuilder sb, String key, String current, String defaultVal) {
        if (current != null && !current.equals(defaultVal)) {
            sb.append(key).append('=').append(current).append('\n');
        } else {
            sb.append("# ").append(key).append('=').append('\n');
        }
    }

    private static void appendProp(StringBuilder sb, String key, double current, double defaultVal) {
        if (current != defaultVal) {
            sb.append(key).append('=').append(current).append('\n');
        } else {
            sb.append("# ").append(key).append('=').append('\n');
        }
    }

    private static void appendProp(StringBuilder sb, String key, double current) {
        if (!Double.isNaN(current)) {
            sb.append(key).append('=').append(Math.round(current)).append('\n');
        } else {
            sb.append("# ").append(key).append('=').append('\n');
        }
    }



    private static void saveState() {
        if (configDir == null || shuttingDown) return;
        // Capture position from first open window if available
        if (!windows.isEmpty()) {
            var w = windows.getFirst();
            savedWindowX = w.getX();
            savedWindowY = w.getY();
        }
        try {
            Files.createDirectories(configDir);
            var stateFile = configDir.resolve("jhostty-state.properties");
            var sb = new StringBuilder();
            sb.append("# Auto-saved by jhostty \u2014 do not edit.\n");
            sb.append("# Create jhostty.properties to override (higher priority).\n");
            sb.append("# Omit a key or leave blank to auto-detect / use default.\n");
            appendProp(sb, "theme", currentThemeName, "Ghostty Default");
            appendProp(sb, "font", currentFontFamily, detectedFontFamily);
            appendProp(sb, "font-size", baseFontSize, DEFAULT_SIZE);
            appendProp(sb, "zoom", currentZoom, baseFontSize);
            appendProp(sb, "shell", String.join(" ", shellCommand), String.join(" ", detectedShellCommand));
            // window-width and window-height are user-config only, not auto-saved
            appendProp(sb, "window-x", savedWindowX);
            appendProp(sb, "window-y", savedWindowY);
            sb.append("sidebar=").append(sidebarVisible).append('\n');
            // Capture current divider position from first visible sidebar
            for (var w : windows) {
                var sp = getContentSplit(w);
                if (sp != null && sp.getDividers().size() > 0 && sidebarVisible) {
                    sidebarDividerPos = sp.getDividerPositions()[0];
                    break;
                }
            }
            sb.append("sidebar-width=").append(String.format("%.3f", sidebarDividerPos)).append('\n');
            captureLayout(); // update lastGoodLayout
            sb.append("layout=").append(lastGoodLayout != null ? lastGoodLayout : "").append('\n');
            // Atomic write
            var tmp = Files.createTempFile(configDir, "jhostty-state", ".tmp");
            Files.writeString(tmp, sb.toString());
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            debug("state saved: " + stateFile);
        } catch (IOException e) {
            System.err.println("[jhostty] failed to save state: " + e.getMessage());
        }
    }
    // --- Layout save/restore ---
    // Format: windows separated by ";", tabs within a window by ","
    // Each tab is a split descriptor: "1" (single), "V2" (vertical 2-pane), "H3" (horizontal 3-pane)
    // Followed by commands: "V2[/bin/fish|/usr/bin/top]" or "1[/bin/fish]" for single
    // Nested splits not yet encoded — flattened to the outer orientation.

    private static String lastGoodLayout = null; // last non-empty layout captured

    private static String captureLayout() {
        var sb = new StringBuilder();
        for (int wi = 0; wi < windows.size(); wi++) {
            if (wi > 0) sb.append(';');
            var w = windows.get(wi);
            if (w.getScene() == null) { sb.append("1"); continue; }
            var tp = getTabPane(w);
            if (tp == null) { sb.append("1"); continue; }
            for (int ti = 0; ti < tp.getTabs().size(); ti++) {
                if (ti > 0) sb.append(',');
                captureNode(sb, tp.getTabs().get(ti).getContent());
            }
        }
        var result = sb.toString();
        if (!result.isBlank()) lastGoodLayout = result;
        debug("captureLayout: " + result);
        return result;
    }

    private static void captureNode(StringBuilder sb, Node node) {
        if (node instanceof TerminalView v) {
            sb.append("1[").append(encodeCommand(getTerminalCommand(v))).append(']');
        } else if (node instanceof SplitPane sp) {
            var orient = sp.getOrientation() == Orientation.VERTICAL ? "V" : "H";
            var terminals = new ArrayList<TerminalView>();
            collectTerminals(sp, terminals);
            sb.append(orient).append(terminals.size());
            sb.append('[');
            for (int i = 0; i < terminals.size(); i++) {
                if (i > 0) sb.append('|');
                sb.append(encodeCommand(getTerminalCommand(terminals.get(i))));
            }
            sb.append(']');
        } else {
            sb.append("1");
        }
    }

    private static void collectTerminals(Node node, List<TerminalView> out) {
        if (node instanceof TerminalView v) out.add(v);
        else if (node instanceof SplitPane sp) sp.getItems().forEach(n -> collectTerminals(n, out));
    }

    private static String encodeCommand(List<String> cmd) {
        // Encode spaces and special chars: join with \x00, escape | and other delimiters
        return String.join(" ", cmd)
                .replace("\\", "\\\\")
                .replace("|", "\\p")
                .replace(",", "\\c")
                .replace(";", "\\s")
                .replace("[", "\\o")
                .replace("]", "\\e");
    }

    private static String decodeCommand(String encoded) {
        return encoded
                .replace("\\e", "]")
                .replace("\\o", "[")
                .replace("\\s", ";")
                .replace("\\c", ",")
                .replace("\\p", "|")
                .replace("\\\\", "\\");
    }

    private static List<String> parseCommand(String encoded) {
        var decoded = decodeCommand(encoded);
        return decoded.isBlank() ? shellCommand : List.of(decoded.split(" "));
    }

    /** Create a terminal with zmx env vars cleaned if the command is zmx. */
    private static TerminalView createTerminalClean(List<String> cmd) {
        if (cmd.size() >= 2 && Path.of(cmd.getFirst()).getFileName().toString().equals("zmx")) {
            var env = new java.util.HashMap<>(System.getenv());
            env.remove("ZMX_SESSION");
            env.remove("ZMX_SESSION_PREFIX");
            env.remove("ZMX_DIR");
            return createTerminal(cmd, env);
        }
        return createTerminal(cmd);
    }

    private static void restoreLayout() {
        var layout = savedLayout;
        savedLayout = null;
        if (layout == null || layout.isBlank()) {
            newWindow();
            return;
        }
        debug("restoring layout: " + layout);
        var windowDescs = layout.split(";");
        for (var windowDesc : windowDescs) {
            var stage = newWindowEmpty();
            if (stage == null) { newWindow(); continue; }
            var tabs = getTabPane(stage);
            if (tabs == null) { newWindow(); continue; }
            var tabDescs = splitTabDescs(windowDesc);
            for (var tabDesc : tabDescs) {
                restoreTab(tabs, tabDesc.trim());
            }
            if (tabs.getTabs().isEmpty()) newTab(tabs); // fallback
        }
    }

    /** Split on commas that are not inside brackets. */
    private static List<String> splitTabDescs(String windowDesc) {
        var result = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < windowDesc.length(); i++) {
            var ch = windowDesc.charAt(i);
            if (ch == '[') depth++;
            else if (ch == ']') depth--;
            else if (ch == ',' && depth == 0) {
                result.add(windowDesc.substring(start, i));
                start = i + 1;
            }
        }
        result.add(windowDesc.substring(start));
        return result;
    }

    private static void restoreTab(TabPane tabPane, String desc) {
        if (desc.isEmpty()) { newTab(tabPane); return; }

        // Parse: "1[cmd]" or "V2[cmd1|cmd2]" or "H3[cmd1|cmd2|cmd3]"
        var bracketIdx = desc.indexOf('[');
        if (bracketIdx < 0) {
            // No command info, just create default tabs
            newTab(tabPane);
            return;
        }
        var prefix = desc.substring(0, bracketIdx);
        var cmdsPart = desc.substring(bracketIdx + 1, desc.length() - 1);
        var cmds = splitCommands(cmdsPart);

        if (prefix.equals("1")) {
            // Single terminal
            var cmd = cmds.isEmpty() ? shellCommand : parseCommand(cmds.getFirst());
            var view = createTerminalClean(cmd);
            if (view == null) return;
            var tab = new Tab();
            bindTabTitle(tab, view);
            tab.setContent(view);
            tab.setClosable(true);
            tab.setOnClosed(_ -> closeTerminalsIn(view));
            tabPane.getTabs().add(tab);
            tabPane.getSelectionModel().select(tab);
        } else {
            // Split: V2, H3, etc.
            var orientation = prefix.startsWith("V") ? Orientation.VERTICAL : Orientation.HORIZONTAL;
            var count = cmds.size();
            if (count < 2) { newTab(tabPane); return; }

            var views = new ArrayList<TerminalView>();
            for (var cmdStr : cmds) {
                var cmd = parseCommand(cmdStr);
                var v = createTerminalClean(cmd);
                if (v != null) views.add(v);
            }
            if (views.isEmpty()) { newTab(tabPane); return; }
            if (views.size() == 1) {
                var tab = new Tab();
                bindTabTitle(tab, views.getFirst());
                tab.setContent(views.getFirst());
                tab.setClosable(true);
                tab.setOnClosed(_ -> closeTerminalsIn(views.getFirst()));
                tabPane.getTabs().add(tab);
            } else {
                var sp = new SplitPane();
                sp.setOrientation(orientation);
                views.forEach(v -> sp.getItems().add(v));
                evenDividers(sp);
                var tab = new Tab();
                bindTabTitle(tab, views.getFirst());
                tab.setContent(sp);
                tab.setClosable(true);
                tab.setOnClosed(_ -> closeTerminalsIn(sp));
                tabPane.getTabs().add(tab);
            }
        }
    }

    /** Split on | that are not escaped. */
    private static List<String> splitCommands(String cmdsPart) {
        var result = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < cmdsPart.length(); i++) {
            if (cmdsPart.charAt(i) == '|' && (i == 0 || cmdsPart.charAt(i - 1) != '\\')) {
                result.add(cmdsPart.substring(start, i));
                start = i + 1;
            }
        }
        result.add(cmdsPart.substring(start));
        return result;
    }

    /** Create a new window without adding an initial tab (for layout restore). */
    private static Stage newWindowEmpty() {
        // Temporarily suppress the newTab call by creating the window structure manually
        var tabs = new TabPane();
        tabs.getStyleClass().add("jhostty-tabs");

        var menuBar = createMenuBar(tabs);
        if (IS_MAC) {
            menuBar.setUseSystemMenuBar(true);
            menuBar.setMaxHeight(0);
            menuBar.setPrefHeight(0);
            menuBar.setMinHeight(0);
        }

        var sidebar = new TreeView<SidebarItem>();
        sidebar.setShowRoot(false);
        sidebar.getStyleClass().add("jhostty-sidebar");
        sidebar.setMinWidth(100);
        sidebar.setCellFactory(_ -> new TreeCell<>() {
            private final Label icon = new Label();
            { icon.setStyle("-fx-font-size: 11; -fx-padding: 0 4 0 0;"); }
            @Override protected void updateItem(SidebarItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                setText(item.label());
                switch (item) {
                    case SidebarItem.WindowItem _ ->   icon.setText("\u25A1");  // □
                    case SidebarItem.TabItem _ ->      icon.setText("\u25AB");  // ▫
                    case SidebarItem.TerminalItem _ ->  icon.setText("\u276F");  // ❯
                    case SidebarItem.SectionHeader _ -> icon.setText("\u2261");  // ≡
                    case SidebarItem.ZmxSessionItem s -> icon.setText(s.session().clients() > 0 ? "\u25C9" : "\u25CB"); // ◉ / ○
                }
                icon.setTextFill(getTextFill());
                setGraphic(icon);
                if (item instanceof SidebarItem.TerminalItem ti && ti.view() == activeTerminal) {
                    setStyle("-fx-font-weight: bold;");
                } else if (item instanceof SidebarItem.SectionHeader) {
                    setStyle("-fx-font-weight: bold; -fx-font-size: 11;");
                } else {
                    setStyle(null);
                }
            }
        });
        sidebar.setOnMouseClicked(e -> {
            if (e.getClickCount() < 2) return;
            var sel = sidebar.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            switch (sel.getValue()) {
                case SidebarItem.TerminalItem ti -> {
                    var stg = findStageFor(ti.view());
                    if (stg != null) { stg.toFront(); stg.requestFocus(); }
                    var tp2 = findTabPane(ti.view());
                    if (tp2 != null) {
                        var tab = findTab(ti.view());
                        if (tab != null) tp2.getSelectionModel().select(tab);
                    }
                    Platform.runLater(() -> ti.view().requestFocus());
                }
                case SidebarItem.TabItem ti -> {
                    var tp2 = findTabPaneForTab(ti.tab());
                    if (tp2 != null) {
                        tp2.getSelectionModel().select(ti.tab());
                        var stg = findStage(tp2);
                        if (stg != null) { stg.toFront(); stg.requestFocus(); }
                        Platform.runLater(() -> focusFirstTerminal(ti.tab().getContent()));
                    }
                }
                case SidebarItem.WindowItem wi -> {
                    wi.stage().toFront();
                    wi.stage().requestFocus();
                }
                case SidebarItem.ZmxSessionItem zi -> {
                    // Focus existing terminal attached to this session, or create new one
                    var existing = findTerminalForZmxSession(zi.session().name());
                    if (existing != null) {
                        var stg = findStageFor(existing);
                        if (stg != null) { stg.toFront(); stg.requestFocus(); }
                        var tp2 = findTabPane(existing);
                        if (tp2 != null) {
                            var tab = findTab(existing);
                            if (tab != null) tp2.getSelectionModel().select(tab);
                        }
                        Platform.runLater(existing::requestFocus);
                    } else {
                        attachZmxSession(zi.session().name());
                    }
                }
                case SidebarItem.SectionHeader _ -> {}
            }
        });

        // Sidebar + tabs in a horizontal SplitPane for resizable sidebar
        var contentSplit = new SplitPane(tabs);
        contentSplit.setOrientation(Orientation.HORIZONTAL);
        contentSplit.getStyleClass().add("jhostty-content-split");

        var root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(contentSplit);

        var scene = new Scene(root, 1000, 700);
        if (cssUrl != null) scene.getStylesheets().add(cssUrl);

        updateTabBarVisibility(tabs);
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) _ -> {
            updateTabBarVisibility(tabs);
            rebuildAllSidebars();
        });
        tabs.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> rebuildAllSidebars());

        var stage = new Stage();
        stage.setTitle("jhostty");
        stage.setScene(scene);
        stage.setOnCloseRequest(_ -> closeAllTerminalsIn(tabs));
        stage.setOnHidden(_ -> {
            savedWindowX = stage.getX();
            savedWindowY = stage.getY();
            windows.remove(stage);
            windowMenus.removeIf(m -> m.getParentMenu() == null && m.getParentPopup() == null);
            sidebarsByWindow.remove(stage);
            rebuildWindowMenus();
            rebuildAllSidebars();
            if (windows.isEmpty()) {
                if (!shuttingDown) saveState(); // normal close of last window
                Platform.exit();
            }
        });

        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (debug && e.isShortcutDown()) {
                debug("KeyEvent: code=" + e.getCode() + " meta=" + e.isMetaDown()
                        + " ctrl=" + e.isControlDown() + " shift=" + e.isShiftDown()
                        + " alt=" + e.isAltDown() + " shortcut=" + e.isShortcutDown());
            }
            if (!e.isShortcutDown()) return;
            switch (e.getCode()) {
                case Q -> { quit(); e.consume(); }
                case N -> { newWindow(); e.consume(); }
                case T -> { newTab(tabs); e.consume(); }
                case D -> { if (e.isShiftDown()) splitActive(Orientation.HORIZONTAL); else splitActive(Orientation.VERTICAL); e.consume(); }
                case W -> { closeActive(tabs, stage); e.consume(); }
                case BACK_SLASH -> { toggleSidebar(); e.consume(); }
                case EQUALS, PLUS, ADD -> { if (activeTerminal != null) zoomTerminal(activeTerminal, 1); e.consume(); }
                case MINUS, SUBTRACT -> { if (activeTerminal != null) zoomTerminal(activeTerminal, -1); e.consume(); }
                case DIGIT0, NUMPAD0 -> { if (activeTerminal != null) setTerminalZoom(activeTerminal, baseFontSize); e.consume(); }
                default -> {}
            }
        });
        scene.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (debug) {
                debug("ScrollEvent: deltaX=" + e.getDeltaX() + " deltaY=" + e.getDeltaY()
                        + " meta=" + e.isMetaDown() + " ctrl=" + e.isControlDown()
                        + " shift=" + e.isShiftDown() + " alt=" + e.isAltDown()
                        + " shortcut=" + e.isShortcutDown() + " direct=" + e.isDirect()
                        + " inertia=" + e.isInertia() + " touchCount=" + e.getTouchCount());
            }
            if (e.isShortcutDown() && e.getDeltaY() != 0) {
                var target = terminalAt(tabs, e.getScreenX(), e.getScreenY());
                if (target == null) target = activeTerminal;
                if (target != null) { zoomTerminal(target, e.getDeltaY() > 0 ? 1 : -1); e.consume(); }
            }
        });
        scene.addEventFilter(ZoomEvent.ZOOM, e -> {
            if (debug) {
                debug("ZoomEvent: factor=" + e.getZoomFactor() + " total=" + e.getTotalZoomFactor()
                        + " meta=" + e.isMetaDown() + " ctrl=" + e.isControlDown()
                        + " shift=" + e.isShiftDown() + " alt=" + e.isAltDown()
                        + " shortcut=" + e.isShortcutDown() + " direct=" + e.isDirect()
                        + " inertia=" + e.isInertia());
            }
            if (e.isShortcutDown()) {
                var target = terminalAt(tabs, e.getScreenX(), e.getScreenY());
                if (target == null) target = activeTerminal;
                if (target != null) { zoomTerminal(target, e.getZoomFactor() > 1 ? 1 : -1); e.consume(); }
            }
        });

        if (!Double.isNaN(savedWindowX)) stage.setX(savedWindowX);
        if (!Double.isNaN(savedWindowY)) stage.setY(savedWindowY);
        windows.add(stage);
        sidebarsByWindow.put(stage, sidebar);
        stage.show();
        if (sidebarVisible) {
            showSidebarIn(contentSplit, sidebar);
            rebuildAllSidebars();
        }
        rebuildWindowMenus();
        return stage;
    }

    /** Quote a file path for safe pasting into a shell. */
    private static String quotePath(String path) {
        if (!path.contains(" ") && !path.contains("'") && !path.contains("\"")) return path;
        if (IS_WINDOWS) return "\"" + path.replace("\"", "\\\"") + "\"";
        return "'" + path.replace("'", "'\\''" ) + "'";
    }

    // --- zmx session management ---

    private static void initZmx() {
        // Check if zmx is available
        zmxAvailable = resolveExecutable("zmx") != null;
        if (!zmxAvailable) {
            debug("zmx not found in PATH");
            return;
        }
        debug("zmx found, starting session refresh");
        refreshZmxSessions();

        // Refresh every 5 seconds
        zmxRefreshTimer = new Timeline(
                new KeyFrame(javafx.util.Duration.seconds(5), _ -> refreshZmxSessions()));
        zmxRefreshTimer.setCycleCount(Animation.INDEFINITE);
        zmxRefreshTimer.play();
    }

    private static void refreshZmxSessions() {
        Thread.ofVirtual().name("zmx-list").start(() -> {
            try {
                var pb = new ProcessBuilder("zmx", "list");
                pb.redirectErrorStream(true);
                var proc = pb.start();
                var output = new String(proc.getInputStream().readAllBytes()).trim();
                if (!proc.waitFor(5, TimeUnit.SECONDS)) { proc.destroyForcibly(); return; }
                var sessions = parseZmxList(output);
                Platform.runLater(() -> {
                    zmxSessions = sessions;
                    rebuildAllSidebars();
                });
            } catch (Exception e) {
                debug("zmx list failed: " + e.getMessage());
            }
        });
    }

    private static List<ZmxSession> parseZmxList(String output) {
        if (output.isBlank()) return List.of();
        var result = new ArrayList<ZmxSession>();
        for (var line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("\u2192 ")) line = line.substring(2).trim(); // strip → prefix
            if (line.isBlank()) continue;
            var fields = new java.util.HashMap<String, String>();
            // Parse tab-separated key=value pairs
            for (var part : line.split("\t")) {
                part = part.trim();
                var eq = part.indexOf('=');
                if (eq > 0) {
                    var key = part.substring(0, eq).trim();
                    var val = part.substring(eq + 1).trim();
                    fields.put(key, val);
                }
            }
            var name = fields.getOrDefault("name", "");
            if (name.isBlank()) continue;
            var pid = parseInt(fields.getOrDefault("pid", "0"));
            var clients = parseInt(fields.getOrDefault("clients", "0"));
            var startDir = fields.getOrDefault("start_dir", "");
            var cwdVal = fields.getOrDefault("cwd", "");
            var cmd = fields.getOrDefault("cmd", "");
            var ended = fields.containsKey("ended");
            var exitCode = parseInt(fields.getOrDefault("exit_code", "0"));
            result.add(new ZmxSession(name, pid, clients, startDir, cwdVal, cmd, ended, exitCode));
        }
        return result;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException _) { return 0; }
    }

    /** Find an existing terminal that is attached to the given zmx session, or null. */
    private static TerminalView findTerminalForZmxSession(String sessionName) {
        var result = new ArrayList<TerminalView>();
        forEachTerminal(v -> {
            var cmd = getTerminalCommand(v);
            // Match commands like ["zmx", "attach", "<name>"] or ["/path/to/zmx", "attach", "<name>"]
            if (cmd.size() >= 3 && cmd.get(cmd.size() - 2).equals("attach")
                    && cmd.getLast().equals(sessionName)
                    && Path.of(cmd.getFirst()).getFileName().toString().equals("zmx")) {
                result.add(v);
            }
        });
        return result.isEmpty() ? null : result.getFirst();
    }

    /** Attach to a zmx session in a new tab (raw mode, no shell integration). */
    private static void attachZmxSession(String sessionName) {
        var zmxPath = resolveExecutable("zmx");
        if (zmxPath == null) return;
        var cmd = List.of(zmxPath.toString(), "attach", sessionName);
        var tabPane = findActiveTabPane();
        if (tabPane == null) return;
        var view = createTerminalClean(cmd);
        if (view == null) return;
        var tab = new Tab();
        bindTabTitle(tab, view);
        tab.setContent(view);
        tab.setClosable(true);
        tab.setOnClosed(_ -> closeTerminalsIn(view));
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        Platform.runLater(jhostty::saveState);
    }

    /** Find the TabPane in the focused/first window. */
    private static TabPane findActiveTabPane() {
        // Try active terminal's window first
        if (activeTerminal != null) {
            var tp = findTabPane(activeTerminal);
            if (tp != null) return tp;
        }
        // Fallback to first window
        if (!windows.isEmpty()) {
            return getTabPane(windows.getFirst());
        }
        return null;
    }

    // --- Font handling ---

    private static String defaultFontFamily() {
        var families = Font.getFamilies();
        for (var family : List.of(
                "JetBrainsMono Nerd Font Mono", "JetBrainsMono Nerd Font",
                "JetBrains Mono", "SF Mono", "Menlo", "Monaco", "Consolas")) {
            if (families.contains(family)) return family;
        }
        return "Monospaced";
    }

    private static Font resolveFont(String family, double size) {
        return new Font(Font.getFontNames(family).stream()
                .filter(n -> n.endsWith("Regular") || n.equals(family))
                .findFirst()
                .orElse(family), size);
    }

    private static List<FontOption> detectFonts() {
        var preferred = List.of(
                "JetBrainsMono Nerd Font Mono", "JetBrainsMono Nerd Font",
                "JetBrains Mono", "FiraCode Nerd Font", "Hack Nerd Font",
                "SF Mono", "Menlo", "Monaco", "Consolas", "Courier New");
        var all = Font.getFamilies();
        var result = new ArrayList<FontOption>();
        var added = new LinkedHashSet<String>();
        for (var family : preferred) {
            if (all.contains(family) && added.add(family)) result.add(new FontOption(family));
        }
        for (var family : all) {
            if (added.add(family)) result.add(new FontOption(family));
        }
        return result;
    }

    // --- macOS app name ---

    private static void setMacAppName(String name) {
        try {
            var linker = Linker.nativeLinker();
            var rt = SymbolLookup.libraryLookup("libobjc.dylib", Arena.global());
            var arena = Arena.global();
            var cls = linker.downcallHandle(rt.find("objc_getClass").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var sel = linker.downcallHandle(rt.find("sel_registerName").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var send0 = linker.downcallHandle(rt.find("objc_msgSend").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var send1 = linker.downcallHandle(rt.find("objc_msgSend").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var sendV = linker.downcallHandle(rt.find("objc_msgSend").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var sendL = linker.downcallHandle(rt.find("objc_msgSend").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            var nsStr = (MemorySegment) send1.invoke(
                    (MemorySegment) send0.invoke((MemorySegment) cls.invoke(arena.allocateFrom("NSString")),
                            (MemorySegment) sel.invoke(arena.allocateFrom("alloc"))),
                    (MemorySegment) sel.invoke(arena.allocateFrom("initWithUTF8String:")),
                    arena.allocateFrom(name));
            var nsApp = (MemorySegment) send0.invoke(
                    (MemorySegment) cls.invoke(arena.allocateFrom("NSApplication")),
                    (MemorySegment) sel.invoke(arena.allocateFrom("sharedApplication")));
            var mainMenu = (MemorySegment) send0.invoke(nsApp, (MemorySegment) sel.invoke(arena.allocateFrom("mainMenu")));
            var appMenuItem = (MemorySegment) sendL.invoke(mainMenu, (MemorySegment) sel.invoke(arena.allocateFrom("itemAtIndex:")), 0L);
            var appMenu = (MemorySegment) send0.invoke(appMenuItem, (MemorySegment) sel.invoke(arena.allocateFrom("submenu")));
            sendV.invoke(appMenu, (MemorySegment) sel.invoke(arena.allocateFrom("setTitle:")), nsStr);
        } catch (Throwable _) {}
    }

    record FontOption(String family) {
        @Override public String toString() { return family; }
    }

    record ThemeOption(String label, TerminalTheme theme) {
        @Override public String toString() { return label; }
    }
}