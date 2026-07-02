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
import javafx.scene.layout.BorderPane;
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
        restoreLayout();
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

        var viewMenu = new Menu("View", null, toggleSidebarItem, new SeparatorMenuItem(), zoomIn, zoomOut, zoomReset, new SeparatorMenuItem(), themeMenu, fontMenu, new SeparatorMenuItem(), reloadConfig);

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
        var view = createTerminal();
        if (view == null) return;
        var tab = new Tab();
        bindTabTitle(tab, view);
        tab.setContent(view);
        tab.setClosable(true);
        tab.setOnClosed(_ -> closeTerminalsIn(view));
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
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
        var hide = tp.getTabs().size() <= 1;
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
        else if (node instanceof SplitPane sp && !sp.getItems().isEmpty()) focusFirstTerminal(sp.getItems().getFirst());
    }

    static void addTerminalNodes(TreeItem<SidebarItem> parent, Node node) {
        if (node instanceof TerminalView v) {
            parent.getChildren().add(new TreeItem<>(new SidebarItem.TerminalItem(v, v.getTitle(), getTerminalCommand(v))));
        } else if (node instanceof SplitPane sp) {
            for (var item : sp.getItems()) addTerminalNodes(parent, item);
        }
    }

    // --- Split Management ---

    static void splitActive(Orientation orientation) {
        if (activeTerminal != null) split(activeTerminal, orientation);
    }

    static void split(TerminalView existing, Orientation orientation) {
        var newView = createTerminal();
        if (newView == null) return;
        var sp = findParentSplitPane(existing);
        if (sp != null) {
            var items = sp.getItems();
            int idx = items.indexOf(existing);
            if (idx >= 0) {
                if (sp.getOrientation() == orientation) {
                    items.add(idx + 1, newView);
                    evenDividers(sp);
                } else {
                    var nested = new SplitPane(existing, newView);
                    nested.setOrientation(orientation);
                    items.set(idx, nested);
                    evenDividers(sp);
                }
            }
        } else {
            var tab = findTab(existing);
            if (tab != null) {
                var newSp = new SplitPane(existing, newView);
                newSp.setOrientation(orientation);
                tab.setContent(newSp);
            }
        }
        Platform.runLater(newView::requestFocus);
    }

    static void evenDividers(SplitPane sp) {
        int n = sp.getItems().size();
        if (n <= 1) return;
        double[] positions = new double[n - 1];
        for (int i = 0; i < positions.length; i++) positions[i] = (i + 1.0) / n;
        Platform.runLater(() -> sp.setDividerPositions(positions));
    }

    // --- Close Management ---

    static void closeActive(TabPane tabPane, Stage stage) {
        if (activeTerminal != null) removeTerminal(activeTerminal, tabPane, stage);
    }

    static void removeTerminal(TerminalView view, TabPane tabPane, Stage stage) {
        if (!closingTerminals.add(view)) return;
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
        if (container instanceof SplitPane sp) {
            for (var item : sp.getItems()) {
                if (containsNode(item, target)) return true;
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
        writeCss();
        for (var w : windows) {
            var sheets = w.getScene().getStylesheets();
            if (sheets.contains(cssUrl)) { sheets.remove(cssUrl); sheets.add(cssUrl); }
        }
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
        try {
            Files.writeString(cssPath, """
                    .single-tab > .tab-header-area { -fx-max-height: 0; -fx-pref-height: 0; -fx-min-height: 0; visibility: hidden; }
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
                    """.formatted(dividerCss, menuBgCss, borderCss, fgCss, selCss, selText, sepCss,
                            borderCss, menuBgCss, fgCss, selCss, selText, fgCss));
        } catch (IOException _) {}
    }

    static void forEachTerminal(java.util.function.Consumer<TerminalView> action) {
        for (var w : windows) {
            var tp = getTabPane(w);
            if (tp != null) { for (var tab : tp.getTabs()) forEachTerminalIn(tab.getContent(), action); }
        }
    }

    static SplitPane findParentSplitPane(Node node) {
        var p = node.getParent();
        while (p != null) {
            if (p instanceof SplitPane sp && sp.getItems().contains(node) && !sp.getStyleClass().contains("jhostty-content-split")) return sp;
            if (p.getParent() instanceof SplitPane sp && sp.getItems().contains(node) && !sp.getStyleClass().contains("jhostty-content-split")) return sp;
            node = p;
            p = p.getParent();
        }
        return null;
    }

    static TerminalView terminalAt(TabPane tabPane, double screenX, double screenY) {
        for (var tab : tabPane.getTabs()) {
            var hit = terminalAtIn(tab.getContent(), screenX, screenY);
            if (hit != null) return hit;
        }
        return null;
    }

    static TerminalView terminalAtIn(Node node, double screenX, double screenY) {
        if (node instanceof TerminalView v) {
            var bounds = v.localToScreen(v.getBoundsInLocal());
            return bounds != null && bounds.contains(screenX, screenY) ? v : null;
        }
        if (node instanceof SplitPane sp) {
            for (var item : sp.getItems()) { var hit = terminalAtIn(item, screenX, screenY); if (hit != null) return hit; }
        }
        return null;
    }

    static void forEachTerminalIn(Node node, java.util.function.Consumer<TerminalView> action) {
        if (node instanceof TerminalView v) action.accept(v);
        else if (node instanceof SplitPane sp) sp.getItems().forEach(n -> forEachTerminalIn(n, action));
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

    static void captureNode(StringBuilder sb, Node node) {
        if (node instanceof TerminalView v) {
            sb.append("1[").append(LayoutCodec.encodeCommand(getTerminalCommand(v))).append(']');
        } else if (node instanceof SplitPane sp) {
            var orient = sp.getOrientation() == Orientation.VERTICAL ? "V" : "H";
            var terminals = new ArrayList<TerminalView>();
            collectTerminals(sp, terminals);
            sb.append(orient).append(terminals.size()).append('[');
            for (int i = 0; i < terminals.size(); i++) {
                if (i > 0) sb.append('|');
                sb.append(LayoutCodec.encodeCommand(getTerminalCommand(terminals.get(i))));
            }
            sb.append(']');
        } else sb.append("1");
    }

    static void collectTerminals(Node node, List<TerminalView> out) {
        if (node instanceof TerminalView v) out.add(v);
        else if (node instanceof SplitPane sp) sp.getItems().forEach(n -> collectTerminals(n, out));
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
            if (tabs.getTabs().isEmpty()) newTab(tabs);
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
            var cmd = cmds.isEmpty() ? shellCommand : LayoutCodec.parseCommand(cmds.getFirst(), shellCommand);
            var view = createTerminalClean(cmd);
            if (view == null) return;
            var tab = new Tab();
            bindTabTitle(tab, view);
            tab.setContent(view); tab.setClosable(true); tab.setOnClosed(_ -> closeTerminalsIn(view));
            tabPane.getTabs().add(tab); tabPane.getSelectionModel().select(tab);
        } else {
            var orientation = prefix.startsWith("V") ? Orientation.VERTICAL : Orientation.HORIZONTAL;
            if (cmds.size() < 2) { newTab(tabPane); return; }
            var views = new ArrayList<TerminalView>();
            for (var cmdStr : cmds) {
                var cmd = LayoutCodec.parseCommand(cmdStr, shellCommand);
                var v = createTerminalClean(cmd);
                if (v != null) views.add(v);
            }
            if (views.isEmpty()) { newTab(tabPane); return; }
            if (views.size() == 1) {
                var tab = new Tab();
                bindTabTitle(tab, views.getFirst());
                tab.setContent(views.getFirst()); tab.setClosable(true);
                tab.setOnClosed(_ -> closeTerminalsIn(views.getFirst()));
                tabPane.getTabs().add(tab);
            } else {
                var sp = new SplitPane();
                sp.setOrientation(orientation);
                views.forEach(v -> sp.getItems().add(v));
                evenDividers(sp);
                var tab = new Tab();
                bindTabTitle(tab, views.getFirst());
                tab.setContent(sp); tab.setClosable(true); tab.setOnClosed(_ -> closeTerminalsIn(sp));
                tabPane.getTabs().add(tab);
            }
        }
    }

    // --- Window Factory ---

    static Stage newWindowEmpty() {
        var tabs = new TabPane();
        tabs.getStyleClass().add("jhostty-tabs");
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

        var contentSplit = new SplitPane(tabs);
        contentSplit.setOrientation(Orientation.HORIZONTAL);
        contentSplit.getStyleClass().add("jhostty-content-split");
        var root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(contentSplit);
        var scene = new Scene(root, 1000, 700);
        if (cssUrl != null) scene.getStylesheets().add(cssUrl);
        updateTabBarVisibility(tabs);
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) _ -> { updateTabBarVisibility(tabs); rebuildAllSidebars(); });
        tabs.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> rebuildAllSidebars());

        var stage = new Stage();
        stage.setTitle("jhostty");
        stage.setScene(scene);
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
        var view = createTerminalClean(cmd);
        if (view == null) return;
        var tab = new Tab();
        bindTabTitle(tab, view);
        tab.setContent(view); tab.setClosable(true); tab.setOnClosed(_ -> closeTerminalsIn(view));
        tabPane.getTabs().add(tab); tabPane.getSelectionModel().select(tab);
        Platform.runLater(JHostty::saveState);
    }

    static TabPane findActiveTabPane() {
        if (activeTerminal != null) { var tp = findTabPane(activeTerminal); if (tp != null) return tp; }
        if (!windows.isEmpty()) return getTabPane(windows.getFirst());
        return null;
    }
}
