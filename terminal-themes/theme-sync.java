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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Build-time theme sync tool.
 * <p>
 * Downloads themes from iTerm2-Color-Schemes, Gogh, Base16/Tinted, and terminal.sexy,
 * deduplicates them, and writes {@code themes/builtin-themes.json}.
 * <p>
 * Features:
 * - Parallel downloads across and within sources (20 concurrent HTTP requests)
 * - Disk cache in .cache/themes/ (default 24h TTL) — second runs are near-instant
 * - --no-cache to force fresh downloads
 * - --clear-cache to wipe cached data
 */
class themesync {

    public static void main(String[] args) throws Exception {
        String filterSource = null;
        String outputPath = "themes/builtin-themes.json";
        boolean useCache = true;
        boolean clearCache = false;
        long maxAgeHours = 24;

        for (String arg : args) {
            if (arg.startsWith("--source=")) filterSource = arg.substring("--source=".length());
            else if (arg.startsWith("--output=")) outputPath = arg.substring("--output=".length());
            else if ("--no-cache".equals(arg)) useCache = false;
            else if ("--clear-cache".equals(arg)) clearCache = true;
            else if (arg.startsWith("--max-age=")) maxAgeHours = Long.parseLong(arg.substring("--max-age=".length()));
            else if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println("""
                    Usage: jbang theme-sync.java [options]

                    Options:
                      --source=NAME      Sync only one source: iterm, gogh, base16, terminalsexy
                      --output=PATH      Output file path (default: themes/builtin-themes.json)
                      --no-cache         Skip disk cache, download everything fresh
                      --clear-cache      Clear the cache directory before syncing
                      --max-age=HOURS    Cache TTL in hours (default: 24)
                      -h, --help         Show this help

                    Sources synced by default: iTerm2-Color-Schemes, Gogh, Base16, terminal.sexy
                    Cache stored in: .cache/themes/
                    """);
                return;
            }
        }

        var cacheDir = Path.of(".cache/themes");
        var cache = new HttpCache(cacheDir, Duration.ofHours(maxAgeHours), useCache, 20);

        if (clearCache) {
            System.out.println("Clearing cache...");
            cache.clearAll();
        }

        List<ThemeSource> sources = new ArrayList<>();
        if (filterSource == null || "iterm".equalsIgnoreCase(filterSource))
            sources.add(new ITermColorSchemesSource(cache));
        if (filterSource == null || "gogh".equalsIgnoreCase(filterSource))
            sources.add(new GoghSource(cache));
        if (filterSource == null || "base16".equalsIgnoreCase(filterSource))
            sources.add(new Base16Source(cache));
        if (filterSource == null || "terminalsexy".equalsIgnoreCase(filterSource))
            sources.add(new TerminalSexySource(cache));

        System.out.println("=== Terminal Theme Sync ===");
        System.out.printf("Syncing from %d source(s)%s...%n%n",
                sources.size(), useCache ? " (cache: " + maxAgeHours + "h TTL)" : " (no cache)");

        long startTime = System.currentTimeMillis();

        // Run all sources in parallel
        var executor = Executors.newFixedThreadPool(sources.size());
        var futures = new ArrayList<Future<SourceResult>>();
        for (var source : sources) {
            futures.add(executor.submit(() -> {
                System.out.printf("[%s] (%s)%n", source.name(), source.url());
                try {
                    var fetched = source.fetch();
                    return new SourceResult(source.name(), fetched, null);
                } catch (Exception e) {
                    System.err.printf("  ERROR: %s: %s%n", source.name(), e.getMessage());
                    return new SourceResult(source.name(), List.of(), e);
                }
            }));
        }
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.MINUTES);

        var allThemes = new ArrayList<TerminalColorScheme>();
        System.out.println();
        for (var future : futures) {
            var result = future.get();
            if (result.error == null) {
                System.out.printf("  %-25s %d themes%n", result.sourceName, result.themes.size());
            } else {
                System.out.printf("  %-25s FAILED: %s%n", result.sourceName, result.error.getMessage());
            }
            allThemes.addAll(result.themes);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.printf("%nTotal themes before dedup: %d%n", allThemes.size());
        var deduped = ThemeDeduplicator.deduplicate(allThemes);
        System.out.printf("Total themes after dedup:  %d%n", deduped.size());

        // Write output
        var outPath = Path.of(outputPath);
        Files.createDirectories(outPath.getParent());
        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(outPath.toFile(), deduped);

        System.out.printf("%nWrote %s (%d themes, %s)%n", outPath, deduped.size(), humanSize(Files.size(outPath)));
        System.out.printf("Time: %.1fs  |  Network: %d requests  |  Cache hits: %d%n",
                elapsed / 1000.0, cache.networkRequests(), cache.cacheHits());
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    record SourceResult(String sourceName, List<TerminalColorScheme> themes, Exception error) {}
}
