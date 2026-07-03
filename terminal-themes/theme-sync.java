///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2
//SOURCES src/dk/xam/themes/*.java
//SOURCES src/dk/xam/themes/sync/*.java

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dk.xam.themes.TerminalColorScheme;
import dk.xam.themes.sync.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Build-time theme sync tool.
 * <p>
 * Downloads themes from iTerm2-Color-Schemes, Gogh, Base16/Tinted, and terminal.sexy,
 * deduplicates them, and writes {@code themes/builtin-themes.json}.
 * <p>
 * Usage: {@code jbang theme-sync.java [--source=iterm|gogh|base16|terminalsexy] [--output=path]}
 */
class themesync {

    public static void main(String[] args) throws Exception {
        String filterSource = null;
        String outputPath = "themes/builtin-themes.json";

        for (String arg : args) {
            if (arg.startsWith("--source=")) filterSource = arg.substring("--source=".length());
            else if (arg.startsWith("--output=")) outputPath = arg.substring("--output=".length());
            else if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println("""
                    Usage: jbang theme-sync.java [options]

                    Options:
                      --source=NAME    Sync only one source: iterm, gogh, base16, terminalsexy
                      --output=PATH    Output file path (default: themes/builtin-themes.json)
                      -h, --help       Show this help

                    Sources synced by default: iTerm2-Color-Schemes, Gogh, Base16, terminal.sexy
                    """);
                return;
            }
        }

        List<ThemeSource> sources = new ArrayList<>();
        if (filterSource == null || "iterm".equalsIgnoreCase(filterSource))
            sources.add(new ITermColorSchemesSource());
        if (filterSource == null || "gogh".equalsIgnoreCase(filterSource))
            sources.add(new GoghSource());
        if (filterSource == null || "base16".equalsIgnoreCase(filterSource))
            sources.add(new Base16Source());
        if (filterSource == null || "terminalsexy".equalsIgnoreCase(filterSource))
            sources.add(new TerminalSexySource());

        System.out.println("=== Terminal Theme Sync ===");
        System.out.printf("Syncing from %d source(s)...%n%n", sources.size());

        var allThemes = new ArrayList<TerminalColorScheme>();
        for (var source : sources) {
            System.out.printf("[%s] (%s)%n", source.name(), source.url());
            try {
                var fetched = source.fetch();
                allThemes.addAll(fetched);
            } catch (Exception e) {
                System.err.printf("  ERROR: Failed to sync %s: %s%n", source.name(), e.getMessage());
            }
            System.out.println();
        }

        System.out.printf("Total themes before dedup: %d%n", allThemes.size());
        var deduped = ThemeDeduplicator.deduplicate(allThemes);
        System.out.printf("Total themes after dedup:  %d%n", deduped.size());

        // Write output
        var outPath = Path.of(outputPath);
        Files.createDirectories(outPath.getParent());
        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(outPath.toFile(), deduped);
        System.out.printf("%nWrote %s (%d themes, %s)%n",
                outPath, deduped.size(), humanSize(Files.size(outPath)));
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
