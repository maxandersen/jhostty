package dk.xam.jhostty;

import dk.xam.jhostty.FontManager.FontOption;
import dk.xam.jhostty.ShellDetection.ShellOption;
import dk.xam.jhostty.Themes.ThemeOption;

import io.github.vlaaad.ghosttyfx.Shell;
import io.github.vlaaad.ghosttyfx.TerminalState;
import io.github.vlaaad.ghosttyfx.TerminalTheme;
import io.github.vlaaad.ghosttyfx.TerminalView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

public class JHostty extends Application {

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    static final boolean IS_MAC = OS_NAME.contains("mac");
    static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final String SHORTCUT_SYMBOL = IS_MAC ? "\u2318" : "Ctrl+";
    private static final String SHIFT_SYMBOL = IS_MAC ? "\u21E7" : "Shift+";
    private static final double DEFAULT_SIZE = 15.0;
    static final String ZOOM_KEY = "jhostty.fontSize";
    static final String COMMAND_KEY = "jhostty.command";

    private static final Set<TerminalView> closingTerminals = ConcurrentHashMap.newKeySet();
    static String currentFontFamily;
    static String currentThemeName;
    static TerminalTheme currentTheme;
    static double baseFontSize = DEFAULT_SIZE;
    static double currentZoom = DEFAULT_SIZE;
    static Path configDir;
    static double savedWindowX = Double.NaN;
    static double savedWindowY = Double.NaN;
    static String detectedFontFamily;
    static List<String> detectedShellCommand;
    static TerminalView activeTerminal;
    static Path cwd;
    static List<String> shellCommand;
    static Path cssPath;
    static String cssUrl;
    static boolean debug;
    static boolean demoMode;
    static final List<Stage> windows = new ArrayList<>();
    static final List<Menu> windowMenus = new ArrayList<>();
    static boolean sidebarVisible = false;
    static double sidebarDividerPos = 0.18;
    static boolean shuttingDown = false;
    static String savedLayout = null;
    static final Map<Stage, TreeView<SidebarItem>> sidebarsByWindow = new ConcurrentHashMap<>();
    static boolean sidebarRebuildScheduled = false;

    // zmx
    static List<ZmxSession> zmxSessions = List.of();
    static volatile boolean zmxAvailable = false;
    static Timeline zmxRefreshTimer;

    // Layout
    static String lastGoodLayout = null;

    // --- Sidebar model ---

    public sealed interface SidebarItem {
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

    // --- Entry point ---

    public static void run(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("Uncaught exception in thread \"" + t.getName() + "\"");
            e.printStackTrace();
        });
        debug = List.of(args).contains("--debug");
        demoMode = List.of(args).contains("--demo");
        Runtime.getRuntime().addShutdownHook(new Thread(JHostty::saveState, "jhostty-shutdown"));
        Application.launch(JHostty.class, args);
    }

    static void debug(String msg) {
        if (debug) System.err.println("[jhostty] " + msg);
    }

    @Override
    public void start(Stage _ignored) {
        cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        configDir = resolveConfigDir();

        detectedFontFamily = FontManager.defaultFontFamily();
        currentFontFamily = detectedFontFamily;
        currentThemeName = "Ghostty Default";
        currentTheme = Themes.all().getFirst().theme();

        var shells = ShellDetection.detectTerminals();
        detectedShellCommand = shells.isEmpty()
                ? List.of(IS_WINDOWS ? "cmd.exe" : "/bin/sh")
                : shells.getFirst().command();
        shellCommand = detectedShellCommand;

        loadConfig();
        System.err.println("[jhostty] shell: " + shellCommand);

        try {
            cssPath = Files.createTempFile("jhostty", ".css");
            cssPath.toFile().deleteOnExit();
            cssUrl = cssPath.toUri().toString();
            writeCss();
        } catch (IOException _) {}

        Platform.setImplicitExit(false);
        initZmx();
        if (demoMode) { newDemoWindow(); }
        else { restoreLayout(); }
        if (IS_MAC) {
            Platform.runLater(() -> MacUtils.setAppName("jhostty"));
        }
    }

    static void quit() {
        debug("quit: saving state before exit");
        saveState();
        shuttingDown = true;
        if (zmxRefreshTimer != null) zmxRefreshTimer.stop();
        var allWindows = new ArrayList<>(windows);
        for (var w : allWindows) {
            var tp = getTabPane(w);
            if (tp != null) closeAllTerminalsIn(tp);
            w.close();
        }
        Platform.exit();
    }

    static Stage newWindow() {
        var stage = newWindowEmpty();
        if (stage == null) return null;
        var tabs = getTabPane(stage);
        if (tabs == null) return null;
        newTab(tabs);
        return stage;
    }

    static void newDemoWindow() {
        var demo = SplitWorkspace.createDemo();
        demo.setStyle("-fx-background-color: #FAFAFA;");
        var scene = new Scene(demo, 1000, 700);
        var stage = new Stage();
        stage.setTitle("SplitWorkspace Demo");
        stage.setScene(scene);
        stage.setOnHidden(_ -> { windows.remove(stage); if (windows.isEmpty()) Platform.exit(); });
        windows.add(stage);
        stage.show();
    }

    // --- Menu Bar ---

    static MenuBar createMenuBar(TabPane tabs) {
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

        var windowMenu = new Menu("Window");
        windowMenus.add(windowMenu);

        var zoomIn = new MenuItem("Zoom In");
        zoomIn.setAccelerator(KeyCombination.keyCombination("Shortcut+Plus"));
        zoomIn.setOnAction(_ -> { if (activeTerminal != null) zoomTerminal(activeTerminal, 1); });

        var zoomOut = new MenuItem("Zoom Out");
        zoomOut.setAccelerator(KeyCombination.keyCombination("Shortcut+Minus"));
        zoomOut.setOnAction(_ -> { if (activeTerminal != null) zoomTerminal(activeTerminal, -1); });

        var zoomReset = new MenuItem("Reset Zoom");
        zoomReset.setAccelerator(KeyCombination.keyCombination("Shortcut+0"));
        zoomReset.setOnAction(_ -> { if (activeTerminal != null) setTerminalZoom(activeTerminal, baseFontSize); });

        var themeToggle = new ToggleGroup();
        var themeMenu = new Menu("Theme");
        for (var t : Themes.all()) {
            var item = new RadioMenuItem(t.label());
            item.setToggleGroup(themeToggle);
            if (t.theme().equals(currentTheme)) item.setSelected(true);
            item.setOnAction(_ -> { currentThemeName = t.label(); currentTheme = t.theme(); applyThemeToAll(); saveState(); });
            themeMenu.getItems().add(item);
        }

        var fontToggle = new ToggleGroup();
        var fontMenu = new Menu("Font");
        for (var f : FontManager.detectFonts()) {
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
            forEachTerminal(v -> {
                v.getProperties().put(ZOOM_KEY, currentZoom);
                v.setFont(FontManager.resolveFont(currentFontFamily, currentZoom));
            });
            updateTitle();
        });

        var toggleSidebarItem = new MenuItem("Toggle Sidebar");
        toggleSidebarItem.setAccelerator(new KeyCodeCombination(KeyCode.BACK_SLASH, KeyCombination.SHORTCUT_DOWN));
        toggleSidebarItem.setOnAction(_ -> toggleSidebar());

        var animToggle = new CheckMenuItem("Animations");
        animToggle.setSelected(true);
        animToggle.setOnAction(_ -> {
            for (var w : windows) {
                var tp2 = getTabPane(w);
                if (tp2 != null) { for (var t : tp2.getTabs()) { if (t.getContent() instanceof SplitWorkspace ws) ws.animationsEnabledProperty().set(animToggle.isSelected()); } }
            }
        });

        var pastelToggle = new CheckMenuItem("Pastel Tinting");
        pastelToggle.setSelected(true);
        pastelToggle.setOnAction(_ -> {
            for (var w : windows) {
                var tp2 = getTabPane(w);
                if (tp2 != null) { for (var t : tp2.getTabs()) { if (t.getContent() instanceof SplitWorkspace ws) ws.pastelTintingProperty().set(pastelToggle.isSelected()); } }
            }
        });

        var settingsToggle = new MenuItem("Toggle Settings Panel");
        settingsToggle.setAccelerator(KeyCombination.keyCombination("Shortcut+,"));
        settingsToggle.setOnAction(_ -> {
            var bp = (BorderPane) tabs.getScene().getRoot();
            var sp = bp.getRight();
            if (sp != null) { var vis = !sp.isVisible(); sp.setVisible(vis); sp.setManaged(vis); }
        });

        var viewMenu = new Menu("View", null, toggleSidebarItem, new SeparatorMenuItem(), zoomIn, zoomOut, zoomReset, new SeparatorMenuItem(), themeMenu, fontMenu, new SeparatorMenuItem(), animToggle, pastelToggle, new SeparatorMenuItem(), settingsToggle, reloadConfig);

        return new MenuBar(shellMenu, viewMenu, windowMenu);
    }

    // --- Context Menu ---

    static ContextMenu createContextMenu(TerminalView view) {
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
                new SeparatorMenuItem(), zoomIn, zoomOut, zoomReset,
                new SeparatorMenuItem(), copy, paste,
                new SeparatorMenuItem(), toggleSidebar);
    }

    // --- Tab Management ---

    static void newTab(TabPane tabPane) {
        var workspace = SplitWorkspace.createSingle(() -> createTerminal());
        workspace.setContentFactory(() -> createTerminal());
        workspace.setPaneBackground(currentTheme.background());
        workspace.setFocusRingColor(focusRingColor(currentTheme));
        workspace.setPastelOpacity(pastelOpacity(currentTheme));
        // Close tab/window when last pane is dragged out
        workspace.setOnEmpty(() -> Platform.runLater(() -> {
            var tp = findTabPane(workspace);
            var stg = tp != null ? findStage(tp) : null;
            var t = findTab(workspace);
            if (t != null && tp != null) tabPane.getTabs().remove(t);
            if (tp != null && tp.getTabs().stream().noneMatch(tab -> tab.getContent() != null) && stg != null) stg.close();
        }));
        // Track active terminal from workspace focus
        workspace.focusedPaneProperty().addListener((_, _, pane) -> {
            if (pane != null && pane.content() instanceof TerminalView tv) {
                activeTerminal = tv;
                var stg = findStageFor(tv);
                if (stg != null) { stg.setTitle(tv.getTitle() != null ? tv.getTitle() : "jhostty"); rebuildWindowMenus(); }
                rebuildAllSidebars();
            }
        });
        var tab = new Tab();
        tab.setText("jhostty");
        tab.setContent(workspace);
        tab.setClosable(true);
        tab.setOnClosed(_ -> closeTerminalsIn(workspace));

        // Insert before the "+" tab if present
        var plusIdx = -1;
        for (int i = 0; i < tabPane.getTabs().size(); i++) {
            if (tabPane.getTabs().get(i).getContent() == null && "+".equals(tabPane.getTabs().get(i).getText())) {
                plusIdx = i;
                break;
            }
        }
        if (plusIdx >= 0) tabPane.getTabs().add(plusIdx, tab);
        else tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);

        // Update tab title when workspace focus changes
        workspace.focusedPaneProperty().addListener((_, _, pane) -> {
            if (pane != null && pane.content() instanceof TerminalView tv) {
                tab.textProperty().bind(tv.titleProperty());
            }
        });
        Platform.runLater(JHostty::saveState);
    }

    static void rebuildWindowMenus() {
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

    static TabPane findTabPane(Node node) {
        var p = node.getParent();
        while (p != null) {
            if (p instanceof TabPane tp) return tp;
            p = p.getParent();
        }
        for (var w : windows) {
            var tp = getTabPane(w);
            if (tp != null) return tp;
        }
        return null;
    }

    static Stage findStage(TabPane tp) {
        if (tp != null && tp.getScene() != null && tp.getScene().getWindow() instanceof Stage s) return s;
        return null;
    }

    static Stage findStageFor(TerminalView view) {
        var tp = findTabPane(view);
        return tp != null ? findStage(tp) : null;
    }

    static void updateTabBarVisibility(TabPane tp) {
        // Count real tabs (not the "+" button)
        var realCount = tp.getTabs().stream().filter(t -> t.getContent() != null).count();
        var hide = realCount <= 1;
        if (hide) { if (!tp.getStyleClass().contains("single-tab")) tp.getStyleClass().add("single-tab"); }
        else tp.getStyleClass().remove("single-tab");
    }

    // --- Sidebar ---

    static void toggleSidebar() {
        sidebarVisible = !sidebarVisible;
        debug("toggleSidebar: visible=" + sidebarVisible);
        for (var w : windows) {
            if (w.getScene().getRoot() instanceof BorderPane bp && bp.getCenter() instanceof SplitPane sp) {
                var sidebar = sidebarsByWindow.get(w);
                if (sidebar == null) continue;
                if (sidebarVisible) showSidebarIn(sp, sidebar);
                else hideSidebarIn(sp, sidebar);
            }
        }
        if (sidebarVisible) rebuildAllSidebars();
    }

    static TabPane getTabPane(Stage w) {
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

    static SplitPane getContentSplit(Stage w) {
        if (w.getScene() == null) return null;
        if (w.getScene().getRoot() instanceof BorderPane bp && bp.getCenter() instanceof SplitPane sp) return sp;
        return null;
    }

    static void showSidebarIn(SplitPane sp, TreeView<SidebarItem> sidebar) {
        if (!sp.getItems().contains(sidebar)) {
            sp.getItems().addFirst(sidebar);
            Platform.runLater(() -> sp.setDividerPositions(sidebarDividerPos));
        }
    }

    static void hideSidebarIn(SplitPane sp, TreeView<SidebarItem> sidebar) {
        if (sp.getItems().contains(sidebar)) {
            if (!sp.getDividers().isEmpty()) sidebarDividerPos = sp.getDividerPositions()[0];
            sp.getItems().remove(sidebar);
        }
    }

    static TabPane findTabPaneForTab(Tab tab) {
        for (var w : windows) {
            var tp = getTabPane(w);
            if (tp != null && tp.getTabs().contains(tab)) return tp;
        }
        return null;
    }

    static void rebuildAllSidebars() {
        if (!sidebarVisible) return;
        if (sidebarRebuildScheduled) return;
        sidebarRebuildScheduled = true;
        Platform.runLater(() -> {
            sidebarRebuildScheduled = false;
            if (!sidebarVisible) return;
            for (var w : windows) {
                var sidebar = sidebarsByWindow.get(w);
                var tp = getTabPane(w);
                if (sidebar != null && tp != null) rebuildSidebar(sidebar, w, tp);
            }
        });
    }

    static void rebuildSidebar(TreeView<SidebarItem> sidebar, Stage stage, TabPane tabs) {
        sidebar.getSelectionModel().clearSelection();
        var root = new TreeItem<SidebarItem>(new SidebarItem.WindowItem(stage, windows.indexOf(stage)));
        root.setExpanded(true);
        for (int wi = 0; wi < windows.size(); wi++) {
            var w = windows.get(wi);
            var tp = getTabPane(w);
            if (tp == null) continue;
            var windowNode = new TreeItem<SidebarItem>(new SidebarItem.WindowItem(w, wi));
            windowNode.setExpanded(true);
            for (var tab : tp.getTabs()) {
                if (tab.getContent() == null) continue; // skip "+" tab
                var tabTitle = tab.getText() != null ? tab.getText() : "Terminal";
                var tabNode = new TreeItem<SidebarItem>(new SidebarItem.TabItem(tab, tabTitle));
                tabNode.setExpanded(true);
                addTerminalNodes(tabNode, tab.getContent());
                windowNode.getChildren().add(tabNode);
            }
            root.getChildren().add(windowNode);
        }
        TreeItem<SidebarItem> finalRoot;
        if (root.getChildren().size() == 1) {
            var onlyWindow = root.getChildren().getFirst();
            finalRoot = new TreeItem<>(onlyWindow.getValue());
            finalRoot.setExpanded(true);
            finalRoot.getChildren().addAll(onlyWindow.getChildren());
        } else {
            finalRoot = root;
        }
        if (zmxAvailable && !zmxSessions.isEmpty()) {
            var zmxHeader = new TreeItem<SidebarItem>(new SidebarItem.SectionHeader("zmx sessions"));
            zmxHeader.setExpanded(true);
            for (var session : zmxSessions) {
                if (!session.ended()) zmxHeader.getChildren().add(new TreeItem<>(new SidebarItem.ZmxSessionItem(session)));
            }
            if (!zmxHeader.getChildren().isEmpty()) finalRoot.getChildren().add(zmxHeader);
        }
        sidebar.setRoot(finalRoot);
    }

    static void focusFirstTerminal(Node node) {
        if (node instanceof TerminalView v) v.requestFocus();
        else if (node instanceof SplitWorkspace ws) {
            var leaves = ws.allLeaves();
            if (!leaves.isEmpty() && leaves.getFirst().content() instanceof TerminalView tv) tv.requestFocus();
        }
    }

    static void addTerminalNodes(TreeItem<SidebarItem> parent, Node node) {
        if (node instanceof TerminalView v) {
            parent.getChildren().add(new TreeItem<>(new SidebarItem.TerminalItem(v, v.getTitle(), getTerminalCommand(v))));
        } else if (node instanceof SplitWorkspace ws) {
            for (var leaf : ws.allLeaves()) {
                if (leaf.content() instanceof TerminalView v) {
                    parent.getChildren().add(new TreeItem<>(new SidebarItem.TerminalItem(v, v.getTitle(), getTerminalCommand(v))));
                }
            }
        }
    }

    // --- SplitWorkspace helpers ---

    static SplitWorkspace findWorkspace(Node node) {
        var p = node;
        while (p != null) {
            if (p instanceof SplitWorkspace ws) return ws;
            p = p.getParent();
        }
        // Fallback: find workspace in active window tab
        for (var w : windows) {
            var tp = getTabPane(w);
            if (tp != null) {
                var tab = tp.getSelectionModel().getSelectedItem();
                if (tab != null && tab.getContent() instanceof SplitWorkspace ws) return ws;
            }
        }
        return null;
    }

    static SplitWorkspace activeWorkspace() {
        if (activeTerminal != null) {
            var ws = findWorkspace(activeTerminal);
            if (ws != null) return ws;
        }
        for (var w : windows) {
            var tp = getTabPane(w);
            if (tp != null) {
                var tab = tp.getSelectionModel().getSelectedItem();
                if (tab != null && tab.getContent() instanceof SplitWorkspace ws) return ws;
            }
        }
        return null;
    }

    static void forEachWorkspace(TabPane tabs, java.util.function.Consumer<SplitWorkspace> action) {
        for (var tab : tabs.getTabs()) {
            if (tab.getContent() instanceof SplitWorkspace ws) action.accept(ws);
        }
    }

    // --- Split Management (via SplitWorkspace) ---

    static void splitActive(Orientation orientation) {
        var ws = activeWorkspace();
        if (ws == null) return;
        var side = (orientation == Orientation.VERTICAL)
            ? javafx.geometry.Side.RIGHT : javafx.geometry.Side.BOTTOM;
        ws.splitFocused(side);
    }

    static void split(TerminalView existing, Orientation orientation) {
        var ws = findWorkspace(existing);
        if (ws == null) return;
        var leaf = ws.findLeafByContent(existing);
        if (leaf != null) {
            ws.focusPane(leaf);
            var side = (orientation == Orientation.VERTICAL)
                ? javafx.geometry.Side.RIGHT : javafx.geometry.Side.BOTTOM;
            ws.splitFocused(side);
        }
    }

    // --- Close Management ---

    static void closeActive(TabPane tabPane, Stage stage) {
        var ws = activeWorkspace();
        if (ws == null || activeTerminal == null) return;
        var leaf = ws.findLeafByContent(activeTerminal);
        if (leaf != null) {
            var content = leaf.content();
            if (content instanceof TerminalView tv) {
                if (!closingTerminals.add(tv)) return;
                Thread.ofVirtual().name("jhostty-close").start(tv::close);
            }
            ws.closePane(leaf);
            if (ws.allLeaves().isEmpty()) {
                if (tabPane == null) tabPane = findTabPane(ws);
                if (stage == null && tabPane != null) stage = findStage(tabPane);
                var tab = findTab(ws);
                if (tab != null && tabPane != null) tabPane.getTabs().remove(tab);
                if (tabPane != null && tabPane.getTabs().stream().noneMatch(t -> t.getContent() != null) && stage != null) stage.close();
            }
        }
    }

    static void removeTerminal(TerminalView view, TabPane tabPane, Stage stage) {
        if (!closingTerminals.add(view)) return;
        Thread.ofVirtual().name("jhostty-close").start(view::close);

        var ws = findWorkspace(view);
        if (ws != null) {
            var leaf = ws.findLeafByContent(view);
            if (leaf != null) ws.closePane(leaf);
            if (ws.allLeaves().isEmpty()) {
                if (tabPane == null) tabPane = findTabPane(ws);
                if (stage == null && tabPane != null) stage = findStage(tabPane);
                var tab = findTab(ws);
                if (tab != null && tabPane != null) tabPane.getTabs().remove(tab);
                if (tabPane != null && tabPane.getTabs().stream().noneMatch(t -> t.getContent() != null) && stage != null) stage.close();
            }
        } else {
            var tab = findTab(view);
            if (tabPane == null) tabPane = findTabPane(view);
            if (stage == null && tabPane != null) stage = findStage(tabPane);
            if (tab != null && tabPane != null) tabPane.getTabs().remove(tab);
            if (tabPane != null && tabPane.getTabs().stream().noneMatch(t -> t.getContent() != null) && stage != null) stage.close();
        }
    }

    static Tab findTab(Node node) {
        var tp = findTabPane(node);
        if (tp == null) return null;
        for (var tab : tp.getTabs()) {
            if (tab.getContent() == node) return tab;
            if (containsNode(tab.getContent(), node)) return tab;
        }
        return null;
    }

    static boolean containsNode(Node container, Node target) {
        if (container == target) return true;
        if (container instanceof SplitWorkspace ws) {
            for (var leaf : ws.allLeaves()) {
                if (leaf.content() == target) return true;
            }
        }
        return false;
    }

    // --- Terminal Factory ---

    static TerminalView createTerminal() {
        return createTerminal(shellCommand, System.getenv());
    }

    static TerminalView createTerminal(List<String> command) {
        return createTerminal(command, System.getenv());
    }

    static TerminalView createTerminal(List<String> command, Map<String, String> env) {
        try {
            var view = new TerminalView((columns, rows) -> {
                var launcher = Shell.integrate(command, env);
                return new PtyTerminal(launcher.command(), cwd, launcher.environment(), columns, rows);
            });
            view.getProperties().put(ZOOM_KEY, currentZoom);
            view.getProperties().put(COMMAND_KEY, List.copyOf(command));
            view.setFont(FontManager.resolveFont(currentFontFamily, currentZoom));
            view.setTheme(currentTheme);

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
            view.terminalStateProperty().addListener((_, _, state) -> {
                if (!(state instanceof TerminalState.Running)) {
                    Platform.runLater(() -> removeTerminal(view, null, null));
                }
            });
            var ctx = createContextMenu(view);
            view.setOnContextMenuRequested(e -> ctx.show(view, e.getScreenX(), e.getScreenY()));
            view.setOnDragOver(e -> {
                var db = e.getDragboard();
                if (db.hasString() || db.hasFiles() || db.hasUrl()) e.acceptTransferModes(TransferMode.COPY);
                e.consume();
            });
            view.setOnDragDropped(e -> {
                var db = e.getDragboard();
                if (db.hasFiles()) {
                    var paths = db.getFiles().stream().map(f -> quotePath(f.getAbsolutePath())).toList();
                    view.sendText(String.join(" ", paths));
                } else if (db.hasString()) view.sendText(db.getString());
                else if (db.hasUrl()) view.sendText(db.getUrl());
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

    static TerminalView createTerminalClean(List<String> cmd) {
        if (cmd.size() >= 2 && Path.of(cmd.getFirst()).getFileName().toString().equals("zmx")) {
            var env = new java.util.HashMap<>(System.getenv());
            env.remove("ZMX_SESSION");
            env.remove("ZMX_SESSION_PREFIX");
            env.remove("ZMX_DIR");
            return createTerminal(cmd, env);
        }
        return createTerminal(cmd);
    }

    // --- Settings ---

    @SuppressWarnings("unchecked")
    static List<String> getTerminalCommand(TerminalView v) {
        var cmd = v.getProperties().get(COMMAND_KEY);
        return cmd instanceof List<?> l ? (List<String>) l : shellCommand;
    }

    static void bindTabTitle(Tab tab, TerminalView view) {
        var cmd = getTerminalCommand(view);
        var fallback = commandBaseName(cmd);
        var title = view.getTitle();
        tab.setText(title != null && !title.isBlank() ? title : fallback);
        view.titleProperty().addListener((_, _, newTitle) -> {
            tab.setText(newTitle != null && !newTitle.isBlank() ? newTitle : fallback);
        });
    }

    static String commandBaseName(List<String> cmd) {
        if (cmd == null || cmd.isEmpty()) return "Terminal";
        var exe = Path.of(cmd.getFirst()).getFileName().toString();
        if (exe.equals("zmx") && cmd.size() >= 3 && cmd.get(1).equals("attach")) {
            var sessionName = cmd.get(2);
            for (var s : zmxSessions) {
                if (s.name().equals(sessionName)) return "zmx: " + s.friendlyName();
            }
            return "zmx: " + sessionName;
        }
        return cmd.size() > 1 ? exe + " " + String.join(" ", cmd.subList(1, cmd.size())) : exe;
    }

    static double getTerminalSize(TerminalView v) {
        var s = v.getProperties().get(ZOOM_KEY);
        return s instanceof Double d ? d : currentZoom;
    }

    static void zoomTerminal(TerminalView v, double delta) {
        var oldSize = getTerminalSize(v);
        var newSize = Math.max(8, oldSize + delta);
        debug("zoom: " + oldSize + " -> " + newSize + " (delta=" + delta + ")");
        setTerminalZoom(v, newSize);
    }

    static void setTerminalZoom(TerminalView v, double size) {
        v.getProperties().put(ZOOM_KEY, size);
        v.setFont(FontManager.resolveFont(currentFontFamily, size));
        currentZoom = size;
        updateTitle();
        saveState();
    }

    static void applyFontToAll() {
        forEachTerminal(v -> v.setFont(FontManager.resolveFont(currentFontFamily, getTerminalSize(v))));
        updateTitle();
    }

    static void updateTitle() {
        if (activeTerminal == null) return;
        var stg = findStageFor(activeTerminal);
        if (stg == null) return;
        var base = activeTerminal.getTitle() != null ? activeTerminal.getTitle() : "jhostty";
        var pct = Math.round(getTerminalSize(activeTerminal) / baseFontSize * 100);
        stg.setTitle(pct == 100 ? base : base + " (" + pct + "%)");
    }

    static void applyThemeToAll() {
        forEachTerminal(v -> v.setTheme(currentTheme));
        // Update workspace pane backgrounds to match theme
        for (var w : windows) {
            var tp = getTabPane(w);
            if (tp != null) {
                for (var tab : tp.getTabs()) {
                    if (tab.getContent() instanceof SplitWorkspace ws) {
                        ws.setPaneBackground(currentTheme.background());
                        ws.setFocusRingColor(focusRingColor(currentTheme));
                        ws.setPastelOpacity(pastelOpacity(currentTheme));
                    }
                }
            }
        }
        writeCss();
        var divColor = dividerColor(currentTheme.background());
        for (var w : windows) {
            w.getScene().setFill(divColor);
            if (w.getScene().getRoot() instanceof BorderPane bp) {
                bp.setStyle("-fx-background-color: " + colorToCss(divColor) + ";");
            }
            var sheets = w.getScene().getStylesheets();
            if (sheets.contains(cssUrl)) { sheets.remove(cssUrl); sheets.add(cssUrl); }
        }
    }

    /** Pastel overlay opacity — subtle in dark themes, more visible in light. */
    static double pastelOpacity(TerminalTheme theme) {
        var bg = theme.background();
        var lum = bg.getRed() * 0.299 + bg.getGreen() * 0.587 + bg.getBlue() * 0.114;
        return lum < 0.5 ? 0.15 : 0.25;
    }

    /** Focus ring color derived from theme. */
    static Color focusRingColor(TerminalTheme theme) {
        var bg = theme.background();
        var lum = bg.getRed() * 0.299 + bg.getGreen() * 0.587 + bg.getBlue() * 0.114;
        var fg = theme.foreground();
        return fg.deriveColor(0, 1, 1, lum < 0.5 ? 0.4 : 0.65);
    }

    /** Divider/gutter color — slightly offset from terminal bg. */
    static Color dividerColor(Color bg) {
        var lum = bg.getRed() * 0.299 + bg.getGreen() * 0.587 + bg.getBlue() * 0.114;
        return lum < 0.5 ? Color.web("#555555") : Color.web("#bbbbbb");
    }

    static String colorToCss(Color c) {
        return String.format("rgba(%d,%d,%d,%.2f)",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255), c.getOpacity());
    }

    static void writeCss() {
        if (cssPath == null) return;
        var bg = currentTheme.background();
        var fg = currentTheme.foreground();
        var lum = bg.getRed() * 0.299 + bg.getGreen() * 0.587 + bg.getBlue() * 0.114;
        var dark = lum < 0.5;
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
        var tabBarBg = dark ? "rgba(20,20,20,0.95)" : "rgba(230,230,230,0.95)";
        var tabSelectedBg = dark ? "rgba(60,60,60,0.9)" : "rgba(255,255,255,0.9)";
        var tabTextCss = dark ? "rgba(255,255,255,0.5)" : "rgba(0,0,0,0.5)";
        var tabSelectedTextCss = dark ? "rgba(255,255,255,0.85)" : "rgba(0,0,0,0.85)";
        var tabCloseCss = dark ? "rgba(255,255,255,0.3)" : "rgba(0,0,0,0.3)";
        var tabCloseHoverCss = dark ? "rgba(255,255,255,0.7)" : "rgba(0,0,0,0.7)";
        try {
            Files.writeString(cssPath, """
                    .single-tab > .tab-header-area { -fx-max-height: 0; -fx-pref-height: 0; -fx-min-height: 0; visibility: hidden; }
                    .tab-pane > .tab-header-area { -fx-background-color: %s; -fx-padding: 0; }
                    .tab-pane > .tab-header-area > .headers-region { -fx-background-color: transparent; }
                    .tab-pane > .tab-header-area > .tab-header-background { -fx-background-color: %s; }
                    .tab-pane .tab { -fx-background-color: transparent; -fx-background-radius: 6 6 0 0; -fx-background-insets: 2 1 0 1; -fx-padding: 4 12 4 12; -fx-border-color: transparent; }
                    .tab-pane .tab:selected { -fx-background-color: %s; }
                    .tab-pane .tab .tab-label { -fx-text-fill: %s; -fx-font-size: 11; }
                    .tab-pane .tab:selected .tab-label { -fx-text-fill: %s; }
                    .tab-pane .tab .tab-close-button { -fx-background-color: %s; -fx-shape: "M 0,0 L 4,4 M 4,0 L 0,4"; -fx-padding: 0 4 0 4; }
                    .tab-pane .tab:hover .tab-close-button { -fx-background-color: %s; }
                    .split-pane > .split-pane-divider { -fx-background-color: %s; -fx-padding: 1; }
                    .context-menu { -fx-background-color: %s; -fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: %s; -fx-border-width: 0.5; -fx-padding: 4 0 4 0; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 12, 0, 0, 4); }
                    .context-menu .menu-item { -fx-padding: 4 16 4 16; }
                    .context-menu .menu-item .label { -fx-text-fill: %s; -fx-font-size: 13; }
                    .context-menu .menu-item:focused { -fx-background-color: %s; -fx-background-radius: 4; -fx-background-insets: 0 4 0 4; }
                    .context-menu .menu-item:focused .label { -fx-text-fill: %s; }
                    .context-menu .separator { -fx-padding: 4 8 4 8; }
                    .context-menu .separator .line { -fx-border-color: %s; -fx-border-width: 0.5 0 0 0; }
                    .jhostty-content-split > .split-pane-divider { -fx-background-color: %s; -fx-padding: 0 1 0 1; }
                    .jhostty-sidebar { -fx-background-color: %s; }
                    .jhostty-sidebar .tree-cell { -fx-text-fill: %s; -fx-background-color: transparent; -fx-font-size: 12; -fx-padding: 3 8 3 4; }
                    .jhostty-sidebar .tree-cell:selected { -fx-background-color: %s; -fx-text-fill: %s; }
                    .jhostty-sidebar .tree-cell:empty { -fx-background-color: transparent; }
                    .jhostty-sidebar .tree-disclosure-node .arrow { -fx-background-color: %s; }
                    """.formatted(
                        tabBarBg, tabBarBg, tabSelectedBg, tabTextCss, tabSelectedTextCss,
                        tabCloseCss, tabCloseHoverCss,
                        dividerCss, menuBgCss, borderCss, fgCss, selCss, selText, sepCss,
                        borderCss, menuBgCss, fgCss, selCss, selText, fgCss));
        } catch (IOException _) {}
    }

    static void forEachTerminal(java.util.function.Consumer<TerminalView> action) {
        for (var w : windows) {
            var tp = getTabPane(w);
            if (tp != null) { for (var tab : tp.getTabs()) forEachTerminalIn(tab.getContent(), action); }
        }
    }

    static TerminalView terminalAt(TabPane tabPane, double screenX, double screenY) {
        for (var tab : tabPane.getTabs()) {
            var content = tab.getContent();
            if (content instanceof SplitWorkspace ws) {
                for (var leaf : ws.allLeaves()) {
                    if (leaf.content() instanceof TerminalView v) {
                        var bounds = v.localToScreen(v.getBoundsInLocal());
                        if (bounds != null && bounds.contains(screenX, screenY)) return v;
                    }
                }
            } else if (content instanceof TerminalView v) {
                var bounds = v.localToScreen(v.getBoundsInLocal());
                if (bounds != null && bounds.contains(screenX, screenY)) return v;
            }
        }
        return null;
    }

    static void forEachTerminalIn(Node node, java.util.function.Consumer<TerminalView> action) {
        if (node instanceof TerminalView v) action.accept(v);
        else if (node instanceof SplitWorkspace ws) {
            for (var leaf : ws.allLeaves()) {
                if (leaf.content() instanceof TerminalView v) action.accept(v);
            }
        }
    }

    static void closeAllTerminalsIn(TabPane tp) {
        for (var tab : tp.getTabs()) closeTerminalsIn(tab.getContent());
    }

    static void closeTerminalsIn(Node node) {
        forEachTerminalIn(node, v -> Thread.ofVirtual().name("jhostty-close").start(v::close));
    }

    static String quotePath(String path) {
        if (!path.contains(" ") && !path.contains("'") && !path.contains("\"")) return path;
        if (IS_WINDOWS) return "\"" + path.replace("\"", "\\\"") + "\"";
        return "'" + path.replace("'", "'\\''") + "'";
    }

    // --- Workspace Toolbar ---

    static HBox createWorkspaceToolbar(TabPane tabs) {
        var btnLeft   = toolBtn("\u21E4", "Split Left",  () -> { var ws = activeWorkspace(); if (ws != null) ws.splitFocused(javafx.geometry.Side.LEFT); });
        var btnRight  = toolBtn("\u21E5", "Split Right", () -> { var ws = activeWorkspace(); if (ws != null) ws.splitFocused(javafx.geometry.Side.RIGHT); });
        var btnUp     = toolBtn("\u2912", "Split Up",    () -> { var ws = activeWorkspace(); if (ws != null) ws.splitFocused(javafx.geometry.Side.TOP); });
        var btnDown   = toolBtn("\u2913", "Split Down",  () -> { var ws = activeWorkspace(); if (ws != null) ws.splitFocused(javafx.geometry.Side.BOTTOM); });
        var btnClose  = toolBtn("\u2715", "Close Pane",  () -> { var ws = activeWorkspace(); if (ws != null) ws.closeFocused(); });
        var btnZoom   = toolBtn("\u2922", "Zoom Toggle", () -> { var ws = activeWorkspace(); if (ws != null) ws.toggleZoom(); });
        var btnReset  = toolBtn("\u21BA", "Reset Layout", () -> {
            // Close all but one terminal, reset to single pane
            var ws = activeWorkspace();
            if (ws != null) {
                var leaves = new java.util.ArrayList<>(ws.allLeaves());
                if (leaves.size() > 1) {
                    for (int i = 1; i < leaves.size(); i++) {
                        var leaf = leaves.get(i);
                        if (leaf.content() instanceof TerminalView tv) {
                            if (closingTerminals.add(tv))
                                Thread.ofVirtual().name("jhostty-close").start(tv::close);
                        }
                        ws.closePane(leaf);
                    }
                }
            }
        });
        var btnNewWin = toolBtn("\u2398", "New Window",  () -> newWindow());

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var toolbar = new HBox(2,
            spacer, btnLeft, btnRight, btnUp, btnDown,
            toolSep(), btnClose, btnZoom,
            toolSep(), btnReset, btnNewWin
        );
        toolbar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        toolbar.setPadding(new javafx.geometry.Insets(2, 8, 2, 8));
        toolbar.setStyle("-fx-background-color: transparent;");
        return toolbar;
    }

    static Button toolBtn(String glyph, String tooltip, Runnable action) {
        var btn = new Button(glyph);
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #999; -fx-font-size: 14; -fx-padding: 2 6; -fx-cursor: hand;");
        btn.setTooltip(new Tooltip(tooltip));
        btn.setFocusTraversable(false);
        btn.setOnAction(_ -> action.run());
        btn.setOnMouseEntered(_ -> btn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-background-radius: 4; -fx-text-fill: #ddd; -fx-font-size: 14; -fx-padding: 2 6; -fx-cursor: hand;"));
        btn.setOnMouseExited(_ -> btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #999; -fx-font-size: 14; -fx-padding: 2 6; -fx-cursor: hand;"));
        return btn;
    }

    static Separator toolSep() {
        var sep = new Separator(Orientation.VERTICAL);
        sep.setPadding(new javafx.geometry.Insets(2, 2, 2, 2));
        sep.setStyle("-fx-background-color: rgba(255,255,255,0.1);");
        return sep;
    }

    // --- Settings Panel ---

    static VBox createSettingsPanel(TabPane tabs) {
        var title = new Label("Settings");
        title.setStyle("-fx-font-size: 14; -fx-font-weight: bold; -fx-text-fill: white;");

        var pastelLabel = new Label("Pastel Opacity");
        pastelLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 11;");
        var pastelSlider = safeSlider(0, 0.5, pastelOpacity(currentTheme));
        pastelSlider.setPrefWidth(180);
        var pastelValue = new Label(String.format("%.0f%%", pastelSlider.getValue() * 100));
        pastelValue.setStyle("-fx-text-fill: #888; -fx-font-size: 10;");
        pastelSlider.valueProperty().addListener((_, _, v) -> {
            pastelValue.setText(String.format("%.0f%%", v.doubleValue() * 100));
            forEachWorkspace(tabs, ws -> ws.setPastelOpacity(v.doubleValue()));
        });
        var pastelRow = new HBox(8, pastelSlider, pastelValue);
        pastelRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var gutterLabel = new Label("Gutter Width");
        gutterLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 11;");
        var gutterSlider = safeSlider(0, 20, SplitWorkspace.GUTTER);
        gutterSlider.setPrefWidth(180);
        var gutterValue = new Label(String.format("%.0fpx", gutterSlider.getValue()));
        gutterValue.setStyle("-fx-text-fill: #888; -fx-font-size: 10;");
        gutterSlider.valueProperty().addListener((_, _, v) -> {
            gutterValue.setText(String.format("%.0fpx", v.doubleValue()));
            forEachWorkspace(tabs, ws -> ws.setGutter(v.doubleValue()));
        });
        var gutterRow = new HBox(8, gutterSlider, gutterValue);
        gutterRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var radiusLabel = new Label("Corner Radius");
        radiusLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 11;");
        var radiusSlider = safeSlider(0, 20, SplitWorkspace.PANE_RADIUS);
        radiusSlider.setPrefWidth(180);
        var radiusValue = new Label(String.format("%.0fpx", radiusSlider.getValue()));
        radiusValue.setStyle("-fx-text-fill: #888; -fx-font-size: 10;");
        radiusSlider.valueProperty().addListener((_, _, v) -> {
            radiusValue.setText(String.format("%.0fpx", v.doubleValue()));
            forEachWorkspace(tabs, ws -> ws.setPaneRadius(v.doubleValue()));
        });
        var radiusRow = new HBox(8, radiusSlider, radiusValue);
        radiusRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var headerLabel = new Label("Header Height");
        headerLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 11;");
        var headerSlider = safeSlider(0, 40, SplitWorkspace.HEADER_H);
        headerSlider.setPrefWidth(180);
        var headerValue = new Label(String.format("%.0fpx", headerSlider.getValue()));
        headerValue.setStyle("-fx-text-fill: #888; -fx-font-size: 10;");
        headerSlider.valueProperty().addListener((_, _, v) -> {
            headerValue.setText(String.format("%.0fpx", v.doubleValue()));
            forEachWorkspace(tabs, ws -> ws.setHeaderHeight(v.doubleValue()));
        });
        var headerRow = new HBox(8, headerSlider, headerValue);
        headerRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var ringLabel = new Label("Focus Ring");
        ringLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 11;");
        var ringSlider = safeSlider(0, 5, SplitWorkspace.FOCUS_RING_WIDTH);
        ringSlider.setPrefWidth(180);
        var ringValue = new Label(String.format("%.1fpx", ringSlider.getValue()));
        ringValue.setStyle("-fx-text-fill: #888; -fx-font-size: 10;");
        ringSlider.valueProperty().addListener((_, _, v) -> {
            ringValue.setText(String.format("%.1fpx", v.doubleValue()));
            forEachWorkspace(tabs, ws -> ws.setFocusRingWidth(v.doubleValue()));
        });
        var ringRow = new HBox(8, ringSlider, ringValue);
        ringRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var animLabel = new Label("Animation Speed");
        animLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 11;");
        var animSlider = safeSlider(50, 800, 300);
        animSlider.setPrefWidth(180);
        var animValue = new Label(String.format("%.0fms", animSlider.getValue()));
        animValue.setStyle("-fx-text-fill: #888; -fx-font-size: 10;");
        animSlider.valueProperty().addListener((_, _, v) -> {
            animValue.setText(String.format("%.0fms", v.doubleValue()));
            forEachWorkspace(tabs, ws -> ws.setAnimationDuration(v.doubleValue()));
        });
        var animRow = new HBox(8, animSlider, animValue);
        animRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var pastelCheck = new CheckBox("Pastel Tinting");
        pastelCheck.setSelected(true);
        pastelCheck.setStyle("-fx-text-fill: #ccc; -fx-font-size: 11;");
        pastelCheck.selectedProperty().addListener((_, _, v) -> forEachWorkspace(tabs, ws -> ws.pastelTintingProperty().set(v)));

        var animCheck = new CheckBox("Animations");
        animCheck.setSelected(true);
        animCheck.setStyle("-fx-text-fill: #ccc; -fx-font-size: 11;");
        animCheck.selectedProperty().addListener((_, _, v) -> forEachWorkspace(tabs, ws -> ws.animationsEnabledProperty().set(v)));

        var sep1 = new Separator();
        sep1.setStyle("-fx-background-color: rgba(255,255,255,0.1);");
        var sep2 = new Separator();
        sep2.setStyle("-fx-background-color: rgba(255,255,255,0.1);");

        var closeBtn = new Button("\u2715");
        closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #888; -fx-font-size: 12; -fx-padding: 0 4; -fx-cursor: hand;");
        closeBtn.setOnMouseEntered(_ -> closeBtn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-background-radius: 4; -fx-text-fill: #ccc; -fx-font-size: 12; -fx-padding: 0 4; -fx-cursor: hand;"));
        closeBtn.setOnMouseExited(_ -> closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #888; -fx-font-size: 12; -fx-padding: 0 4; -fx-cursor: hand;"));
        closeBtn.setFocusTraversable(false);

        var titleRow = new HBox(title, new Region() {{ HBox.setHgrow(this, Priority.ALWAYS); }}, closeBtn);
        titleRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var panel = new VBox(10,
            titleRow, sep1,
            pastelLabel, pastelRow,
            gutterLabel, gutterRow,
            radiusLabel, radiusRow,
            headerLabel, headerRow,
            ringLabel, ringRow,
            animLabel, animRow,
            sep2, pastelCheck, animCheck
        );
        panel.setPadding(new javafx.geometry.Insets(12));
        panel.setStyle("-fx-background-color: rgba(30,30,30,0.95); -fx-border-color: rgba(255,255,255,0.1); -fx-border-width: 0 0 0 1;");
        panel.setPrefWidth(220);
        panel.setMinWidth(220);
        closeBtn.setOnAction(_ -> { panel.setVisible(false); panel.setManaged(false); });
        return panel;
    }

    /** Slider workaround for JavaFX 26 SliderSkin NPE bug. */
    static Slider safeSlider(double min, double max, double value) {
        var slider = new Slider(min, max, value);
        slider.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            var bounds = slider.localToScene(slider.getBoundsInLocal());
            if (bounds == null) return;
            double rel = (e.getSceneX() - bounds.getMinX()) / bounds.getWidth();
            rel = Math.max(0, Math.min(1, rel));
            slider.setValue(min + rel * (max - min));
            e.consume();
        });
        return slider;
    }

    // --- Config ---

    static Path resolveConfigDir() {
        var xdg = System.getenv("XDG_CONFIG_HOME");
        var base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("jhostty");
    }

    static void loadConfig() {
        var stateFile = configDir.resolve("jhostty-state.properties");
        var userFile = configDir.resolve("jhostty.properties");
        var hasState = Files.isRegularFile(stateFile);
        var hasUser = Files.isRegularFile(userFile);
        if (!hasState && !hasUser) { debug("no config found in " + configDir); return; }
        try {
            var builder = new io.smallrye.config.SmallRyeConfigBuilder();
            if (hasState) { debug("loading state: " + stateFile); builder.withSources(new io.smallrye.config.PropertiesConfigSource(stateFile.toUri().toURL(), 100)); }
            if (hasUser) { debug("loading user config: " + userFile); builder.withSources(new io.smallrye.config.PropertiesConfigSource(userFile.toUri().toURL(), 200)); }
            var config = builder.build();
            currentThemeName = "Ghostty Default"; currentFontFamily = detectedFontFamily; baseFontSize = DEFAULT_SIZE; currentZoom = DEFAULT_SIZE;
            shellCommand = detectedShellCommand; savedWindowX = Double.NaN; savedWindowY = Double.NaN; sidebarVisible = false; savedLayout = null;
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
            for (var t : Themes.all()) { if (t.label().equals(currentThemeName)) { currentTheme = t.theme(); break; } }
            debug("config loaded: theme=" + currentThemeName + " font=" + currentFontFamily + " fontSize=" + baseFontSize + " zoom=" + currentZoom);
        } catch (Exception e) { System.err.println("[jhostty] failed to load config: " + e.getMessage()); }
    }

    static void appendProp(StringBuilder sb, String key, String current, String defaultVal) {
        if (current != null && !current.equals(defaultVal)) sb.append(key).append('=').append(current).append('\n');
        else sb.append("# ").append(key).append('=').append('\n');
    }

    static void appendProp(StringBuilder sb, String key, double current, double defaultVal) {
        if (current != defaultVal) sb.append(key).append('=').append(current).append('\n');
        else sb.append("# ").append(key).append('=').append('\n');
    }

    static void appendProp(StringBuilder sb, String key, double current) {
        if (!Double.isNaN(current)) sb.append(key).append('=').append(Math.round(current)).append('\n');
        else sb.append("# ").append(key).append('=').append('\n');
    }

    static void saveState() {
        if (configDir == null || shuttingDown) return;
        if (!windows.isEmpty()) { var w = windows.getFirst(); savedWindowX = w.getX(); savedWindowY = w.getY(); }
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
            appendProp(sb, "window-x", savedWindowX);
            appendProp(sb, "window-y", savedWindowY);
            sb.append("sidebar=").append(sidebarVisible).append('\n');
            for (var w : windows) {
                var sp = getContentSplit(w);
                if (sp != null && !sp.getDividers().isEmpty() && sidebarVisible) { sidebarDividerPos = sp.getDividerPositions()[0]; break; }
            }
            sb.append("sidebar-width=").append(String.format("%.3f", sidebarDividerPos)).append('\n');
            captureLayout();
            sb.append("layout=").append(lastGoodLayout != null ? lastGoodLayout : "").append('\n');
            var tmp = Files.createTempFile(configDir, "jhostty-state", ".tmp");
            Files.writeString(tmp, sb.toString());
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            debug("state saved: " + stateFile);
        } catch (IOException e) { System.err.println("[jhostty] failed to save state: " + e.getMessage()); }
    }

    // --- Layout ---

    static String captureLayout() {
        var sb = new StringBuilder();
        for (int wi = 0; wi < windows.size(); wi++) {
            if (wi > 0) sb.append(';');
            var w = windows.get(wi);
            if (w.getScene() == null) { sb.append("1"); continue; }
            var tp = getTabPane(w);
            if (tp == null) { sb.append("1"); continue; }
            boolean first = true;
            for (var tab : tp.getTabs()) {
                if (tab.getContent() == null) continue; // skip "+" tab
                if (!first) sb.append(',');
                first = false;
                captureNode(sb, tab.getContent());
            }
            if (first) sb.append("1"); // no real tabs
        }
        var result = sb.toString();
        if (!result.isBlank()) lastGoodLayout = result;
        debug("captureLayout: " + result);
        return result;
    }

    static void captureNode(StringBuilder sb, Node node) {
        if (node instanceof TerminalView v) {
            sb.append("1[").append(LayoutCodec.encodeCommand(getTerminalCommand(v))).append(']');
        } else if (node instanceof SplitWorkspace ws) {
            var leaves = ws.allLeaves();
            if (leaves.size() <= 1) {
                if (!leaves.isEmpty() && leaves.getFirst().content() instanceof TerminalView v) {
                    sb.append("1[").append(LayoutCodec.encodeCommand(getTerminalCommand(v))).append(']');
                } else sb.append("1");
            } else {
                // For now encode as horizontal split (simplified — full tree encoding would need SplitWorkspace to expose its tree)
                sb.append("H").append(leaves.size()).append('[');
                for (int i = 0; i < leaves.size(); i++) {
                    if (i > 0) sb.append('|');
                    if (leaves.get(i).content() instanceof TerminalView v) {
                        sb.append(LayoutCodec.encodeCommand(getTerminalCommand(v)));
                    }
                }
                sb.append(']');
            }
        } else sb.append("1");
    }

    static void restoreLayout() {
        var layout = savedLayout;
        savedLayout = null;
        if (layout == null || layout.isBlank()) { newWindow(); return; }
        debug("restoring layout: " + layout);
        for (var windowDesc : layout.split(";")) {
            var stage = newWindowEmpty();
            if (stage == null) { newWindow(); continue; }
            var tabs = getTabPane(stage);
            if (tabs == null) { newWindow(); continue; }
            for (var tabDesc : LayoutCodec.splitTabDescs(windowDesc)) restoreTab(tabs, tabDesc.trim());
            if (tabs.getTabs().stream().noneMatch(t -> t.getContent() != null)) newTab(tabs);
        }
    }

    static void restoreTab(TabPane tabPane, String desc) {
        if (desc.isEmpty()) { newTab(tabPane); return; }
        var bracketIdx = desc.indexOf('[');
        if (bracketIdx < 0) { newTab(tabPane); return; }
        var prefix = desc.substring(0, bracketIdx);
        var cmdsPart = desc.substring(bracketIdx + 1, desc.length() - 1);
        var cmds = LayoutCodec.splitCommands(cmdsPart);
        if (prefix.equals("1")) {
            // Single terminal — create a workspace with one pane
            var cmd = cmds.isEmpty() ? shellCommand : LayoutCodec.parseCommand(cmds.getFirst(), shellCommand);
            newTab(tabPane); // creates a workspace with default terminal
            // TODO: could enhance to use the saved command
        } else {
            // Multiple terminals — for now just create a tab and split
            newTab(tabPane);
            if (cmds.size() > 1) {
                var ws = activeWorkspace();
                if (ws != null) {
                    var orientation = prefix.startsWith("V") ? Orientation.VERTICAL : Orientation.HORIZONTAL;
                    var side = (orientation == Orientation.HORIZONTAL) ? javafx.geometry.Side.RIGHT : javafx.geometry.Side.BOTTOM;
                    for (int i = 1; i < cmds.size(); i++) {
                        ws.splitFocused(side);
                    }
                }
            }
        }
    }

    // --- Window Factory ---

    static Stage newWindowEmpty() {
        var tabs = new TabPane();
        tabs.getStyleClass().add("jhostty-tabs");
        tabs.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        // Equal-width tabs
        Runnable updateTabWidths = () -> {
            int count = (int) tabs.getTabs().stream().filter(t -> t.getContent() != null).count();
            if (count <= 0) count = 1;
            double tabWidth = Math.max(80, (tabs.getWidth() - 40) / count);
            tabs.setTabMaxWidth(tabWidth);
            tabs.setTabMinWidth(tabWidth);
        };
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) _ -> Platform.runLater(updateTabWidths));
        tabs.widthProperty().addListener((_, _, _) -> updateTabWidths.run());

        // "+" new tab button
        var newTabBtn = new Tab("+");
        newTabBtn.setClosable(false);
        newTabBtn.setContent(null);
        newTabBtn.setStyle("-fx-pref-width: 28; -fx-min-width: 28; -fx-max-width: 28;");
        tabs.getTabs().add(newTabBtn);
        tabs.getSelectionModel().selectedItemProperty().addListener((_, _, selected) -> {
            if (selected == newTabBtn) {
                Platform.runLater(() -> {
                    newTab(tabs);
                    tabs.getTabs().remove(newTabBtn);
                    tabs.getTabs().add(newTabBtn);
                });
            }
        });

        var menuBar = createMenuBar(tabs);
        if (IS_MAC) { menuBar.setUseSystemMenuBar(true); menuBar.setMaxHeight(0); menuBar.setPrefHeight(0); menuBar.setMinHeight(0); }

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
                    case SidebarItem.WindowItem _ ->   icon.setText("\u25A1");
                    case SidebarItem.TabItem _ ->      icon.setText("\u25AB");
                    case SidebarItem.TerminalItem _ ->  icon.setText("\u276F");
                    case SidebarItem.SectionHeader _ -> icon.setText("\u2261");
                    case SidebarItem.ZmxSessionItem s -> icon.setText(s.session().clients() > 0 ? "\u25C9" : "\u25CB");
                }
                icon.setTextFill(getTextFill());
                setGraphic(icon);
                if (item instanceof SidebarItem.TerminalItem ti && ti.view() == activeTerminal) setStyle("-fx-font-weight: bold;");
                else if (item instanceof SidebarItem.SectionHeader) setStyle("-fx-font-weight: bold; -fx-font-size: 11;");
                else setStyle(null);
            }
        });
        sidebar.setOnMouseClicked(e -> {
            if (e.getClickCount() < 2) return;
            var sel = sidebar.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            switch (sel.getValue()) {
                case SidebarItem.TerminalItem ti -> {
                    var stg = findStageFor(ti.view()); if (stg != null) { stg.toFront(); stg.requestFocus(); }
                    var tp2 = findTabPane(ti.view());
                    if (tp2 != null) { var tab = findTab(ti.view()); if (tab != null) tp2.getSelectionModel().select(tab); }
                    Platform.runLater(() -> ti.view().requestFocus());
                }
                case SidebarItem.TabItem ti -> {
                    var tp2 = findTabPaneForTab(ti.tab());
                    if (tp2 != null) { tp2.getSelectionModel().select(ti.tab()); var stg = findStage(tp2); if (stg != null) { stg.toFront(); stg.requestFocus(); } Platform.runLater(() -> focusFirstTerminal(ti.tab().getContent())); }
                }
                case SidebarItem.WindowItem wi -> { wi.stage().toFront(); wi.stage().requestFocus(); }
                case SidebarItem.ZmxSessionItem zi -> {
                    var existing = findTerminalForZmxSession(zi.session().name());
                    if (existing != null) {
                        var stg = findStageFor(existing); if (stg != null) { stg.toFront(); stg.requestFocus(); }
                        var tp2 = findTabPane(existing); if (tp2 != null) { var tab = findTab(existing); if (tab != null) tp2.getSelectionModel().select(tab); }
                        Platform.runLater(existing::requestFocus);
                    } else attachZmxSession(zi.session().name());
                }
                case SidebarItem.SectionHeader _ -> {}
            }
        });

        var dividerColor = dividerColor(currentTheme.background());

        var contentSplit = new SplitPane(tabs);
        contentSplit.setOrientation(Orientation.HORIZONTAL);
        contentSplit.getStyleClass().add("jhostty-content-split");

        var root = new BorderPane();
        root.setStyle("-fx-background-color: " + colorToCss(dividerColor) + ";");

        var toolbar = createWorkspaceToolbar(tabs);
        toolbar.setPickOnBounds(false);
        toolbar.setStyle("-fx-background-color: transparent;");
        toolbar.setMaxHeight(28);
        toolbar.setMaxWidth(Region.USE_PREF_SIZE);

        var titleBarHeight = 28.0;

        var titleLabel = new Label("jhostty");
        titleLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.6); -fx-font-size: 12;");
        titleLabel.setMouseTransparent(true);

        var trafficSpacer = new Region();
        trafficSpacer.setMinWidth(78);
        trafficSpacer.setMouseTransparent(true);
        var leftSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);
        leftSpacer.setMouseTransparent(true);
        var rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);
        rightSpacer.setMouseTransparent(true);

        var shortcutHint = new Label(IS_MAC ? "\u2318T  new tab" : "Ctrl+T  new tab");
        shortcutHint.setStyle("-fx-text-fill: rgba(255,255,255,0.65); -fx-font-size: 11; -fx-padding: 0 12 0 8;");
        shortcutHint.setMouseTransparent(true);
        shortcutHint.setMinWidth(Region.USE_PREF_SIZE);

        var titleBar = new HBox(4, trafficSpacer, leftSpacer, titleLabel, rightSpacer, toolbar, shortcutHint);
        titleBar.setAlignment(javafx.geometry.Pos.CENTER);
        titleBar.setPadding(new javafx.geometry.Insets(0, 4, 0, 4));
        titleBar.setStyle("-fx-background-color: transparent;");
        titleBar.setPrefHeight(titleBarHeight);
        titleBar.setMinHeight(titleBarHeight);
        titleBar.setMaxHeight(titleBarHeight);

        root.setTop(new VBox(titleBar, menuBar));
        root.setCenter(contentSplit);

        // Settings panel (hidden by default, toggled with Cmd+,)
        var settingsPanel = createSettingsPanel(tabs);
        settingsPanel.setVisible(false);
        settingsPanel.setManaged(false);
        root.setRight(settingsPanel);

        var scene = new Scene(root, 1000, 700);
        scene.setFill(dividerColor);
        if (cssUrl != null) scene.getStylesheets().add(cssUrl);

        updateTabBarVisibility(tabs);
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) _ -> { updateTabBarVisibility(tabs); rebuildAllSidebars(); });
        tabs.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> rebuildAllSidebars());

        var stage = new Stage();
        stage.initStyle(javafx.stage.StageStyle.EXTENDED);
        stage.setTitle("jhostty");
        stage.setScene(scene);
        titleLabel.textProperty().bind(stage.titleProperty());

        // Window drag via title bar
        final double[] dragOffset = new double[2];
        titleBar.setOnMousePressed(e -> {
            dragOffset[0] = e.getScreenX() - stage.getX();
            dragOffset[1] = e.getScreenY() - stage.getY();
        });
        titleBar.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragOffset[0]);
            stage.setY(e.getScreenY() - dragOffset[1]);
        });
        titleBar.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) stage.setMaximized(!stage.isMaximized());
        });

        stage.setOnCloseRequest(_ -> closeAllTerminalsIn(tabs));
        stage.setOnHidden(_ -> {
            savedWindowX = stage.getX(); savedWindowY = stage.getY();
            windows.remove(stage); windowMenus.removeIf(m -> m.getParentMenu() == null && m.getParentPopup() == null);
            sidebarsByWindow.remove(stage); rebuildWindowMenus(); rebuildAllSidebars();
            if (windows.isEmpty()) { if (!shuttingDown) saveState(); Platform.exit(); }
        });

        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (debug && e.isShortcutDown()) debug("KeyEvent: code=" + e.getCode() + " meta=" + e.isMetaDown() + " ctrl=" + e.isControlDown() + " shift=" + e.isShiftDown());
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
                case ENTER -> { if (e.isShiftDown()) { var ws = activeWorkspace(); if (ws != null) ws.toggleZoom(); e.consume(); } }
                case COMMA -> {
                    var bp = (BorderPane) tabs.getScene().getRoot();
                    var sp = (Node) bp.getRight();
                    if (sp != null) { var vis = !sp.isVisible(); sp.setVisible(vis); sp.setManaged(vis); }
                    e.consume();
                }
                case TAB -> { if (!e.isShiftDown()) { var ws = activeWorkspace(); if (ws != null) { ws.focusNext(); e.consume(); } } }
                case LEFT -> { if (e.isShiftDown()) { var ws = activeWorkspace(); if (ws != null) { ws.resizeFocused(javafx.geometry.Side.LEFT, 16); e.consume(); } } }
                case RIGHT -> { if (e.isShiftDown()) { var ws = activeWorkspace(); if (ws != null) { ws.resizeFocused(javafx.geometry.Side.RIGHT, 16); e.consume(); } } }
                case UP -> { if (e.isShiftDown()) { var ws = activeWorkspace(); if (ws != null) { ws.resizeFocused(javafx.geometry.Side.TOP, 16); e.consume(); } } }
                case DOWN -> { if (e.isShiftDown()) { var ws = activeWorkspace(); if (ws != null) { ws.resizeFocused(javafx.geometry.Side.BOTTOM, 16); e.consume(); } } }
                default -> {}
            }
        });
        scene.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isShortcutDown() && e.getDeltaY() != 0) {
                var target = terminalAt(tabs, e.getScreenX(), e.getScreenY());
                if (target == null) target = activeTerminal;
                if (target != null) { zoomTerminal(target, e.getDeltaY() > 0 ? 1 : -1); e.consume(); }
            }
        });
        scene.addEventFilter(ZoomEvent.ZOOM, e -> {
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
        if (sidebarVisible) { showSidebarIn(contentSplit, sidebar); rebuildAllSidebars(); }
        rebuildWindowMenus();
        return stage;
    }

    // --- zmx session management ---

    static void initZmx() {
        zmxAvailable = ShellDetection.resolveExecutable("zmx") != null;
        if (!zmxAvailable) { debug("zmx not found in PATH"); return; }
        debug("zmx found, starting session refresh");
        refreshZmxSessions();
        zmxRefreshTimer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(5), _ -> refreshZmxSessions()));
        zmxRefreshTimer.setCycleCount(Animation.INDEFINITE);
        zmxRefreshTimer.play();
    }

    static void refreshZmxSessions() {
        Thread.ofVirtual().name("zmx-list").start(() -> {
            try {
                var pb = new ProcessBuilder("zmx", "list");
                pb.redirectErrorStream(true);
                var proc = pb.start();
                var output = new String(proc.getInputStream().readAllBytes()).trim();
                if (!proc.waitFor(5, TimeUnit.SECONDS)) { proc.destroyForcibly(); return; }
                var sessions = ZmxSession.parseZmxList(output);
                Platform.runLater(() -> { zmxSessions = sessions; rebuildAllSidebars(); });
            } catch (Exception e) { debug("zmx list failed: " + e.getMessage()); }
        });
    }

    static TerminalView findTerminalForZmxSession(String sessionName) {
        var result = new ArrayList<TerminalView>();
        forEachTerminal(v -> {
            var cmd = getTerminalCommand(v);
            if (cmd.size() >= 3 && cmd.get(cmd.size() - 2).equals("attach") && cmd.getLast().equals(sessionName) && Path.of(cmd.getFirst()).getFileName().toString().equals("zmx")) result.add(v);
        });
        return result.isEmpty() ? null : result.getFirst();
    }

    static void attachZmxSession(String sessionName) {
        var zmxPath = ShellDetection.resolveExecutable("zmx");
        if (zmxPath == null) return;
        var cmd = List.of(zmxPath.toString(), "attach", sessionName);
        var tabPane = findActiveTabPane();
        if (tabPane == null) return;
        // Create terminal in a new workspace tab
        var view = createTerminalClean(cmd);
        if (view == null) return;
        var workspace = SplitWorkspace.createSingle(() -> view);
        workspace.setContentFactory(() -> createTerminal());
        workspace.setPaneBackground(currentTheme.background());
        workspace.setFocusRingColor(focusRingColor(currentTheme));
        workspace.setPastelOpacity(pastelOpacity(currentTheme));
        workspace.setOnEmpty(() -> Platform.runLater(() -> {
            var tp = findTabPane(workspace);
            var stg = tp != null ? findStage(tp) : null;
            var t = findTab(workspace);
            if (t != null && tp != null) tabPane.getTabs().remove(t);
            if (tp != null && tp.getTabs().stream().noneMatch(tab -> tab.getContent() != null) && stg != null) stg.close();
        }));
        workspace.focusedPaneProperty().addListener((_, _, pane) -> {
            if (pane != null && pane.content() instanceof TerminalView tv) {
                activeTerminal = tv;
                var stg = findStageFor(tv);
                if (stg != null) { stg.setTitle(tv.getTitle() != null ? tv.getTitle() : "jhostty"); rebuildWindowMenus(); }
                rebuildAllSidebars();
            }
        });
        var tab = new Tab();
        tab.setText(commandBaseName(cmd));
        tab.setContent(workspace);
        tab.setClosable(true);
        tab.setOnClosed(_ -> closeTerminalsIn(workspace));
        // Insert before "+" tab
        var plusIdx = -1;
        for (int i = 0; i < tabPane.getTabs().size(); i++) {
            if (tabPane.getTabs().get(i).getContent() == null && "+".equals(tabPane.getTabs().get(i).getText())) {
                plusIdx = i;
                break;
            }
        }
        if (plusIdx >= 0) tabPane.getTabs().add(plusIdx, tab);
        else tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        Platform.runLater(JHostty::saveState);
    }

    static TabPane findActiveTabPane() {
        if (activeTerminal != null) { var tp = findTabPane(activeTerminal); if (tp != null) return tp; }
        if (!windows.isEmpty()) return getTabPane(windows.getFirst());
        return null;
    }
}
