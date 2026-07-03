package dk.xam.jhostty;

import dk.xam.themes.TerminalColorScheme;
import dk.xam.themes.ThemeRegistry;
import io.github.vlaaad.ghosttyfx.TerminalTheme;
import javafx.scene.paint.Color;

import java.util.List;
import java.util.Optional;

/**
 * Bridge between the terminal-themes registry (1300+ bundled schemes)
 * and ghosttyfx's {@link TerminalTheme}.
 */
public final class Themes {
    private Themes() {}

    /** Wrapper that pairs a display label with a ghosttyfx TerminalTheme. */
    public record ThemeOption(String label, TerminalTheme theme) {
        @Override public String toString() { return label; }
    }

    private static List<ThemeOption> cached;

    /**
     * All available themes as ThemeOptions, ready for ComboBox / menu use.
     * Includes the Ghostty Default plus all bundled schemes.
     */
    public static List<ThemeOption> all() {
        if (cached == null) {
            var registry = ThemeRegistry.create();
            registry.loadBundled();
            var list = new java.util.ArrayList<ThemeOption>();
            list.add(new ThemeOption("Ghostty Default", TerminalTheme.defaults()));
            for (var scheme : registry.all()) {
                try {
                    list.add(new ThemeOption(scheme.name(), toTerminalTheme(scheme)));
                } catch (Exception e) {
                    // skip malformed schemes silently
                }
            }
            cached = List.copyOf(list);
        }
        return cached;
    }

    /** Find a ThemeOption by name (case-insensitive, alias-aware). */
    public static Optional<ThemeOption> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        if ("Ghostty Default".equalsIgnoreCase(name.trim()))
            return Optional.of(all().getFirst());
        // Search our cached list first (exact match on label)
        for (var opt : all()) {
            if (opt.label().equalsIgnoreCase(name.trim())) return Optional.of(opt);
        }
        // Fall back to registry alias/normalized lookup
        var registry = ThemeRegistry.create();
        registry.loadBundled();
        return registry.find(name).map(scheme -> {
            try {
                return new ThemeOption(scheme.name(), toTerminalTheme(scheme));
            } catch (Exception e) {
                return null;
            }
        });
    }

    /** Search themes by query substring. */
    public static List<ThemeOption> search(String query) {
        if (query == null || query.isBlank()) return all();
        String q = query.trim().toLowerCase();
        return all().stream()
                .filter(t -> t.label().toLowerCase().contains(q))
                .toList();
    }

    /** Total number of available themes. */
    public static int count() {
        return all().size();
    }

    /**
     * Convert a {@link TerminalColorScheme} to a ghosttyfx {@link TerminalTheme}.
     */
    public static TerminalTheme toTerminalTheme(TerminalColorScheme scheme) {
        var fg = Color.web(scheme.foreground());
        var bg = Color.web(scheme.background());
        var palette = scheme.palette().stream().map(Color::web).toList();
        var cursor = scheme.cursor() != null ? Color.web(scheme.cursor()) : fg;
        var cursorText = scheme.cursorText() != null ? Color.web(scheme.cursorText()) : bg;
        var selBg = scheme.selectionBackground() != null ? Color.web(scheme.selectionBackground()) : fg;
        var selFg = scheme.selectionForeground() != null ? Color.web(scheme.selectionForeground()) : bg;

        return new TerminalTheme(
                bg, fg, palette,
                cursor, cursorText,
                selBg, selFg,
                0.5,
                fg.deriveColor(0, 1, 1, 0.45),
                fg.deriveColor(0, 1, 1, 0.18),
                fg.deriveColor(0, 1, 1, 0.35)
        );
    }
}
