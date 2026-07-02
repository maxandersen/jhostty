package dk.xam.jhostty;

import io.github.vlaaad.ghosttyfx.TerminalTheme;
import javafx.scene.paint.Color;

import java.util.List;

public final class Themes {
    private Themes() {}

    public record ThemeOption(String label, TerminalTheme theme) {
        @Override public String toString() { return label; }
    }

    public static List<ThemeOption> all() {
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
}
