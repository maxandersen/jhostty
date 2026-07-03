package dk.xam.themes.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.xam.themes.ColorUtil;
import dk.xam.themes.TerminalColorScheme;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Imports themes from iTerm2-Color-Schemes via their Windows Terminal JSON export.
 */
public class ITermColorSchemesSource implements ThemeSource {
    private static final String API_URL =
            "https://api.github.com/repos/mbadolato/iTerm2-Color-Schemes/contents/windowsterminal";
    private static final String RAW_BASE =
            "https://raw.githubusercontent.com/mbadolato/iTerm2-Color-Schemes/master/windowsterminal/";

    private final HttpCache cache;
    private final ObjectMapper mapper = new ObjectMapper();

    public ITermColorSchemesSource(HttpCache cache) { this.cache = cache; }

    @Override public String name() { return "iTerm2-Color-Schemes"; }
    @Override public String url() { return "https://github.com/mbadolato/iTerm2-Color-Schemes"; }

    @Override
    public List<TerminalColorScheme> fetch() throws Exception {
        System.out.println("  Fetching file list...");
        var body = cache.get(URI.create(API_URL));
        var files = mapper.readTree(body);

        var progress = new AtomicInteger();
        var futures = new ArrayList<CompletableFuture<TerminalColorScheme>>();
        int total = 0;
        for (var f : files) if (f.get("name").asText().endsWith(".json")) total++;
        int totalFinal = total;

        for (var file : files) {
            String fileName = file.get("name").asText();
            if (!fileName.endsWith(".json")) continue;
            String themeName = fileName.replace(".json", "");
            String downloadUrl = file.has("download_url")
                    ? file.get("download_url").asText() : RAW_BASE + fileName;

            futures.add(cache.getAsync(HttpCache.safeUri(downloadUrl)).thenApply(json -> {
                int done = progress.incrementAndGet();
                if (done % 100 == 0) System.out.printf("    %d/%d...%n", done, totalFinal);
                try { return parseWindowsTerminalJson(themeName, json); }
                catch (Exception e) { return null; }
            }).exceptionally(e -> {
                progress.incrementAndGet();
                System.err.println("    WARN: " + fileName + ": " + e.getMessage());
                return null;
            }));
        }

        var themes = futures.stream().map(CompletableFuture::join).filter(s -> s != null).toList();
        System.out.printf("  Imported %d themes%n", themes.size());
        return themes;
    }

    private TerminalColorScheme parseWindowsTerminalJson(String name, String json) throws Exception {
        var node = mapper.readTree(json);
        return TerminalColorScheme.builder(node.has("name") ? node.get("name").asText() : name)
                .sourceName(name()).sourceUrl(url())
                .foreground(getColor(node, "foreground"))
                .background(getColor(node, "background"))
                .cursor(getColorOpt(node, "cursorColor"))
                .selectionBackground(getColorOpt(node, "selectionBackground"))
                .ansi(0, getColor(node, "black")).ansi(1, getColor(node, "red"))
                .ansi(2, getColor(node, "green")).ansi(3, getColor(node, "yellow"))
                .ansi(4, getColor(node, "blue")).ansi(5, getColor(node, "purple"))
                .ansi(6, getColor(node, "cyan")).ansi(7, getColor(node, "white"))
                .bright(0, getColor(node, "brightBlack")).bright(1, getColor(node, "brightRed"))
                .bright(2, getColor(node, "brightGreen")).bright(3, getColor(node, "brightYellow"))
                .bright(4, getColor(node, "brightBlue")).bright(5, getColor(node, "brightPurple"))
                .bright(6, getColor(node, "brightCyan")).bright(7, getColor(node, "brightWhite"))
                .build();
    }

    private String getColor(JsonNode node, String key) {
        var val = node.get(key);
        if (val == null || val.isNull()) throw new IllegalArgumentException("Missing: " + key);
        return val.asText();
    }

    private String getColorOpt(JsonNode node, String key) {
        var val = node.get(key);
        if (val == null || val.isNull()) return null;
        String text = val.asText();
        return ColorUtil.isValidColor(text) ? text : null;
    }
}
