package dk.xam.themes.sync;

import dk.xam.themes.ColorUtil;
import dk.xam.themes.TerminalColorScheme;

import java.util.*;

/**
 * Deduplicates themes across multiple sources.
 * <p>
 * When two themes from different sources share the same normalized name:
 * <ul>
 *   <li>If their colors are identical, they are merged into one entry
 *       (keeping the first name, accumulating aliases and sources).</li>
 *   <li>If their colors differ, each gets a source suffix to disambiguate,
 *       and the bare name is preserved as an alias on the first one.</li>
 * </ul>
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
                continue;
            }

            // Partition duplicates into buckets of color-identical schemes
            var buckets = partitionByColors(group);

            if (buckets.size() == 1) {
                // All duplicates have identical colors — merge into one entry
                result.add(mergeIdentical(buckets.getFirst()));
            } else {
                // Actual color differences — disambiguate with source suffix
                for (var bucket : buckets) {
                    var merged = mergeIdentical(bucket);
                    String newName = merged.name() + " (" + merged.sourceName() + ")";
                    var aliases = new ArrayList<>(merged.aliases());
                    aliases.add(merged.name()); // bare name as alias
                    result.add(renamed(merged, newName, aliases));
                }
            }
        }

        // Sort by name for deterministic output
        result.sort(Comparator.comparing(TerminalColorScheme::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * Extract just the color fingerprint — the 16 ANSI + fg/bg values that
     * define the visual appearance. Ignores name, aliases, source metadata,
     * and optional cursor/selection colors for the identity check.
     */
    private static String colorFingerprint(TerminalColorScheme s) {
        return String.join("|",
                s.foreground(), s.background(),
                s.ansi0(), s.ansi1(), s.ansi2(), s.ansi3(),
                s.ansi4(), s.ansi5(), s.ansi6(), s.ansi7(),
                s.bright0(), s.bright1(), s.bright2(), s.bright3(),
                s.bright4(), s.bright5(), s.bright6(), s.bright7());
    }

    /**
     * Partition a group of same-name themes into buckets where each bucket
     * contains themes with identical color fingerprints.
     */
    private static List<List<TerminalColorScheme>> partitionByColors(List<TerminalColorScheme> group) {
        var buckets = new LinkedHashMap<String, List<TerminalColorScheme>>();
        for (var theme : group) {
            buckets.computeIfAbsent(colorFingerprint(theme), k -> new ArrayList<>()).add(theme);
        }
        return new ArrayList<>(buckets.values());
    }

    /**
     * Merge a bucket of color-identical themes into one entry.
     * Keeps the first theme's name, accumulates all aliases and source names.
     */
    private static TerminalColorScheme mergeIdentical(List<TerminalColorScheme> bucket) {
        var first = bucket.getFirst();
        if (bucket.size() == 1) return first;

        var aliases = new LinkedHashSet<>(first.aliases());
        var sourceNames = new LinkedHashSet<String>();
        if (first.sourceName() != null) sourceNames.add(first.sourceName());

        for (int i = 1; i < bucket.size(); i++) {
            var other = bucket.get(i);
            // Add the other theme's name as an alias if different
            if (!ColorUtil.normalizeName(other.name()).equals(ColorUtil.normalizeName(first.name()))) {
                aliases.add(other.name());
            }
            aliases.addAll(other.aliases());
            if (other.sourceName() != null) sourceNames.add(other.sourceName());
        }

        // Pick the best cursor/selection values — prefer non-null
        String cursor = coalesce(first.cursor(), bucket);
        String cursorText = coalesceField(bucket, TerminalColorScheme::cursorText);
        String selBg = coalesceField(bucket, TerminalColorScheme::selectionBackground);
        String selFg = coalesceField(bucket, TerminalColorScheme::selectionForeground);

        return new TerminalColorScheme(
                first.name(), new ArrayList<>(aliases),
                String.join(", ", sourceNames), first.sourceUrl(),
                first.foreground(), first.background(),
                cursor, cursorText, selBg, selFg,
                first.ansi0(), first.ansi1(), first.ansi2(), first.ansi3(),
                first.ansi4(), first.ansi5(), first.ansi6(), first.ansi7(),
                first.bright0(), first.bright1(), first.bright2(), first.bright3(),
                first.bright4(), first.bright5(), first.bright6(), first.bright7()
        );
    }

    private static String coalesce(String first, List<TerminalColorScheme> bucket) {
        return coalesceField(bucket, TerminalColorScheme::cursor);
    }

    private static String coalesceField(List<TerminalColorScheme> bucket,
                                         java.util.function.Function<TerminalColorScheme, String> getter) {
        for (var t : bucket) {
            String val = getter.apply(t);
            if (val != null) return val;
        }
        return null;
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
