package dk.xam.themes.sync;

import dk.xam.themes.ColorUtil;
import dk.xam.themes.TerminalColorScheme;

import java.util.*;

/**
 * Deduplicates themes across multiple sources.
 * <p>
 * When two themes from different sources have the same normalized name,
 * the first one wins and the conflicting one gets a source suffix.
 * All original names are preserved as aliases.
 */
public final class ThemeDeduplicator {

    private ThemeDeduplicator() {}

    /**
     * Deduplicate a list of themes from multiple sources.
     * Returns a new list with unique names and aliases for conflicts.
     */
    public static List<TerminalColorScheme> deduplicate(List<TerminalColorScheme> themes) {
        // Group by normalized name
        var byNormalized = new LinkedHashMap<String, List<TerminalColorScheme>>();
        for (var theme : themes) {
            String key = ColorUtil.normalizeName(theme.name());
            byNormalized.computeIfAbsent(key, k -> new ArrayList<>()).add(theme);
        }

        var result = new ArrayList<TerminalColorScheme>();
        for (var entry : byNormalized.entrySet()) {
            var group = entry.getValue();
            if (group.size() == 1) {
                result.add(group.getFirst());
            } else {
                // Multiple themes with same normalized name — disambiguate
                boolean first = true;
                for (var theme : group) {
                    if (first) {
                        // First one keeps its name, gets aliases from others
                        var aliases = new ArrayList<>(theme.aliases());
                        for (int i = 1; i < group.size(); i++) {
                            String suffixed = group.get(i).name() + " (" + group.get(i).sourceName() + ")";
                            aliases.add(suffixed);
                        }
                        result.add(withAliases(theme, aliases));
                        first = false;
                    } else {
                        // Subsequent ones get source suffix
                        String newName = theme.name() + " (" + theme.sourceName() + ")";
                        var aliases = new ArrayList<>(theme.aliases());
                        aliases.add(theme.name()); // original name becomes alias
                        result.add(renamed(theme, newName, aliases));
                    }
                }
            }
        }

        // Sort by name for deterministic output
        result.sort(Comparator.comparing(TerminalColorScheme::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static TerminalColorScheme withAliases(TerminalColorScheme s, List<String> aliases) {
        return new TerminalColorScheme(
                s.name(), aliases, s.sourceName(), s.sourceUrl(),
                s.foreground(), s.background(), s.cursor(), s.cursorText(),
                s.selectionBackground(), s.selectionForeground(),
                s.ansi0(), s.ansi1(), s.ansi2(), s.ansi3(),
                s.ansi4(), s.ansi5(), s.ansi6(), s.ansi7(),
                s.bright0(), s.bright1(), s.bright2(), s.bright3(),
                s.bright4(), s.bright5(), s.bright6(), s.bright7()
        );
    }

    private static TerminalColorScheme renamed(TerminalColorScheme s, String newName, List<String> aliases) {
        return new TerminalColorScheme(
                newName, aliases, s.sourceName(), s.sourceUrl(),
                s.foreground(), s.background(), s.cursor(), s.cursorText(),
                s.selectionBackground(), s.selectionForeground(),
                s.ansi0(), s.ansi1(), s.ansi2(), s.ansi3(),
                s.ansi4(), s.ansi5(), s.ansi6(), s.ansi7(),
                s.bright0(), s.bright1(), s.bright2(), s.bright3(),
                s.bright4(), s.bright5(), s.bright6(), s.bright7()
        );
    }
}
