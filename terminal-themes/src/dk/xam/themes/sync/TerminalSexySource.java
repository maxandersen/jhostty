package dk.xam.themes.sync;

import com.fasterxml.jackson.core.type.TypeReference;
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
 * Imports themes from terminal.sexy via dist/schemes/ index + JSON files.
 */
public class TerminalSexySource implements ThemeSource {
    private static final String INDEX_URL =
            "https://raw.githubusercontent.com/stayradiated/terminal.sexy/master/dist/schemes/index.json";
    private static final String SCHEME_BASE =
            "https://raw.githubusercontent.com/stayradiated/terminal.sexy/master/dist/schemes/";

    private final HttpCache cache;
    private final ObjectMapper mapper = new ObjectMapper();

    public TerminalSexySource(HttpCache cache) { this.cache = cache; }

    @Override public String name() { return "terminal.sexy"; }
    @Override public String url() { return "https://terminal.sexy/"; }

    @Override
    public List<TerminalColorScheme> fetch() throws Exception {
        System.out.println("  Fetching index...");
        var body = cache.get(URI.create(INDEX_URL));
        List<String> schemePaths = mapper.readValue(body, new TypeReference<>() {});

        var progress = new AtomicInteger();
        var futures = new ArrayList<CompletableFuture<TerminalColorScheme>>();
        int total = schemePaths.size();

        for (String path : schemePaths) {
            futures.add(cache.getAsync(HttpCache.safeUri(SCHEME_BASE + path + ".json")).thenApply(json -> {
                int done = progress.incrementAndGet();
                if (done % 50 == 0) System.out.printf("    %d/%d...%n", done, total);
                try { return parseScheme(path, json); }
                catch (Exception e) { return null; }
            }).exceptionally(e -> {
                progress.incrementAndGet();
                System.err.println("    WARN: " + path + ": " + e.getMessage());
                return null;
            }));
        }

        var themes = futures.stream().map(CompletableFuture::join).filter(s -> s != null).toList();
        System.out.printf("  Imported %d themes%n", themes.size());
        return themes;
    }

    private TerminalColorScheme parseScheme(String path, String json) throws Exception {
        var node = mapper.readTree(json);
        JsonNode colors = node.get("color");
        if (colors == null || !colors.isArray() || colors.size() < 16) return null;

        String name = node.has("name") && !node.get("name").asText().isBlank()
                ? node.get("name").asText() : prettifyPath(path);

        String fg = getColorOpt(node, "foreground");
        String bg = getColorOpt(node, "background");
        if (fg == null) fg = colors.get(7).asText();
        if (bg == null) bg = colors.get(0).asText();

        return TerminalColorScheme.builder(name)
                .sourceName(name()).sourceUrl(url())
                .foreground(fg).background(bg)
                .cursor(getColorOpt(node, "cursor"))
                .ansi(0, colors.get(0).asText()).ansi(1, colors.get(1).asText())
                .ansi(2, colors.get(2).asText()).ansi(3, colors.get(3).asText())
                .ansi(4, colors.get(4).asText()).ansi(5, colors.get(5).asText())
                .ansi(6, colors.get(6).asText()).ansi(7, colors.get(7).asText())
                .bright(0, colors.get(8).asText()).bright(1, colors.get(9).asText())
                .bright(2, colors.get(10).asText()).bright(3, colors.get(11).asText())
                .bright(4, colors.get(12).asText()).bright(5, colors.get(13).asText())
                .bright(6, colors.get(14).asText()).bright(7, colors.get(15).asText())
                .build();
    }

    private String prettifyPath(String path) {
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        return base.replace('.', ' ').replace('-', ' ').trim();
    }

    private String getColorOpt(JsonNode node, String key) {
        var val = node.get(key);
        if (val == null || val.isNull()) return null;
        String text = val.asText().trim();
        return ColorUtil.isValidColor(text) ? text : null;
    }
}
