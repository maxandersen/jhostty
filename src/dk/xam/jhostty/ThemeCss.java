package dk.xam.jhostty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.paint.Color;

/**
 * Generates the dynamic theme CSS stylesheet.
 */
final class ThemeCss {

    private ThemeCss() {}

    static String colorToCss(Color c) {
        return String.format("rgba(%d,%d,%d,%.2f)",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255), c.getOpacity());
    }


    static double pastelOpacity(Color bg) {
        var lum = luminance(bg);
        return lum < 0.5 ? 0.15 : 0.25;
    }

    static Color focusRingColor(Color bg, Color fg) {
        var lum = luminance(bg);
        return fg.deriveColor(0, 1, 1, lum < 0.5 ? 0.4 : 0.65);
    }

    static double luminance(Color c) {
        return c.getRed() * 0.299 + c.getGreen() * 0.587 + c.getBlue() * 0.114;
    }

    static void write(Path cssPath, Color bg, Color fg, Color selBg) {
        if (cssPath == null) return;
        var lum = luminance(bg);
        var dark = lum < 0.5;
        var menuBg = dark ? bg.brighter().brighter() : bg.darker();
        var menuBgCss = String.format("rgba(%d,%d,%d,0.95)",
                (int)(menuBg.getRed()*255), (int)(menuBg.getGreen()*255), (int)(menuBg.getBlue()*255));
        var fgCss = colorToCss(fg);
        var borderCss = dark ? "rgba(255,255,255,0.12)" : "rgba(0,0,0,0.15)";
        var sepCss = dark ? "rgba(255,255,255,0.1)" : "rgba(0,0,0,0.1)";
        var selCss = String.format("rgba(%d,%d,%d,0.8)",
                (int)(selBg.getRed()*255), (int)(selBg.getGreen()*255), (int)(selBg.getBlue()*255));
        var selText = dark ? "white" : "black";
        var dividerCss = dark ? "#555555" : "#bbbbbb";
        var tabBarBg = colorToCss(dark ? bg.darker() : bg.darker());
        var tabSelectedBg = colorToCss(dark ? bg.brighter() : bg.brighter());
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
                    .jhostty-sidebar .tree-cell:selected,
                    .jhostty-sidebar .tree-cell:filled:selected,
                    .jhostty-sidebar:focused .tree-cell:filled:selected,
                    .jhostty-sidebar:focused .tree-cell:selected,
                    .jhostty-sidebar .tree-cell:filled:focused:selected { -fx-background-color: %s; -fx-text-fill: %s; }
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
                    .jhostty-palette-list .list-cell { -fx-background-color: transparent; -fx-padding: 2 8; }
                    .jhostty-palette-list .jhostty-palette-label { -fx-text-fill: %s; }
                    .jhostty-palette-list .jhostty-palette-cat { -fx-text-fill: %s; }
                    .jhostty-palette-list .jhostty-palette-shortcut { -fx-text-fill: %s; }
                    .jhostty-palette-list .list-cell:selected { -fx-background-color: %s; -fx-background-radius: 4; }
                    .jhostty-palette-list .list-cell:selected .label { -fx-text-fill: %s; }
                    .tool-btn { -fx-background-color: transparent; -fx-padding: 4 6; -fx-cursor: hand; }
                    .tool-btn:hover { -fx-background-color: %s; -fx-background-radius: 4; }
                    .tool-icon { -fx-min-width: 12; -fx-min-height: 12; -fx-max-width: 12; -fx-max-height: 12; -fx-background-color: %s; }
                    .tool-btn:hover .tool-icon { -fx-background-color: %s; }
                    .close-btn { -fx-background-color: transparent; -fx-padding: 2 4; -fx-cursor: hand; }
                    .close-btn:hover { -fx-background-color: %s; -fx-background-radius: 4; }
                    .close-icon { -fx-min-width: 9; -fx-min-height: 9; -fx-max-width: 9; -fx-max-height: 9; -fx-background-color: %s; }
                    .close-btn:hover .close-icon { -fx-background-color: %s; }
                    .pane-close-btn { -fx-background-color: transparent; -fx-padding: 2 4; -fx-cursor: hand; }
                    .pane-close-btn:hover { -fx-background-color: transparent; }
                    .pane-close-icon { -fx-min-width: 7; -fx-min-height: 7; -fx-max-width: 7; -fx-max-height: 7; -fx-background-color: %s; }
                    .pane-close-btn:hover .pane-close-icon { -fx-background-color: %s; }
                    .settings-title { -fx-font-size: 14; -fx-font-weight: bold; }
                    .settings-label { -fx-font-size: 11; -fx-opacity: 0.6; }
                    .settings-value { -fx-font-size: 10; -fx-opacity: 0.5; }
                    .settings-check { -fx-font-size: 11; }
                    .window-title { -fx-text-fill: %s; -fx-font-size: 12; }
                    .sidebar-icon { -fx-font-size: 11; -fx-padding: 0 4 0 0; }
                    .readonly-pill { -fx-background-color: #e67e22; -fx-text-fill: white; -fx-font-size: 10; -fx-font-weight: bold; -fx-padding: 2 8; -fx-background-radius: 10; }
                    .jhostty-palette-search { -fx-font-size: 17; -fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-border-color: rgba(255,255,255,0.15); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 12; }
                    .jhostty-palette-box { -fx-background-color: rgba(30,30,30,0.95); -fx-background-radius: 10; -fx-border-color: rgba(255,255,255,0.12); -fx-border-radius: 10; -fx-padding: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 20, 0, 0, 8); }
                    .jhostty-palette-overlay { -fx-background-color: rgba(0,0,0,0.3); }
                    .tab-overview-title { -fx-text-fill: rgba(255,255,255,0.8); -fx-font-size: 11; }
                    .tab-overview-card { -fx-background-color: rgba(40,40,40,0.85); -fx-background-radius: 10; -fx-cursor: hand; }
                    .tab-overview-card:hover { -fx-background-color: rgba(60,60,60,0.9); }
                    .tab-overview-card-selected { -fx-border-color: #3B82F6; -fx-border-width: 2; -fx-border-radius: 10; }
                    .tab-overview-plus-label { -fx-text-fill: rgba(255,255,255,0.4); -fx-font-size: 36; }
                    .tab-overview-plus-card { -fx-background-color: rgba(80,80,80,0.5); -fx-background-radius: 10; -fx-cursor: hand; }
                    .tab-overview-plus-card:hover { -fx-background-color: rgba(100,100,100,0.6); }
                    .tab-overview-scroll { -fx-background: transparent; -fx-background-color: transparent; }
                    .tab-overview-overlay { -fx-background-color: rgba(0,0,0,0.75); }
                    """.formatted(
                        dividerCss, dividerCss,
                        tabBarBg, tabBarBg, tabSelectedBg, tabTextCss, tabSelectedTextCss,
                        tabCloseCss, tabCloseHoverCss,
                        dividerCss, menuBgCss, borderCss, fgCss, selCss, selText, sepCss,
                        dividerCss, borderCss, menuBgCss, fgCss, sepCss, fgCss,
                        selCss, selText, fgCss,
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
                        fgCss, fgCss, fgCss, selCss, selText,
                        // tool buttons
                        dark ? "rgba(255,255,255,0.1)" : "rgba(0,0,0,0.08)",
                        dark ? "#999" : "#666",
                        dark ? "#ddd" : "#333",
                        // close buttons
                        dark ? "rgba(255,255,255,0.1)" : "rgba(0,0,0,0.08)",
                        dark ? "#888" : "#666",
                        dark ? "#ccc" : "#333",
                        // window title
                        dark ? "rgba(255,255,255,0.6)" : "rgba(0,0,0,0.5)",
                        // pane close button (faint default, brighter on hover)
                        dark ? "rgba(255,255,255,0.15)" : "rgba(0,0,0,0.1)",
                        dark ? "rgba(255,255,255,0.7)" : "rgba(0,0,0,0.6)"
                        ));
        } catch (IOException _) {}
    }
}
