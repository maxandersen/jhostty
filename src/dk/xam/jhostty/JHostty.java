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
    static final Map<Stage, Node> sidebarPanelsByWindow = new ConcurrentHashMap<>();
    static boolean sidebarRebuildScheduled = false;

    // zmx
    static List<ZmxSession> zmxSessions = List.of();
    static volatile boolean zmxAvailable = false;
    static Timeline zmxRefreshTimer;

    // Layout
    static String lastGoodLayout = null;


    // Shared focus-follows-mouse state
    static final javafx.beans.property.BooleanProperty focusFollowsMouse = new javafx.beans.property.SimpleBooleanProperty(true);
    static {
        focusFollowsMouse.addListener((_, _, v) -> {
            for (var w : windows) {
                var tp = getTabPane(w);
                if (tp != null) { for (var t : tp.getTabs()) { if (t.getContent() instanceof SplitWorkspace ws) ws.focusFollowsMouseProperty().set(v); } }
            }
        });
    }

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
        // Suppress noisy JavaFX VirtualFlow log
        java.util.logging.Logger.getLogger("javafx.scene.control.skin.VirtualFlow").setLevel(java.util.logging.Level.WARNING);
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
        newTabItem.setOnAction(_ -> newTabNext(tabs));

        var splitH = new MenuItem("Add Column");
        splitH.setAccelerator(KeyCombination.keyCombination("Shortcut+D"));
        splitH.setOnAction(_ -> splitActive(Orientation.VERTICAL));

        var splitV = new MenuItem("Add Row");
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

        var showAllTabs = new MenuItem("Show All Tabs");
        showAllTabs.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+T"));
        showAllTabs.setOnAction(_ -> toggleTabOverview(tabs));

        var shellMenu = new Menu("Shell", null, newWindowItem, newTabItem, showAllTabs, splitH, splitV, new SeparatorMenuItem(), zmxMenu, new SeparatorMenuItem(), closeItem);

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
        toggleSidebarItem.setAccelerator(new KeyCodeCombination(KeyCode.SLASH, KeyCombination.SHORTCUT_DOWN));
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

        var focusFollowsToggle = new CheckMenuItem("Focus Follows Mouse");
        focusFollowsToggle.selectedProperty().bindBidirectional(focusFollowsMouse);

        var settingsToggle = new MenuItem("Toggle Settings Panel");
        settingsToggle.setAccelerator(KeyCombination.keyCombination("Shortcut+,"));
        settingsToggle.setOnAction(_ -> {
            var bp = getRootPane(findStage(tabs));
            if (bp != null) { var sp = bp.getRight(); if (sp != null) { var vis = !sp.isVisible(); sp.setVisible(vis); sp.setManaged(vis); } }
        });

        var viewMenu = new Menu("View", null, toggleSidebarItem, new SeparatorMenuItem(), zoomIn, zoomOut, zoomReset, new SeparatorMenuItem(), themeMenu, fontMenu, new SeparatorMenuItem(), animToggle, pastelToggle, focusFollowsToggle, new SeparatorMenuItem(), settingsToggle, reloadConfig);

        // Help menu
        var helpItem = new MenuItem("jhostty Help");
        helpItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+/"));
        helpItem.setOnAction(_ -> showHelp());
        var aboutItem = new MenuItem("About jhostty");
        aboutItem.setOnAction(_ -> showHelp());
        var helpMenu = new Menu("Help", null, helpItem, aboutItem);

        return new MenuBar(shellMenu, viewMenu, windowMenu, helpMenu);
    }

    // --- Context Menu ---

    static ContextMenu createContextMenu(TerminalView view) {
        var sc = SHORTCUT_SYMBOL;
        var sh = SHIFT_SYMBOL;
        var newWindowItem = new MenuItem("New Window           " + sc + "N");
        newWindowItem.setOnAction(_ -> newWindow());
        var newTabItem = new MenuItem("New Tab                 " + sc + "T");
        newTabItem.setOnAction(_ -> newTab(findTabPane(view)));
        var splitH = new MenuItem("Add Column    " + sc + "D");
        splitH.setOnAction(_ -> split(view, Orientation.VERTICAL));
        var splitV = new MenuItem("Add Row        " + sc + sh + "D");
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
        var toggleSidebar = new MenuItem("Toggle Sidebar       " + sc + "/");
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
        workspace.focusFollowsMouseProperty().set(focusFollowsMouse.get());
        workspace.setStyle("-fx-background-color: " + colorToCss(dividerColor(currentTheme.background())) + ";");
        // Close tab/window when last pane is dragged out
        workspace.setOnEmpty(() -> Platform.runLater(() -> {
            var tp = findTabPane(workspace);
            var stg = tp != null ? findStage(tp) : null;
            var t = findTab(workspace);
            if (t != null && tp != null) tabPane.getTabs().remove(t);
            if (tp != null && tp.getTabs().isEmpty() && stg != null) stg.close();
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
        setupTabGraphic(tab, tabPane);

        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);

        // Update tab title when workspace focus changes
        workspace.focusedPaneProperty().addListener((_, _, pane) -> {
            if (pane != null && pane.content() instanceof TerminalView tv) {
                tab.textProperty().unbind();
                tab.textProperty().bind(tv.titleProperty());
            }
        });
        Platform.runLater(JHostty::saveState);
    }

    /** Insert a new tab right after the currently selected tab. */
    static void newTabNext(TabPane tabPane) {
        var selected = tabPane.getSelectionModel().getSelectedItem();
        var idx = selected != null ? tabPane.getTabs().indexOf(selected) : -1;
        // Create the workspace tab
        var workspace = SplitWorkspace.createSingle(() -> createTerminal());
        workspace.setContentFactory(() -> createTerminal());
        workspace.setPaneBackground(currentTheme.background());
        workspace.setFocusRingColor(focusRingColor(currentTheme));
        workspace.setPastelOpacity(pastelOpacity(currentTheme));
        workspace.focusFollowsMouseProperty().set(focusFollowsMouse.get());
        workspace.setStyle("-fx-background-color: " + colorToCss(dividerColor(currentTheme.background())) + ";");
        workspace.setOnEmpty(() -> Platform.runLater(() -> {
            var tp = findTabPane(workspace);
            var stg = tp != null ? findStage(tp) : null;
            var t = findTab(workspace);
            if (t != null && tp != null) tp.getTabs().remove(t);
            if (tp != null && tp.getTabs().isEmpty() && stg != null) stg.close();
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
        tab.setText("jhostty");
        tab.setContent(workspace);
        setupTabGraphic(tab, tabPane);
        if (idx >= 0 && idx + 1 < tabPane.getTabs().size()) {
            tabPane.getTabs().add(idx + 1, tab);
        } else {
            tabPane.getTabs().add(tab);
        }
        tabPane.getSelectionModel().select(tab);
        workspace.focusedPaneProperty().addListener((_, _, pane) -> {
            if (pane != null && pane.content() instanceof TerminalView tv) {
                tab.textProperty().unbind();
                tab.textProperty().bind(tv.titleProperty());
            }
        });
        Platform.runLater(JHostty::saveState);
    }

    /** Set up tab with close handler and deferred + button injection. */
    static void setupTabGraphic(Tab tab, TabPane tabPane) {
        tab.setClosable(true);
        tab.setOnClosed(_ -> {
            closeTerminalsIn(tab.getContent());
            if (tabPane.getTabs().isEmpty()) {
                var stg = findStage(tabPane);
                if (stg != null) stg.close();
            }
        });
        // Inject + button after skin is created (needs triple runLater)
        Platform.runLater(() -> Platform.runLater(() -> Platform.runLater(() -> injectAddButton(tab, tabPane))));
    }

    private static void injectAddButton(Tab tab, TabPane tabPane) {
        if (tab.getProperties().containsKey("jhostty.addBtn")) return;
        // Find this tab's header node via lookup — each .tab has a .tab-container
        var allTabHeaders = tabPane.lookupAll(".tab");
        var tabIdx = tabPane.getTabs().indexOf(tab);
        int i = 0;
        for (var tabHeader : allTabHeaders) {
            if (i++ != tabIdx) continue;
            // Found our tab header
            var container = tabHeader.lookup(".tab-container");
            if (container instanceof javafx.scene.layout.Pane pane) {
                var addBtn = new Label("+");
                var hidden = "-fx-text-fill: transparent; -fx-font-size: 14; -fx-cursor: hand; -fx-padding: 0 0 0 2;";
                var subtle = "-fx-text-fill: rgba(255,255,255,0.4); -fx-font-size: 14; -fx-cursor: hand; -fx-padding: 0 0 0 2;";
                var bright = "-fx-text-fill: rgba(255,255,255,0.9); -fx-font-size: 14; -fx-cursor: hand; -fx-padding: 0 0 0 2;";
                addBtn.setStyle(hidden);
                addBtn.setOnMouseEntered(_ -> addBtn.setStyle(bright));
                addBtn.setOnMouseExited(_ -> { if (tabHeader.isHover()) addBtn.setStyle(subtle); else addBtn.setStyle(hidden); });
                addBtn.setOnMouseClicked(e -> { newTabNext(tabPane); e.consume(); });
                tabHeader.setOnMouseEntered(_ -> addBtn.setStyle(subtle));
                tabHeader.setOnMouseExited(_ -> { if (!addBtn.isHover()) addBtn.setStyle(hidden); });
                pane.getChildren().add(addBtn);
                tab.getProperties().put("jhostty.addBtn", addBtn);
            }
            return;
        }
    }

    /** Find a TabPane (other than exclude) whose tab-header-area contains the screen point. */
    /** Find a TabPane (other than exclude) whose window contains the screen point. */

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
            var bp = getRootPane(w);
            if (bp != null && bp.getCenter() instanceof SplitPane sp) {
                var panel = sidebarPanelsByWindow.get(w);
                if (panel == null) continue;
                if (sidebarVisible) showSidebarIn(sp, panel);
                else hideSidebarIn(sp, panel);
            }
        }
        if (sidebarVisible) rebuildAllSidebars();
    }

    static BorderPane getRootPane(Stage w) {
        if (w.getScene() == null) return null;
        var sceneRoot = w.getScene().getRoot();
        if (sceneRoot instanceof BorderPane bp) return bp;
        if (sceneRoot instanceof javafx.scene.layout.StackPane sp && !sp.getChildren().isEmpty()
                && sp.getChildren().getFirst() instanceof BorderPane bp) return bp;
        return null;
    }

    static TabPane getTabPane(Stage w) {
        var bp = getRootPane(w);
        if (bp == null) return null;
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
        var bp = getRootPane(w);
        if (bp != null && bp.getCenter() instanceof SplitPane sp) return sp;
        return null;
    }

    static void showSidebarIn(SplitPane sp, Node sidebarPanel) {
        if (!sp.getItems().contains(sidebarPanel)) {
            sp.getItems().addFirst(sidebarPanel);
            Platform.runLater(() -> sp.setDividerPositions(sidebarDividerPos));
        }
    }

    static void hideSidebarIn(SplitPane sp, Node sidebarPanel) {
        if (sp.getItems().contains(sidebarPanel)) {
            if (!sp.getDividers().isEmpty()) sidebarDividerPos = sp.getDividerPositions()[0];
            sp.getItems().remove(sidebarPanel);
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
        var divColor = dividerColor(currentTheme.background());
        var divColorCss = colorToCss(divColor);
        var windowBgCss = colorToCss(currentTheme.background());
        // Update workspace pane backgrounds to match theme
        for (var w : windows) {
            var tp = getTabPane(w);
            if (tp != null) {
                for (var tab : tp.getTabs()) {
                    if (tab.getContent() instanceof SplitWorkspace ws) {
                        ws.setPaneBackground(currentTheme.background());
                        ws.setFocusRingColor(focusRingColor(currentTheme));
                        ws.setPastelOpacity(pastelOpacity(currentTheme));
                        ws.setStyle("-fx-background-color: " + divColorCss + ";");
                    }
                }
            }
        }
        writeCss();
        for (var w : windows) {
            w.getScene().setFill(currentTheme.background());
            var bpTheme = getRootPane(w);
            if (bpTheme != null) {
                bpTheme.setStyle("-fx-background-color: " + windowBgCss + ";"  );
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
        return Color.TRANSPARENT;
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
        var tabBarColor = dark ? bg.darker() : bg.darker();
        var tabBarBg = colorToCss(tabBarColor);
        var tabSelectedColor = dark ? bg.brighter() : bg.brighter();
        var tabSelectedBg = colorToCss(tabSelectedColor);
        var tabTextCss = colorToCss(fg.deriveColor(0, 1, 1, 0.5));
        var tabSelectedTextCss = colorToCss(fg.deriveColor(0, 1, 1, 0.85));
        var tabCloseCss = colorToCss(fg.deriveColor(0, 1, 1, 0.3));
        var tabCloseHoverCss = colorToCss(fg.deriveColor(0, 1, 1, 0.7));
        try {
            Files.writeString(cssPath, """
                    .single-tab > .tab-header-area { -fx-max-height: 0; -fx-pref-height: 0; -fx-min-height: 0; visibility: hidden; }
                    .tab-pane > .tab-header-area > .control-buttons-tab { visibility: hidden; -fx-padding: 0; -fx-max-width: 0; }
                    .tab-pane { -fx-background-color: %s; }
                    .tab-pane > .tab-content-area { -fx-background-color: %s; }
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
                    .jhostty-content-split { -fx-background-color: %s; }
                    .jhostty-content-split > .split-pane-divider { -fx-background-color: %s; -fx-padding: 0 1 0 1; }
                    .jhostty-sidebar { -fx-background-color: %s; }
                    .jhostty-sidebar .label { -fx-text-fill: %s; }
                    .jhostty-sidebar .separator .line { -fx-border-color: %s; -fx-border-width: 0.5 0 0 0; }
                    .jhostty-sidebar .tree-cell { -fx-text-fill: %s; -fx-background-color: transparent; -fx-font-size: 12; -fx-padding: 3 8 3 4; }
                    .jhostty-sidebar .tree-cell:selected { -fx-background-color: %s; -fx-text-fill: %s; }
                    .jhostty-sidebar .tree-cell:empty { -fx-background-color: transparent; }
                    .jhostty-sidebar .tree-disclosure-node .arrow { -fx-background-color: %s; }
                    .scroll-bar { -fx-background-color: transparent; -fx-padding: 0; }
                    .scroll-bar .track { -fx-background-color: transparent; -fx-border-color: transparent; -fx-background-radius: 0; }
                    .scroll-bar .thumb { -fx-background-color: %s; -fx-background-radius: 4; -fx-background-insets: 1; }
                    .scroll-bar .thumb:hover { -fx-background-color: %s; }
                    .scroll-bar .increment-button, .scroll-bar .decrement-button { -fx-padding: 0; -fx-background-color: transparent; }
                    .scroll-bar .increment-arrow, .scroll-bar .decrement-arrow { -fx-padding: 0; -fx-shape: ""; }
                    .scroll-bar:vertical { -fx-pref-width: 6; }
                    .scroll-bar:horizontal { -fx-pref-height: 6; }
                    .scroll-bar:vertical:hover { -fx-pref-width: 8; }
                    .scroll-bar:horizontal:hover { -fx-pref-height: 8; }
                    .jhostty-settings { -fx-background-color: %s; -fx-border-color: %s; -fx-border-width: 0 0 0 1; }
                    .jhostty-settings .label { -fx-text-fill: %s; }
                    .jhostty-settings .check-box { -fx-text-fill: %s; }
                    .jhostty-settings .check-box .box { -fx-background-color: %s; -fx-border-color: %s; -fx-border-radius: 3; -fx-background-radius: 3; }
                    .jhostty-settings .check-box:selected .box .mark { -fx-background-color: %s; }
                    .jhostty-settings .separator .line { -fx-border-color: %s; -fx-border-width: 0.5 0 0 0; }
                    .jhostty-settings .text-field { -fx-background-color: %s; -fx-text-fill: %s; -fx-border-color: %s; -fx-border-radius: 4; -fx-background-radius: 4; -fx-prompt-text-fill: %s; }
                    .jhostty-settings .list-view { -fx-background-color: %s; -fx-border-color: %s; -fx-border-radius: 4; -fx-background-radius: 4; }
                    .jhostty-settings .list-view .list-cell { -fx-text-fill: %s; -fx-background-color: transparent; -fx-font-size: 11; }
                    .jhostty-settings .list-view .list-cell:filled:hover { -fx-background-color: %s; }
                    .jhostty-settings .list-view .list-cell:filled:selected { -fx-background-color: %s; -fx-text-fill: %s; }
                    .jhostty-settings .combo-box { -fx-background-color: %s; -fx-border-color: %s; -fx-border-radius: 4; -fx-background-radius: 4; }
                    .jhostty-settings .combo-box .list-cell { -fx-text-fill: %s; -fx-font-size: 11; }
                    .jhostty-settings .combo-box-popup .list-view { -fx-background-color: %s; -fx-border-color: %s; -fx-border-radius: 4; -fx-background-radius: 4; }
                    .jhostty-settings .combo-box-popup .list-cell { -fx-text-fill: %s; -fx-background-color: transparent; -fx-font-size: 11; }
                    .jhostty-settings .combo-box-popup .list-cell:filled:hover { -fx-background-color: %s; }
                    .jhostty-settings .combo-box-popup .list-cell:filled:selected { -fx-background-color: %s; -fx-text-fill: %s; }
                    .jhostty-settings .slider .track { -fx-background-color: %s; -fx-background-radius: 3; }
                    .jhostty-settings .slider .thumb { -fx-background-color: %s; }
                    .jhostty-palette-list { -fx-background-color: transparent; -fx-background: transparent; }
                    .jhostty-palette-list .list-cell { -fx-text-fill: %s; -fx-background-color: transparent; -fx-padding: 2 8; }
                    .jhostty-palette-list .list-cell:selected { -fx-background-color: %s; -fx-background-radius: 4; }
                    .jhostty-palette-list .list-cell:selected .label { -fx-text-fill: %s; }
                    """.formatted(
                        dividerCss, dividerCss,
                        tabBarBg, tabBarBg, tabSelectedBg, tabTextCss, tabSelectedTextCss,
                        tabCloseCss, tabCloseHoverCss,
                        dividerCss, menuBgCss, borderCss, fgCss, selCss, selText, sepCss,
                        dividerCss, borderCss, menuBgCss, fgCss, selCss, selText, fgCss,
                        fgCss, sepCss,
                        // scrollbar
                        dark ? "rgba(255,255,255,0.15)" : "rgba(0,0,0,0.15)",
                        dark ? "rgba(255,255,255,0.3)" : "rgba(0,0,0,0.3)",
                        // settings panel
                        menuBgCss, borderCss, fgCss, fgCss,
                        dark ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.06)", borderCss, fgCss, sepCss,
                        // text-field
                        dark ? "rgba(255,255,255,0.06)" : "rgba(0,0,0,0.04)", fgCss, borderCss,
                        dark ? "rgba(255,255,255,0.3)" : "rgba(0,0,0,0.3)",
                        // list-view
                        dark ? "rgba(255,255,255,0.04)" : "rgba(0,0,0,0.03)", borderCss, fgCss,
                        dark ? "rgba(255,255,255,0.1)" : "rgba(0,0,0,0.08)",
                        selCss, selText,
                        // combo-box
                        dark ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.06)", borderCss, fgCss,
                        menuBgCss, borderCss, fgCss,
                        dark ? "rgba(255,255,255,0.1)" : "rgba(0,0,0,0.08)",
                        selCss, selText,
                        dark ? "rgba(255,255,255,0.1)" : "rgba(0,0,0,0.08)",
                        dark ? "rgba(255,255,255,0.5)" : "rgba(0,0,0,0.4)",
                        // palette list
                        fgCss, selCss, selText
                        ));
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
        var btnLeft   = toolBtn("\u21E4", "Add Column Left",  () -> { var ws = activeWorkspace(); if (ws != null) ws.splitFocused(javafx.geometry.Side.LEFT); });
        var btnRight  = toolBtn("\u21E5", "Add Column Right", () -> { var ws = activeWorkspace(); if (ws != null) ws.splitFocused(javafx.geometry.Side.RIGHT); });
        var btnUp     = toolBtn("\u2912", "Add Row Above",    () -> { var ws = activeWorkspace(); if (ws != null) ws.splitFocused(javafx.geometry.Side.TOP); });
        var btnDown   = toolBtn("\u2913", "Add Row Below",  () -> { var ws = activeWorkspace(); if (ws != null) ws.splitFocused(javafx.geometry.Side.BOTTOM); });
        var btnClose  = toolBtn("\u2715", "Close Pane",  () -> { var ws = activeWorkspace(); if (ws != null) ws.closeFocused(); });
        var btnZoom   = toolBtn("\u2922", "Zoom Toggle", () -> { var ws = activeWorkspace(); if (ws != null) ws.toggleZoom(); });
        var btnNewWin = toolBtn("\u2398", "New Window",  () -> newWindow());

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var toolbar = new HBox(2,
            spacer, btnLeft, btnRight, btnUp, btnDown,
            toolSep(), btnClose, btnZoom,
            toolSep(), btnNewWin
        );
        toolbar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        toolbar.setPadding(new javafx.geometry.Insets(2, 8, 2, 8));
        toolbar.setStyle("-fx-background-color: transparent;");
        return toolbar;
    }

    static Button toolBtn(String glyph, String tooltip, Runnable action) {
        var btn = new Button(glyph);
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #999; -fx-font-size: 17; -fx-padding: 2 6; -fx-cursor: hand;");
        btn.setTooltip(new Tooltip(tooltip));
        btn.setFocusTraversable(false);
        btn.setOnAction(_ -> action.run());
        btn.setOnMouseEntered(_ -> btn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-background-radius: 4; -fx-text-fill: #ddd; -fx-font-size: 17; -fx-padding: 2 6; -fx-cursor: hand;"));
        btn.setOnMouseExited(_ -> btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #999; -fx-font-size: 17; -fx-padding: 2 6; -fx-cursor: hand;"));
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
        title.setStyle("-fx-font-size: 14; -fx-font-weight: bold;");

        var pastelLabel = new Label("Pastel Opacity");
        pastelLabel.setStyle("-fx-font-size: 11; -fx-opacity: 0.6;");
        var pastelSlider = safeSlider(0, 0.5, pastelOpacity(currentTheme));
        pastelSlider.setPrefWidth(180);
        var pastelValue = new Label(String.format("%.0f%%", pastelSlider.getValue() * 100));
        pastelValue.setStyle("-fx-font-size: 10; -fx-opacity: 0.5;");
        pastelSlider.valueProperty().addListener((_, _, v) -> {
            pastelValue.setText(String.format("%.0f%%", v.doubleValue() * 100));
            forEachWorkspace(tabs, ws -> ws.setPastelOpacity(v.doubleValue()));
        });
        var pastelRow = new HBox(8, pastelSlider, pastelValue);
        pastelRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var gutterLabel = new Label("Gutter Width");
        gutterLabel.setStyle("-fx-font-size: 11; -fx-opacity: 0.6;");
        var gutterSlider = safeSlider(0, 20, SplitWorkspace.GUTTER);
        gutterSlider.setPrefWidth(180);
        var gutterValue = new Label(String.format("%.0fpx", gutterSlider.getValue()));
        gutterValue.setStyle("-fx-font-size: 10; -fx-opacity: 0.5;");
        gutterSlider.valueProperty().addListener((_, _, v) -> {
            gutterValue.setText(String.format("%.0fpx", v.doubleValue()));
            forEachWorkspace(tabs, ws -> ws.setGutter(v.doubleValue()));
        });
        var gutterRow = new HBox(8, gutterSlider, gutterValue);
        gutterRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var radiusLabel = new Label("Corner Radius");
        radiusLabel.setStyle("-fx-font-size: 11; -fx-opacity: 0.6;");
        var radiusSlider = safeSlider(0, 20, SplitWorkspace.PANE_RADIUS);
        radiusSlider.setPrefWidth(180);
        var radiusValue = new Label(String.format("%.0fpx", radiusSlider.getValue()));
        radiusValue.setStyle("-fx-font-size: 10; -fx-opacity: 0.5;");
        radiusSlider.valueProperty().addListener((_, _, v) -> {
            radiusValue.setText(String.format("%.0fpx", v.doubleValue()));
            forEachWorkspace(tabs, ws -> ws.setPaneRadius(v.doubleValue()));
        });
        var radiusRow = new HBox(8, radiusSlider, radiusValue);
        radiusRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var headerLabel = new Label("Header Height");
        headerLabel.setStyle("-fx-font-size: 11; -fx-opacity: 0.6;");
        var headerSlider = safeSlider(0, 40, SplitWorkspace.HEADER_H);
        headerSlider.setPrefWidth(180);
        var headerValue = new Label(String.format("%.0fpx", headerSlider.getValue()));
        headerValue.setStyle("-fx-font-size: 10; -fx-opacity: 0.5;");
        headerSlider.valueProperty().addListener((_, _, v) -> {
            headerValue.setText(String.format("%.0fpx", v.doubleValue()));
            forEachWorkspace(tabs, ws -> ws.setHeaderHeight(v.doubleValue()));
        });
        var headerRow = new HBox(8, headerSlider, headerValue);
        headerRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var ringLabel = new Label("Focus Ring");
        ringLabel.setStyle("-fx-font-size: 11; -fx-opacity: 0.6;");
        var ringSlider = safeSlider(0, 5, SplitWorkspace.FOCUS_RING_WIDTH);
        ringSlider.setPrefWidth(180);
        var ringValue = new Label(String.format("%.1fpx", ringSlider.getValue()));
        ringValue.setStyle("-fx-font-size: 10; -fx-opacity: 0.5;");
        ringSlider.valueProperty().addListener((_, _, v) -> {
            ringValue.setText(String.format("%.1fpx", v.doubleValue()));
            forEachWorkspace(tabs, ws -> ws.setFocusRingWidth(v.doubleValue()));
        });
        var ringRow = new HBox(8, ringSlider, ringValue);
        ringRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var animLabel = new Label("Animation Speed");
        animLabel.setStyle("-fx-font-size: 11; -fx-opacity: 0.6;");
        var animSlider = safeSlider(50, 800, 300);
        animSlider.setPrefWidth(180);
        var animValue = new Label(String.format("%.0fms", animSlider.getValue()));
        animValue.setStyle("-fx-font-size: 10; -fx-opacity: 0.5;");
        animSlider.valueProperty().addListener((_, _, v) -> {
            animValue.setText(String.format("%.0fms", v.doubleValue()));
            forEachWorkspace(tabs, ws -> ws.setAnimationDuration(v.doubleValue()));
        });
        var animRow = new HBox(8, animSlider, animValue);
        animRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var pastelCheck = new CheckBox("Pastel Tinting");
        pastelCheck.setSelected(true);
        pastelCheck.setStyle("-fx-font-size: 11;");
        pastelCheck.selectedProperty().addListener((_, _, v) -> forEachWorkspace(tabs, ws -> ws.pastelTintingProperty().set(v)));

        var animCheck = new CheckBox("Animations");
        animCheck.setSelected(true);
        animCheck.setStyle("-fx-font-size: 11;");
        animCheck.selectedProperty().addListener((_, _, v) -> forEachWorkspace(tabs, ws -> ws.animationsEnabledProperty().set(v)));

        var focusFollowsCheck = new CheckBox("Focus Follows Mouse");
        focusFollowsCheck.setStyle("-fx-font-size: 11;");
        focusFollowsCheck.selectedProperty().bindBidirectional(focusFollowsMouse);

        var sep1 = new Separator();
        var sep2 = new Separator();

        var closeBtn = new Button("\u2715");
        closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #888; -fx-font-size: 12; -fx-padding: 0 4; -fx-cursor: hand;");
        closeBtn.setOnMouseEntered(_ -> closeBtn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-background-radius: 4; -fx-text-fill: #ccc; -fx-font-size: 12; -fx-padding: 0 4; -fx-cursor: hand;"));
        closeBtn.setOnMouseExited(_ -> closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #888; -fx-font-size: 12; -fx-padding: 0 4; -fx-cursor: hand;"));
        closeBtn.setFocusTraversable(false);

        var titleRow = new HBox(title, new Region() {{ HBox.setHgrow(this, Priority.ALWAYS); }}, closeBtn);
        titleRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // --- Theme selector (search + list with color preview) ---
        var themeLabel = new Label("Theme");
        themeLabel.setStyle("-fx-font-size: 11; -fx-opacity: 0.6;");
        var allThemes = Themes.all();
        var themeSearch = new TextField();
        themeSearch.setPromptText("Search " + allThemes.size() + " themes...");
        themeSearch.setStyle("-fx-font-size: 11;");
        themeSearch.setPrefWidth(230);
        var themeList = new ListView<ThemeOption>();
        themeList.getItems().addAll(allThemes);
        themeList.setPrefHeight(150);
        themeList.setStyle("-fx-font-size: 11;");

        // Color preview cell
        themeList.setCellFactory(_ -> new javafx.scene.control.ListCell<>() {
            private final HBox colorStrip = new HBox(1);
            { colorStrip.setMinWidth(48); colorStrip.setMaxWidth(48); colorStrip.setPrefHeight(12); colorStrip.setAlignment(javafx.geometry.Pos.CENTER); }
            @Override protected void updateItem(ThemeOption item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                setText(item.label());
                colorStrip.getChildren().clear();
                var t = item.theme();
                var p = t.palette();
                var colors = new Color[] { t.background(), t.foreground(),
                    p.size() > 1 ? p.get(1) : t.foreground(), p.size() > 2 ? p.get(2) : t.foreground(),
                    p.size() > 4 ? p.get(4) : t.foreground(), p.size() > 5 ? p.get(5) : t.foreground() };
                for (var c : colors) {
                    var swatch = new Region();
                    swatch.setMinSize(7, 12); swatch.setMaxSize(7, 12);
                    swatch.setStyle("-fx-background-color: " + colorToCss(c) + "; -fx-background-radius: 1;");
                    colorStrip.getChildren().add(swatch);
                }
                setGraphic(colorStrip);
                setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
                if (item.label().equals(currentThemeName)) setStyle("-fx-font-weight: bold;");
                else setStyle("");
            }
        });
        // Scroll to current theme
        allThemes.stream().filter(t -> t.label().equals(currentThemeName)).findFirst()
            .ifPresent(t -> { themeList.getSelectionModel().select(t); themeList.scrollTo(t); });
        // Forward arrow keys from search field to list
        themeSearch.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN) {
                var idx = themeList.getSelectionModel().getSelectedIndex();
                if (e.getCode() == KeyCode.DOWN) idx = Math.min(idx + 1, themeList.getItems().size() - 1);
                else idx = Math.max(idx - 1, 0);
                themeList.getSelectionModel().select(idx);
                themeList.scrollTo(idx);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                e.consume(); // prevent Enter from leaving the field
            }
        });
        // Filter as user types
        themeSearch.textProperty().addListener((_, _, text) -> {
            themeList.getItems().clear();
            if (text == null || text.isBlank()) {
                themeList.getItems().addAll(allThemes);
            } else {
                var lower = text.toLowerCase();
                allThemes.stream().filter(t -> t.label().toLowerCase().contains(lower))
                    .forEach(t -> themeList.getItems().add(t));
            }
            if (!themeList.getItems().isEmpty()) themeList.getSelectionModel().select(0);
        });
        // Apply on selection change (arrow keys or click)
        themeList.getSelectionModel().selectedItemProperty().addListener((_, _, selected) -> {
            if (selected != null && !selected.label().equals(currentThemeName)) {
                currentThemeName = selected.label();
                currentTheme = selected.theme();
                applyThemeToAll();
                saveState();
            }
        });
        var themeBox = new VBox(4, themeSearch, themeList);

        // --- Zoom slider ---
        var zoomLabel = new Label("Zoom");
        zoomLabel.setStyle("-fx-font-size: 11; -fx-opacity: 0.6;");
        var zoomSlider = safeSlider(8, 36, currentZoom);
        zoomSlider.setPrefWidth(180);
        var zoomValue = new Label(String.format("%.0fpt", zoomSlider.getValue()));
        zoomValue.setStyle("-fx-font-size: 10; -fx-opacity: 0.5;");
        zoomSlider.valueProperty().addListener((_, _, v) -> {
            var size = Math.round(v.doubleValue());
            zoomValue.setText(size + "pt");
            currentZoom = size;
            forEachTerminal(tv -> {
                tv.getProperties().put(ZOOM_KEY, (double) size);
                tv.setFont(FontManager.resolveFont(currentFontFamily, size));
            });
            updateTitle();
            saveState();
        });
        var zoomRow = new HBox(8, zoomSlider, zoomValue);
        zoomRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var panel = new VBox(10,
            titleRow, sep1,
            themeLabel, themeBox,
            zoomLabel, zoomRow,
            pastelLabel, pastelRow,
            gutterLabel, gutterRow,
            radiusLabel, radiusRow,
            headerLabel, headerRow,
            ringLabel, ringRow,
            animLabel, animRow,
            sep2, pastelCheck, animCheck, focusFollowsCheck
        );
        panel.setPadding(new javafx.geometry.Insets(12));
        panel.getStyleClass().add("jhostty-settings");
        panel.setPrefWidth(280);
        panel.setMinWidth(280);
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
            Themes.find(currentThemeName).ifPresent(t -> { currentThemeName = t.label(); currentTheme = t.theme(); });
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
            var root = ws.getRoot();
            if (root != null) captureSplitNode(sb, root);
            else sb.append("L[]");
        } else sb.append("1");
    }

    static void captureSplitNode(StringBuilder sb, SplitNode node) {
        if (node instanceof LeafPane leaf) {
            if (leaf.content() instanceof TerminalView v) {
                sb.append("T[").append(LayoutCodec.encodeCommand(getTerminalCommand(v))).append(']');
            } else sb.append("T[]");
        } else if (node instanceof Split split) {
            var orient = split.orientation() == Orientation.HORIZONTAL ? "C" : "R";
            sb.append(orient).append('{');
            for (int i = 0; i < split.children().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(String.format("%.3f:", split.weights().get(i)));
                captureSplitNode(sb, split.children().get(i));
            }
            sb.append('}');
        } else sb.append("L[]");
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
        // New format: L[cmd], H{0.500:L[cmd],0.500:L[cmd]}, V{...}
        // Legacy format: 1[cmd], H3[cmd|cmd|cmd]
        if (desc.startsWith("T[") || desc.startsWith("C{") || desc.startsWith("R{")) {
            var tree = parseSplitNode(desc, new int[]{0});
            if (tree == null) { newTab(tabPane); return; }
            var workspace = buildWorkspaceFromTree(tree);
            if (workspace == null) { newTab(tabPane); return; }
            configureWorkspace(workspace, tabPane);
            var tab = new Tab();
            tab.setText("jhostty");
            tab.setContent(workspace);
            setupTabGraphic(tab, tabPane);
            tabPane.getTabs().add(tab);
            tabPane.getSelectionModel().select(tab);
            workspace.focusedPaneProperty().addListener((_, _, pane) -> {
                if (pane != null && pane.content() instanceof TerminalView tv) {
                    tab.textProperty().unbind();
                    tab.textProperty().bind(tv.titleProperty());
                }
            });
        } else {
            // Legacy format or single terminal
            newTab(tabPane);
        }
    }

    /** Parse a split node from the serialized format. */
    static SplitNode parseSplitNode(String s, int[] pos) {
        if (pos[0] >= s.length()) return null;
        char c = s.charAt(pos[0]);
        if (c == 'T') {
            // Terminal: T[command]
            pos[0] += 2; // skip "T["
            int end = s.indexOf(']', pos[0]);
            if (end < 0) return null;
            var cmdStr = s.substring(pos[0], end);
            pos[0] = end + 1;
            var cmd = cmdStr.isEmpty() ? shellCommand : LayoutCodec.parseCommand(cmdStr, shellCommand);
            var view = createTerminalClean(cmd);
            if (view == null) return null;
            return new LeafPane(PaneId.next(), view, null);
        } else if (c == 'C' || c == 'R') {
            // Column: C{...}, Row: R{...}
            var orient = c == 'C' ? Orientation.HORIZONTAL : Orientation.VERTICAL;
            pos[0] += 2; // skip "C{" or "R{"
            var children = new ArrayList<SplitNode>();
            var weights = new ArrayList<Double>();
            while (pos[0] < s.length() && s.charAt(pos[0]) != '}') {
                if (s.charAt(pos[0]) == ',') pos[0]++;
                int colonIdx = s.indexOf(':', pos[0]);
                if (colonIdx < 0) break;
                var weight = Double.parseDouble(s.substring(pos[0], colonIdx));
                pos[0] = colonIdx + 1;
                var child = parseSplitNode(s, pos);
                if (child != null) { children.add(child); weights.add(weight); }
            }
            if (pos[0] < s.length() && s.charAt(pos[0]) == '}') pos[0]++;
            if (children.size() < 2) return children.isEmpty() ? null : children.getFirst();
            return new Split(orient, children, weights);
        }
        return null;
    }

    /** Build a SplitWorkspace from a parsed tree. */
    static SplitWorkspace buildWorkspaceFromTree(SplitNode tree) {
        var ws = new SplitWorkspace();
        ws.setContentFactory(() -> createTerminal());
        ws.setRoot(tree);
        return ws;
    }

    /** Configure a workspace with theme, callbacks, etc. */
    static void configureWorkspace(SplitWorkspace workspace, TabPane tabPane) {
        workspace.setPaneBackground(currentTheme.background());
        workspace.setFocusRingColor(focusRingColor(currentTheme));
        workspace.setPastelOpacity(pastelOpacity(currentTheme));
        workspace.focusFollowsMouseProperty().set(focusFollowsMouse.get());
        workspace.setStyle("-fx-background-color: " + colorToCss(dividerColor(currentTheme.background())) + ";");
        workspace.setOnEmpty(() -> Platform.runLater(() -> {
            var tp = findTabPane(workspace);
            var stg = tp != null ? findStage(tp) : null;
            var t = findTab(workspace);
            if (t != null && tp != null) tp.getTabs().remove(t);
            if (tp != null && tp.getTabs().isEmpty() && stg != null) stg.close();
        }));
        workspace.setOnPaneDraggedOut((leaf, screenX, screenY, paneW, paneH) -> Platform.runLater(() -> {
            // Create a new window at the drop position, sized to the pane
            var newStage = newWindowEmpty();
            if (newStage == null) return;
            newStage.setWidth(Math.max(400, paneW + 40));
            newStage.setHeight(Math.max(300, paneH + 80));
            newStage.setX(screenX - newStage.getWidth() / 2);
            newStage.setY(screenY - 30);
            var newTabs = getTabPane(newStage);
            if (newTabs == null) return;
            var newWs = new SplitWorkspace();
            newWs.setContentFactory(() -> createTerminal());
            newWs.setRoot(leaf);
            configureWorkspace(newWs, newTabs);
            var tab = new Tab();
            tab.setText("jhostty");
            tab.setContent(newWs);
            setupTabGraphic(tab, newTabs);
            newTabs.getTabs().add(tab);
            newTabs.getSelectionModel().select(tab);
            newWs.focusedPaneProperty().addListener((_, _, pane) -> {
                if (pane != null && pane.content() instanceof TerminalView tv) {
                    activeTerminal = tv;
                    tab.textProperty().unbind();
                    tab.textProperty().bind(tv.titleProperty());
                    var stg = findStageFor(tv);
                    if (stg != null) { stg.setTitle(tv.getTitle() != null ? tv.getTitle() : "jhostty"); rebuildWindowMenus(); }
                    rebuildAllSidebars();
                }
            });
            if (leaf.content() instanceof TerminalView tv) {
                activeTerminal = tv;
                tv.requestFocus();
            }
        }));
    }

    // --- Window Factory ---

    static Stage newWindowEmpty() {
        var tabs = new TabPane();
        tabs.getStyleClass().add("jhostty-tabs");
        tabs.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        // Equal-width tabs (exclude the "+" button from sizing)
        Runnable updateTabWidths = () -> {
            int count = tabs.getTabs().size();
            if (count <= 0) return;
            double tabWidth = Math.max(60, tabs.getWidth() / count);
            for (var t : tabs.getTabs()) {
                t.setStyle("-fx-pref-width: " + tabWidth + "; -fx-min-width: 60; -fx-max-width: " + tabWidth + ";");
            }
        };
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) _ -> Platform.runLater(updateTabWidths));
        tabs.widthProperty().addListener((_, _, _) -> updateTabWidths.run());

        tabs.getSelectionModel().selectedItemProperty().addListener((_, _, selected) -> {
            if (selected != null && selected.getContent() instanceof SplitWorkspace ws) {
                // Update activeTerminal when switching tabs
                var focused = ws.focusedPaneProperty().get();
                if (focused != null && focused.content() instanceof TerminalView tv) {
                    activeTerminal = tv;
                    Platform.runLater(tv::requestFocus);
                }
            }
        });

        var menuBar = createMenuBar(tabs);
        if (IS_MAC) { menuBar.setUseSystemMenuBar(true); menuBar.setMaxHeight(0); menuBar.setPrefHeight(0); menuBar.setMinHeight(0); }

        var sidebarTree = new TreeView<SidebarItem>();
        sidebarTree.setShowRoot(false);
        sidebarTree.getStyleClass().add("jhostty-sidebar");

        var sidebarTitle = new Label("Terminals");
        sidebarTitle.setStyle("-fx-font-size: 14; -fx-font-weight: bold;");
        var sidebarCloseBtn = new Button("\u2715");
        sidebarCloseBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #888; -fx-font-size: 12; -fx-padding: 0 4; -fx-cursor: hand;");
        sidebarCloseBtn.setOnMouseEntered(_ -> sidebarCloseBtn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-background-radius: 4; -fx-text-fill: #ccc; -fx-font-size: 12; -fx-padding: 0 4; -fx-cursor: hand;"));
        sidebarCloseBtn.setOnMouseExited(_ -> sidebarCloseBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #888; -fx-font-size: 12; -fx-padding: 0 4; -fx-cursor: hand;"));
        sidebarCloseBtn.setFocusTraversable(false);
        sidebarCloseBtn.setOnAction(_ -> toggleSidebar());
        var sidebarHeader = new HBox(sidebarTitle, new Region() {{ HBox.setHgrow(this, Priority.ALWAYS); }}, sidebarCloseBtn);
        sidebarHeader.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        var sidebarSep = new Separator();
        VBox.setVgrow(sidebarTree, Priority.ALWAYS);
        var sidebar = new VBox(sidebarHeader, sidebarSep, sidebarTree);
        sidebar.setPadding(new javafx.geometry.Insets(12, 12, 0, 12));
        sidebar.getStyleClass().add("jhostty-sidebar");
        sidebar.setMinWidth(100);
        sidebarTree.setCellFactory(_ -> new TreeCell<>() {
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
        sidebarTree.setOnMouseClicked(e -> {
            if (e.getClickCount() < 2) return;
            var sel = sidebarTree.getSelectionModel().getSelectedItem();
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
        var windowBg = currentTheme.background();

        var contentSplit = new SplitPane(tabs);
        contentSplit.setOrientation(Orientation.HORIZONTAL);
        contentSplit.getStyleClass().add("jhostty-content-split");

        var root = new BorderPane();
        root.setStyle("-fx-background-color: " + colorToCss(windowBg) + ";");

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

        var titleBar = new HBox(4, trafficSpacer, leftSpacer, titleLabel, rightSpacer, toolbar);
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

        var rootStack = new javafx.scene.layout.StackPane(root);
        var scene = new Scene(rootStack, 1000, 700);
        scene.setFill(windowBg);
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
        stage.setOnCloseRequest(_ -> {
            savedWindowX = stage.getX(); savedWindowY = stage.getY();
            if (!shuttingDown) saveState();
        });
        stage.setOnHidden(_ -> {
            windows.remove(stage); windowMenus.removeIf(m -> m.getParentMenu() == null && m.getParentPopup() == null);
            sidebarsByWindow.remove(stage); sidebarPanelsByWindow.remove(stage); rebuildWindowMenus(); rebuildAllSidebars();
            if (windows.isEmpty()) Platform.exit();
        });

        // Show pane numbers when Cmd/Ctrl is held (only if multiple panes)
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && !e.isShiftDown() && !e.isAltDown()) {
                var ws = activeWorkspace();
                if (ws != null && ws.allLeaves().size() > 1 && !ws.isDragging()) ws.showPaneNumbers();
            }
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (!e.isShortcutDown()) {
                var ws = activeWorkspace();
                if (ws != null) ws.hidePaneNumbers();
            }
        });
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (debug && e.isShortcutDown()) debug("KeyEvent: code=" + e.getCode() + " meta=" + e.isMetaDown() + " ctrl=" + e.isControlDown() + " shift=" + e.isShiftDown());
            if (!e.isShortcutDown()) return;
            switch (e.getCode()) {
                case P -> { showCommandPalette(tabs); e.consume(); }
                case Q -> { quit(); e.consume(); }
                case N -> { newWindow(); e.consume(); }
                case T -> { if (e.isShiftDown()) toggleTabOverview(tabs); else newTabNext(tabs); e.consume(); }
                case D -> { if (e.isShiftDown()) splitActive(Orientation.HORIZONTAL); else splitActive(Orientation.VERTICAL); e.consume(); }
                case W -> { closeActive(tabs, stage); e.consume(); }
                case SLASH -> { toggleSidebar(); e.consume(); }
                case EQUALS, PLUS, ADD -> { if (activeTerminal != null) zoomTerminal(activeTerminal, 1); e.consume(); }
                case MINUS, SUBTRACT -> { if (activeTerminal != null) zoomTerminal(activeTerminal, -1); e.consume(); }
                case DIGIT0, NUMPAD0 -> { if (activeTerminal != null) setTerminalZoom(activeTerminal, baseFontSize); e.consume(); }
                case DIGIT1, DIGIT2, DIGIT3, DIGIT4, DIGIT5, DIGIT6, DIGIT7, DIGIT8, DIGIT9 -> {
                    if (!e.isShiftDown()) {
                        var ws = activeWorkspace();
                        if (ws != null) {
                            int idx = e.getCode().ordinal() - KeyCode.DIGIT1.ordinal();
                            ws.hidePaneNumbers();
                            ws.focusPaneByIndex(idx);
                            e.consume();
                        }
                    }
                }
                case ENTER -> { if (e.isShiftDown()) { var ws = activeWorkspace(); if (ws != null) ws.toggleZoom(); e.consume(); } }
                case COMMA -> {
                    var bpC = getRootPane(findStage(tabs));
                    if (bpC != null) { var spC = (Node) bpC.getRight(); if (spC != null) { var vis = !spC.isVisible(); spC.setVisible(vis); spC.setManaged(vis); } }
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
            if (e.isShortcutDown() && e.getDeltaY() != 0 && activeTerminal != null) {
                zoomTerminal(activeTerminal, e.getDeltaY() > 0 ? 1 : -1);
                e.consume();
            }
        });
        scene.addEventFilter(ZoomEvent.ZOOM, e -> {
            if (activeTerminal != null) {
                zoomTerminal(activeTerminal, e.getZoomFactor() > 1 ? 1 : -1);
                e.consume();
            }
        });

        if (!Double.isNaN(savedWindowX)) stage.setX(savedWindowX);
        if (!Double.isNaN(savedWindowY)) stage.setY(savedWindowY);
        windows.add(stage);
        sidebarsByWindow.put(stage, sidebarTree);
        sidebarPanelsByWindow.put(stage, sidebar);
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
        workspace.focusFollowsMouseProperty().set(focusFollowsMouse.get());
        workspace.setStyle("-fx-background-color: " + colorToCss(dividerColor(currentTheme.background())) + ";");
        workspace.setOnEmpty(() -> Platform.runLater(() -> {
            var tp = findTabPane(workspace);
            var stg = tp != null ? findStage(tp) : null;
            var t = findTab(workspace);
            if (t != null && tp != null) tabPane.getTabs().remove(t);
            if (tp != null && tp.getTabs().isEmpty() && stg != null) stg.close();
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
        setupTabGraphic(tab, tabPane);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        Platform.runLater(JHostty::saveState);
    }

    // --- Tab Overview ---

    static javafx.scene.layout.StackPane tabOverlayPane;
    static boolean tabOverviewAnimating = false;

    static void toggleTabOverview(TabPane tabs) {
        var stage = findStage(tabs);
        if (stage == null || tabOverviewAnimating) return;
        var root = (javafx.scene.layout.StackPane) stage.getScene().getRoot();

        // If already showing, animate out and dismiss
        if (tabOverlayPane != null && tabOverlayPane.getParent() == root) {
            animateOverviewOut(root);
            return;
        }

        // Build grid of tab snapshots
        var grid = new javafx.scene.layout.FlowPane(16, 16);
        grid.setAlignment(javafx.geometry.Pos.CENTER);
        grid.setPadding(new javafx.geometry.Insets(40));

        var selectedTab = tabs.getSelectionModel().getSelectedItem();

        for (var tab : tabs.getTabs()) {
            var content = tab.getContent();
            if (content == null) continue;

            // Snapshot the tab content
            var snapshot = content.snapshot(null, null);
            var imgView = new javafx.scene.image.ImageView(snapshot);
            imgView.setFitWidth(220);
            imgView.setPreserveRatio(true);
            imgView.setSmooth(true);

            var title = tab.getText() != null && !tab.getText().isBlank() ? tab.getText() : "Terminal";
            var titleLbl = new Label(title);
            titleLbl.setStyle("-fx-text-fill: rgba(255,255,255,0.8); -fx-font-size: 11;");
            titleLbl.setMaxWidth(220);
            titleLbl.setAlignment(javafx.geometry.Pos.CENTER);

            var card = new VBox(4, titleLbl, imgView);
            card.setAlignment(javafx.geometry.Pos.CENTER);
            card.setPadding(new javafx.geometry.Insets(8));
            card.setStyle("-fx-background-color: rgba(40,40,40,0.85); -fx-background-radius: 10; -fx-cursor: hand;");

            if (tab == selectedTab) {
                card.setStyle("-fx-background-color: rgba(40,40,40,0.85); -fx-background-radius: 10; -fx-cursor: hand; -fx-border-color: #3B82F6; -fx-border-width: 2; -fx-border-radius: 10;");
            }

            card.setOnMouseEntered(_ -> {
                if (tab != selectedTab) card.setStyle("-fx-background-color: rgba(60,60,60,0.9); -fx-background-radius: 10; -fx-cursor: hand;");
            });
            card.setOnMouseExited(_ -> {
                if (tab == selectedTab)
                    card.setStyle("-fx-background-color: rgba(40,40,40,0.85); -fx-background-radius: 10; -fx-cursor: hand; -fx-border-color: #3B82F6; -fx-border-width: 2; -fx-border-radius: 10;");
                else
                    card.setStyle("-fx-background-color: rgba(40,40,40,0.85); -fx-background-radius: 10; -fx-cursor: hand;");
            });

            final var cardRef = card;
            card.setOnMouseClicked(_ -> {
                tabs.getSelectionModel().select(tab);
                animateOverviewSelect(root, cardRef, grid);
            });

            grid.getChildren().add(card);
        }

        // "+" card
        var plusLbl = new Label("+");
        plusLbl.setStyle("-fx-text-fill: rgba(255,255,255,0.4); -fx-font-size: 36;");
        var plusCard = new VBox(plusLbl);
        plusCard.setAlignment(javafx.geometry.Pos.CENTER);
        plusCard.setPrefSize(220, 140);
        plusCard.setStyle("-fx-background-color: rgba(80,80,80,0.5); -fx-background-radius: 10; -fx-cursor: hand;");
        plusCard.setOnMouseEntered(_ -> plusCard.setStyle("-fx-background-color: rgba(100,100,100,0.6); -fx-background-radius: 10; -fx-cursor: hand;"));
        plusCard.setOnMouseExited(_ -> plusCard.setStyle("-fx-background-color: rgba(80,80,80,0.5); -fx-background-radius: 10; -fx-cursor: hand;"));
        plusCard.setOnMouseClicked(_ -> {
            animateOverviewOut(root);
            Platform.runLater(() -> newTabNext(tabs));
        });
        grid.getChildren().add(plusCard);

        // Wrap in scrollable overlay
        var scroll = new javafx.scene.control.ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        tabOverlayPane = new javafx.scene.layout.StackPane(scroll);
        tabOverlayPane.setStyle("-fx-background-color: rgba(0,0,0,0.75);");

        // Click background to dismiss
        tabOverlayPane.setOnMouseClicked(e -> {
            if (e.getTarget() == tabOverlayPane || e.getTarget() == scroll) animateOverviewOut(root);
        });

        // Escape to dismiss
        tabOverlayPane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) { animateOverviewOut(root); e.consume(); }
        });

        // Animate in: start zoomed up, shrink to grid
        root.getChildren().add(tabOverlayPane);
        tabOverlayPane.requestFocus();
        tabOverlayPane.setOpacity(0);
        // Start cards invisible, scale up from center
        for (var node : grid.getChildren()) { node.setOpacity(0); node.setScaleX(2.5); node.setScaleY(2.5); }
        tabOverviewAnimating = true;

        var bgFade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(250), tabOverlayPane);
        bgFade.setToValue(1.0);

        // Stagger cards appearing
        var cardAnims = new javafx.animation.ParallelTransition();
        int ci = 0;
        for (var node : grid.getChildren()) {
            var delay = javafx.util.Duration.millis(ci * 30);
            var fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(200), node);
            fade.setDelay(delay);
            fade.setToValue(1.0);
            var scale = new javafx.animation.ScaleTransition(javafx.util.Duration.millis(300), node);
            scale.setDelay(delay);
            scale.setToX(1.0); scale.setToY(1.0);
            scale.setInterpolator(javafx.animation.Interpolator.EASE_OUT);
            cardAnims.getChildren().addAll(fade, scale);
            ci++;
        }
        var par = new javafx.animation.ParallelTransition(bgFade, cardAnims);
        par.setOnFinished(_ -> tabOverviewAnimating = false);
        par.play();
    }

    static void animateOverviewOut(javafx.scene.layout.Pane root) {
        if (tabOverlayPane == null || tabOverviewAnimating) return;
        tabOverviewAnimating = true;
        var content = tabOverlayPane.getChildren().isEmpty() ? tabOverlayPane : tabOverlayPane.getChildren().getFirst();
        var gridNode = (content instanceof javafx.scene.control.ScrollPane sp) ? sp.getContent() : content;
        var cards = (gridNode instanceof javafx.scene.layout.Pane p) ? p.getChildren() : javafx.collections.FXCollections.<Node>observableArrayList();

        // Animate cards zooming out + fading
        var cardAnims = new javafx.animation.ParallelTransition();
        int ci = 0;
        for (var node : cards) {
            var delay = javafx.util.Duration.millis(ci * 20);
            var fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(150), node);
            fade.setDelay(delay);
            fade.setToValue(0);
            var scale = new javafx.animation.ScaleTransition(javafx.util.Duration.millis(200), node);
            scale.setDelay(delay);
            scale.setToX(2.5); scale.setToY(2.5);
            scale.setInterpolator(javafx.animation.Interpolator.EASE_IN);
            cardAnims.getChildren().addAll(fade, scale);
            ci++;
        }
        var bgFade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(200), tabOverlayPane);
        bgFade.setDelay(javafx.util.Duration.millis(100));
        bgFade.setToValue(0);
        var par = new javafx.animation.ParallelTransition(cardAnims, bgFade);
        par.setOnFinished(_ -> {
            root.getChildren().remove(tabOverlayPane);
            tabOverlayPane = null;
            tabOverviewAnimating = false;
        });
        par.play();
    }

    /** Animate zoom into the selected card while fading out others. */
    static void animateOverviewSelect(javafx.scene.layout.Pane root, Node selectedCard, javafx.scene.layout.Pane grid) {
        if (tabOverlayPane == null || tabOverviewAnimating) return;
        tabOverviewAnimating = true;

        var anims = new javafx.animation.ParallelTransition();

        // Selected card: zoom to fill screen
        var selScale = new javafx.animation.ScaleTransition(javafx.util.Duration.millis(300), selectedCard);
        selScale.setToX(4.0); selScale.setToY(4.0);
        selScale.setInterpolator(javafx.animation.Interpolator.EASE_IN);
        var selFade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(250), selectedCard);
        selFade.setDelay(javafx.util.Duration.millis(100));
        selFade.setToValue(0);
        anims.getChildren().addAll(selScale, selFade);

        // Other cards: fade out and shrink
        for (var node : grid.getChildren()) {
            if (node == selectedCard) continue;
            var fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(150), node);
            fade.setToValue(0);
            var scale = new javafx.animation.ScaleTransition(javafx.util.Duration.millis(200), node);
            scale.setToX(0.8); scale.setToY(0.8);
            scale.setInterpolator(javafx.animation.Interpolator.EASE_IN);
            anims.getChildren().addAll(fade, scale);
        }

        // Background fade
        var bgFade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(200), tabOverlayPane);
        bgFade.setDelay(javafx.util.Duration.millis(150));
        bgFade.setToValue(0);
        anims.getChildren().add(bgFade);

        anims.setOnFinished(_ -> {
            root.getChildren().remove(tabOverlayPane);
            tabOverlayPane = null;
            tabOverviewAnimating = false;
        });
        anims.play();
    }

    // --- Command Palette ---

    record PaletteItem(String label, String shortcut, String category, Runnable action) {
        @Override public String toString() { return label; }
    }

    static javafx.scene.layout.StackPane paletteOverlay;

    static void showCommandPalette(TabPane tabs) {
        var stage = findStage(tabs);
        if (stage == null) return;
        var rootStack = (javafx.scene.layout.StackPane) stage.getScene().getRoot();

        // Dismiss if already showing
        if (paletteOverlay != null && paletteOverlay.getParent() == rootStack) {
            rootStack.getChildren().remove(paletteOverlay);
            paletteOverlay = null;
            return;
        }

        var sc = IS_MAC ? "\u2318" : "Ctrl+";
        var sh = IS_MAC ? "\u21E7" : "Shift+";

        // Build command list
        var commands = new ArrayList<PaletteItem>();
        commands.add(new PaletteItem("New Tab", sc + "T", "Shell", () -> newTabNext(tabs)));
        commands.add(new PaletteItem("New Window", sc + "N", "Shell", () -> newWindow()));
        commands.add(new PaletteItem("Close Pane/Tab", sc + "W", "Shell", () -> closeActive(tabs, stage)));
        commands.add(new PaletteItem("Add Column", sc + "D", "Shell", () -> splitActive(Orientation.VERTICAL)));
        commands.add(new PaletteItem("Add Row", sc + sh + "D", "Shell", () -> splitActive(Orientation.HORIZONTAL)));
        commands.add(new PaletteItem("Show All Tabs", sc + sh + "T", "Shell", () -> toggleTabOverview(tabs)));
        commands.add(new PaletteItem("Quit", sc + "Q", "Shell", () -> quit()));
        commands.add(new PaletteItem("Toggle Sidebar", sc + "/", "View", () -> toggleSidebar()));
        commands.add(new PaletteItem("Toggle Settings", sc + ",", "View", () -> {
            var bp = getRootPane(stage); if (bp != null) { var sp = (Node) bp.getRight(); if (sp != null) { var vis = !sp.isVisible(); sp.setVisible(vis); sp.setManaged(vis); } }
        }));
        commands.add(new PaletteItem("Zoom In", sc + "+", "View", () -> { if (activeTerminal != null) zoomTerminal(activeTerminal, 1); }));
        commands.add(new PaletteItem("Zoom Out", sc + "-", "View", () -> { if (activeTerminal != null) zoomTerminal(activeTerminal, -1); }));
        commands.add(new PaletteItem("Reset Zoom", sc + "0", "View", () -> { if (activeTerminal != null) setTerminalZoom(activeTerminal, baseFontSize); }));
        commands.add(new PaletteItem("Zoom/Unzoom Pane", sc + sh + "Enter", "Pane", () -> { var ws = activeWorkspace(); if (ws != null) ws.toggleZoom(); }));
        commands.add(new PaletteItem("Focus Next Pane", sc + "Tab", "Pane", () -> { var ws = activeWorkspace(); if (ws != null) ws.focusNext(); }));
        commands.add(new PaletteItem("Help", sc + sh + "/", "Help", () -> showHelp()));
        commands.add(new PaletteItem("Reload Config", "", "View", () -> { loadConfig(); applyThemeToAll(); }));

        // Add tabs as items
        for (int i = 0; i < tabs.getTabs().size(); i++) {
            var tab = tabs.getTabs().get(i);
            var title = tab.getText() != null && !tab.getText().isBlank() ? tab.getText() : "Terminal";
            final int idx = i;
            commands.add(new PaletteItem("Switch to: " + title, i < 9 ? sc + (i + 1) : "", "Tabs", () -> tabs.getSelectionModel().select(idx)));
        }

        // Add themes (first 50 matching)
        for (var t : Themes.all()) {
            final var theme = t;
            commands.add(new PaletteItem("Theme: " + t.label(), "", "Themes", () -> {
                currentThemeName = theme.label(); currentTheme = theme.theme(); applyThemeToAll(); saveState();
            }));
        }

        var allItems = List.copyOf(commands);

        var searchField = new TextField();
        searchField.setPromptText("Type a command, tab name, or theme...");
        searchField.setStyle("-fx-font-size: 15; -fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-border-color: rgba(255,255,255,0.15); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 12;");

        var resultList = new ListView<PaletteItem>();
        resultList.getItems().addAll(allItems);
        resultList.setPrefHeight(350);
        resultList.getStyleClass().add("jhostty-palette-list");
        resultList.setFixedCellSize(28);
        resultList.setCellFactory(_ -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(PaletteItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                var lbl = new Label(item.label());
                lbl.setStyle("-fx-font-size: 14;");
                var cat = new Label(item.category());
                cat.setStyle("-fx-opacity: 0.35; -fx-font-size: 11;");
                var spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                var sc2 = new Label(item.shortcut());
                sc2.setStyle("-fx-opacity: 0.45; -fx-font-size: 12;");
                var row = new HBox(8, lbl, cat, spacer, sc2);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });

        // Filter
        searchField.textProperty().addListener((_, _, text) -> {
            resultList.getItems().clear();
            var lower = text == null ? "" : text.toLowerCase();
            allItems.stream()
                .filter(item -> lower.isEmpty() || item.label().toLowerCase().contains(lower)
                    || item.category().toLowerCase().contains(lower))
                .forEach(item -> resultList.getItems().add(item));
            if (!resultList.getItems().isEmpty()) resultList.getSelectionModel().select(0);
        });

        // Arrow keys navigate, Enter selects
        // Execute selected item helper
        final Runnable[] execRef = new Runnable[1];
        execRef[0] = () -> {
            var sel = resultList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                var r = (javafx.scene.layout.StackPane) stage.getScene().getRoot();
                r.getChildren().remove(paletteOverlay);
                paletteOverlay = null;
                Platform.runLater(sel.action());
            }
        };

        searchField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DOWN) {
                var idx = resultList.getSelectionModel().getSelectedIndex();
                resultList.getSelectionModel().select(Math.min(idx + 1, resultList.getItems().size() - 1));
                resultList.scrollTo(resultList.getSelectionModel().getSelectedIndex());
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                var idx = resultList.getSelectionModel().getSelectedIndex();
                resultList.getSelectionModel().select(Math.max(idx - 1, 0));
                resultList.scrollTo(resultList.getSelectionModel().getSelectedIndex());
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                execRef[0].run(); e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                rootStack.getChildren().remove(paletteOverlay); paletteOverlay = null; e.consume();
            }
        });

        // Click to select
        resultList.setOnMouseClicked(_ -> execRef[0].run());

        var paletteBox = new VBox(8, searchField, resultList);
        paletteBox.setStyle("-fx-background-color: rgba(30,30,30,0.95); -fx-background-radius: 10; -fx-border-color: rgba(255,255,255,0.12); -fx-border-radius: 10; -fx-padding: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 20, 0, 0, 8);");
        paletteBox.setMaxWidth(550);
        paletteBox.setMaxHeight(500);

        // Arrow/Enter/Escape on the list itself
        resultList.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) { execRef[0].run(); e.consume(); }
            else if (e.getCode() == KeyCode.ESCAPE) { rootStack.getChildren().remove(paletteOverlay); paletteOverlay = null; e.consume(); }
            // Typing goes to search field
            else if (e.getCode().isLetterKey() || e.getCode().isDigitKey() || e.getCode() == KeyCode.BACK_SPACE) {
                searchField.requestFocus();
                if (e.getCode() == KeyCode.BACK_SPACE) searchField.deletePreviousChar();
                else searchField.appendText(e.getText());
                e.consume();
            }
        });

        paletteOverlay = new javafx.scene.layout.StackPane(paletteBox);
        paletteOverlay.setStyle("-fx-background-color: transparent;");
        paletteOverlay.setPickOnBounds(false);
        javafx.scene.layout.StackPane.setAlignment(paletteBox, javafx.geometry.Pos.TOP_CENTER);
        javafx.scene.layout.StackPane.setMargin(paletteBox, new javafx.geometry.Insets(60, 0, 0, 0));

        rootStack.getChildren().add(paletteOverlay);
        searchField.requestFocus();
        if (!resultList.getItems().isEmpty()) resultList.getSelectionModel().select(0);
    }

    // --- Help ---

    static void showHelp() {
        var sc = IS_MAC ? "\u2318" : "Ctrl+";
        var sh = IS_MAC ? "\u21E7" : "Shift+";
        var mod = IS_MAC ? "\u2318" : "Ctrl";

        // ANSI escape codes
        var B = "\033[1m";      // bold
        var D = "\033[2m";      // dim
        var U = "\033[4m";      // underline
        var R = "\033[0m";      // reset
        var C = "\033[36m";     // cyan
        var Y = "\033[33m";     // yellow
        var G = "\033[32m";     // green
        var M = "\033[35m";     // magenta

        var help = B + "\u2B1A jhostty" + R + "  \u2014 A modern terminal emulator\n" +
            D + "Built with JavaFX and Ghostty" + R + "\n\n" +
            B + U + "Keyboard Shortcuts" + R + "\n\n" +
            C + "  " + sc + "T" + R + "          New tab (after current)\n" +
            C + "  " + sc + "N" + R + "          New window\n" +
            C + "  " + sc + "D" + R + "          Add column (split right)\n" +
            C + "  " + sc + sh + "D" + R + "         Add row (split down)\n" +
            C + "  " + sc + "W" + R + "          Close pane / tab\n" +
            C + "  " + sc + "Q" + R + "          Quit\n\n" +
            C + "  " + sc + "+" + R + " / " + C + sc + "-" + R + "    Zoom in / out\n" +
            C + "  " + sc + "0" + R + "          Reset zoom\n" +
            C + "  Pinch" + R + "        Trackpad zoom\n\n" +
            C + "  " + sc + "1" + R + "\u2013" + C + sc + "9" + R + "      Focus pane by number\n" +
            C + "  " + sc + "Tab" + R + "        Focus next pane\n" +
            C + "  " + sc + sh + "Enter" + R + "    Zoom / unzoom pane\n" +
            C + "  " + sc + sh + "\u2190\u2191\u2192\u2193" + R + "       Resize focused pane\n\n" +
            C + "  " + sc + "/" + R + "          Toggle sidebar\n" +
            C + "  " + sc + "," + R + "          Toggle settings panel\n" +
            C + "  " + sc + sh + "/" + R + "         This help\n\n" +
            B + U + "Split Panes" + R + "\n\n" +
            Y + "  \u2022" + R + " Hold " + C + mod + R + " to see pane numbers, press digit to jump\n" +
            Y + "  \u2022" + R + " Drag pane header to reorder or swap panes\n" +
            Y + "  \u2022" + R + " Drag pane outside window to create new window\n" +
            Y + "  \u2022" + R + " Double-click pane header to zoom/unzoom\n" +
            Y + "  \u2022" + R + " Drag dividers to resize; corners for both axes\n\n" +
            B + U + "Tabs" + R + "\n\n" +
            G + "  \u2022" + R + " Click " + B + "+" + R + " on a tab to add new tab next to it\n" +
            G + "  \u2022" + R + " Click " + B + "\u2715" + R + " to close a tab\n" +
            G + "  \u2022" + R + " Drag tabs to reorder\n\n" +
            B + U + "Features" + R + "\n\n" +
            M + "  \u2022" + R + " Theme support (View \u2192 Theme or Settings panel)\n" +
            M + "  \u2022" + R + " Font selection (View \u2192 Font)\n" +
            M + "  \u2022" + R + " Focus follows mouse\n" +
            M + "  \u2022" + R + " Pastel pane tinting for visual distinction\n" +
            M + "  \u2022" + R + " zmx session integration\n" +
            M + "  \u2022" + R + " Layout save/restore across restarts\n" +
            M + "  \u2022" + R + " Drag & drop files onto terminal\n\n" +
            B + U + "Configuration" + R + "\n\n" +
            D + "  User config:" + R + "   ~/.config/jhostty/jhostty.properties\n" +
            D + "  Auto-saved:" + R + "    ~/.config/jhostty/jhostty-state.properties\n" +
            D + "  Keys:" + R + "          theme, font, font-size, shell\n";

        try {
            var tmpFile = Files.createTempFile("jhostty-help", ".txt");
            tmpFile.toFile().deleteOnExit();
            Files.writeString(tmpFile, help);
            var tabPane = findActiveTabPane();
            if (tabPane == null) return;
            var cmd = List.of("sh", "-c",
                "clear && cat '" + tmpFile.toAbsolutePath() + "' && printf '\\n\\033[2mPress q to close...\\033[0m\\n' && read -r _");
            var view = createTerminal(cmd);
            if (view == null) return;
            var tab = new Tab();
            tab.setText("Help");
            tab.setContent(new SplitWorkspace() {{
                setContentFactory(() -> createTerminal());
                setRoot(new LeafPane(PaneId.next(), view, "Help"));
            }});
            var ws = (SplitWorkspace) tab.getContent();
            configureWorkspace(ws, tabPane);
            setupTabGraphic(tab, tabPane);
            tabPane.getTabs().add(tab);
            tabPane.getSelectionModel().select(tab);
        } catch (IOException _) {}
    }

    static TabPane findActiveTabPane() {
        if (activeTerminal != null) { var tp = findTabPane(activeTerminal); if (tp != null) return tp; }
        if (!windows.isEmpty()) return getTabPane(windows.getFirst());
        return null;
    }
}
