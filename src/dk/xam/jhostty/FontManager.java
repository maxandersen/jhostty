package dk.xam.jhostty;

import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class FontManager {
    private FontManager() {}


    public static String defaultFontFamily() {
        var families = Font.getFamilies();
        for (var family : List.of(
                "JetBrainsMono Nerd Font Mono", "JetBrainsMono Nerd Font",
                "JetBrains Mono", "SF Mono", "Menlo", "Monaco", "Consolas")) {
            if (families.contains(family)) return family;
        }
        return "Monospaced";
    }

    public static Font resolveFont(String family, double size) {
        return new Font(Font.getFontNames(family).stream()
                .filter(n -> n.endsWith("Regular") || n.equals(family))
                .findFirst()
                .orElse(family), size);
    }

    public static List<String> detectFonts() {
        var preferred = List.of(
                "JetBrainsMono Nerd Font Mono", "JetBrainsMono Nerd Font",
                "JetBrains Mono", "FiraCode Nerd Font", "Hack Nerd Font",
                "SF Mono", "Menlo", "Monaco", "Consolas", "Courier New");
        var all = Font.getFamilies();
        var result = new ArrayList<String>();
        var added = new LinkedHashSet<String>();
        for (var family : preferred) {
            if (all.contains(family) && added.add(family)) result.add(family);
        }
        for (var family : all) {
            if (added.add(family)) result.add(family);
        }
        return result;
    }
}
