package dk.xam.themes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

/**
 * Immutable terminal color scheme with normalized #RRGGBB hex colors.
 * <p>
 * All ANSI 16 colors (ansi0-7, bright0-7), foreground, and background are required.
 * Cursor, selection, and other colors are optional (nullable).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TerminalColorScheme(
        String name,
        List<String> aliases,
        String sourceName,
        String sourceUrl,
        String foreground,
        String background,
        String cursor,
        String cursorText,
        String selectionBackground,
        String selectionForeground,
        String ansi0, String ansi1, String ansi2, String ansi3,
        String ansi4, String ansi5, String ansi6, String ansi7,
        String bright0, String bright1, String bright2, String bright3,
        String bright4, String bright5, String bright6, String bright7
) {
    public TerminalColorScheme {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(foreground, "foreground");
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(ansi0, "ansi0");
        Objects.requireNonNull(ansi1, "ansi1");
        Objects.requireNonNull(ansi2, "ansi2");
        Objects.requireNonNull(ansi3, "ansi3");
        Objects.requireNonNull(ansi4, "ansi4");
        Objects.requireNonNull(ansi5, "ansi5");
        Objects.requireNonNull(ansi6, "ansi6");
        Objects.requireNonNull(ansi7, "ansi7");
        Objects.requireNonNull(bright0, "bright0");
        Objects.requireNonNull(bright1, "bright1");
        Objects.requireNonNull(bright2, "bright2");
        Objects.requireNonNull(bright3, "bright3");
        Objects.requireNonNull(bright4, "bright4");
        Objects.requireNonNull(bright5, "bright5");
        Objects.requireNonNull(bright6, "bright6");
        Objects.requireNonNull(bright7, "bright7");
        if (aliases == null) aliases = List.of();
        foreground = ColorUtil.normalize(foreground);
        background = ColorUtil.normalize(background);
        cursor = cursor != null ? ColorUtil.normalize(cursor) : null;
        cursorText = cursorText != null ? ColorUtil.normalize(cursorText) : null;
        selectionBackground = selectionBackground != null ? ColorUtil.normalize(selectionBackground) : null;
        selectionForeground = selectionForeground != null ? ColorUtil.normalize(selectionForeground) : null;
        ansi0 = ColorUtil.normalize(ansi0);
        ansi1 = ColorUtil.normalize(ansi1);
        ansi2 = ColorUtil.normalize(ansi2);
        ansi3 = ColorUtil.normalize(ansi3);
        ansi4 = ColorUtil.normalize(ansi4);
        ansi5 = ColorUtil.normalize(ansi5);
        ansi6 = ColorUtil.normalize(ansi6);
        ansi7 = ColorUtil.normalize(ansi7);
        bright0 = ColorUtil.normalize(bright0);
        bright1 = ColorUtil.normalize(bright1);
        bright2 = ColorUtil.normalize(bright2);
        bright3 = ColorUtil.normalize(bright3);
        bright4 = ColorUtil.normalize(bright4);
        bright5 = ColorUtil.normalize(bright5);
        bright6 = ColorUtil.normalize(bright6);
        bright7 = ColorUtil.normalize(bright7);
    }

    /** Returns the 16 ANSI colors as a list: ansi0-7 then bright0-7. */
    public List<String> palette() {
        return List.of(
                ansi0, ansi1, ansi2, ansi3, ansi4, ansi5, ansi6, ansi7,
                bright0, bright1, bright2, bright3, bright4, bright5, bright6, bright7
        );
    }

    /** Effective cursor color (falls back to foreground). */
    public String effectiveCursor() {
        return cursor != null ? cursor : foreground;
    }

    /** Effective cursor text color (falls back to background). */
    public String effectiveCursorText() {
        return cursorText != null ? cursorText : background;
    }

    /** Effective selection background (falls back to ansi4). */
    public String effectiveSelectionBackground() {
        return selectionBackground != null ? selectionBackground : ansi4;
    }

    /** Effective selection foreground (falls back to foreground). */
    public String effectiveSelectionForeground() {
        return selectionForeground != null ? selectionForeground : foreground;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private List<String> aliases = List.of();
        private String sourceName, sourceUrl;
        private String foreground, background;
        private String cursor, cursorText, selectionBackground, selectionForeground;
        private final String[] ansi = new String[8];
        private final String[] bright = new String[8];

        private Builder(String name) { this.name = name; }

        public Builder aliases(List<String> v) { this.aliases = v; return this; }
        public Builder sourceName(String v) { this.sourceName = v; return this; }
        public Builder sourceUrl(String v) { this.sourceUrl = v; return this; }
        public Builder foreground(String v) { this.foreground = v; return this; }
        public Builder background(String v) { this.background = v; return this; }
        public Builder cursor(String v) { this.cursor = v; return this; }
        public Builder cursorText(String v) { this.cursorText = v; return this; }
        public Builder selectionBackground(String v) { this.selectionBackground = v; return this; }
        public Builder selectionForeground(String v) { this.selectionForeground = v; return this; }
        public Builder ansi(int i, String v) { this.ansi[i] = v; return this; }
        public Builder bright(int i, String v) { this.bright[i] = v; return this; }

        /** Set all 16 palette colors from a list (ansi0-7, bright0-7). */
        public Builder palette(List<String> colors) {
            if (colors.size() != 16) throw new IllegalArgumentException("palette must have 16 colors, got " + colors.size());
            for (int i = 0; i < 8; i++) { ansi[i] = colors.get(i); bright[i] = colors.get(i + 8); }
            return this;
        }

        public TerminalColorScheme build() {
            return new TerminalColorScheme(name, aliases, sourceName, sourceUrl,
                    foreground, background, cursor, cursorText,
                    selectionBackground, selectionForeground,
                    ansi[0], ansi[1], ansi[2], ansi[3], ansi[4], ansi[5], ansi[6], ansi[7],
                    bright[0], bright[1], bright[2], bright[3], bright[4], bright[5], bright[6], bright[7]);
        }
    }
}
