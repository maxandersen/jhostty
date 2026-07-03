package dk.xam.themes.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dk.xam.themes.ColorUtil;
import dk.xam.themes.TerminalColorScheme;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Imports themes from Gogh (https://github.com/Gogh-Co/Gogh).
 * YAML files with color_01..color_16, background, foreground, cursor.
 */
public class GoghSource implements ThemeSource {
    private static final String API_URL =
            "https://api.github.com/repos/Gogh-Co/Gogh/contents/themes";

    private final HttpCache cache;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public GoghSource(HttpCache cache) { this.cache = cache; }

    @Override public String name() { return "Gogh"; }
    @Override public String url() { return "https://github.com/Gogh-Co/Gogh"; }

    @Override
    public List<TerminalColorScheme> fetch() throws Exception {
        System.out.println("  Fetching file list...");
        var body = cache.get(URI.create(API_URL));
        var files = jsonMapper.readTree(body);

        var progress = new AtomicInteger();
        var futures = new ArrayList<CompletableFuture<TerminalColorScheme>>();
        int total = 0;
        for (var f : files) {
            String n = f.get("name").asText();
            if (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".json")) total++;
        }
        int totalFinal = total;

        for (var file : files) {
            String fileName = file.get("name").asText();
            if (!fileName.endsWith(".yml") && !fileName.endsWith(".yaml") && !fileName.endsWith(".json")) continue;
            boolean isYaml = fileName.endsWith(".yml") || fileName.endsWith(".yaml");
            String downloadUrl = file.get("download_url").asText();

            futures.add(cache.getAsync(HttpCache.safeUri(downloadUrl)).thenApply(content -> {
                int done = progress.incrementAndGet();
                if (done % 100 == 0) System.out.printf("    %d/%d...%n", done, totalFinal);
                try {
                    var m = isYaml ? yamlMapper : jsonMapper;
                    return parseGogh(m.readTree(content));
                } catch (Exception e) { return null; }
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

    private TerminalColorScheme parseGogh(JsonNode node) {
        String name = getStr(node, "name");
        if (name == null || name.isBlank()) return null;
        try {
            return TerminalColorScheme.builder(name)
                    .sourceName(name()).sourceUrl(url())
                    .foreground(getColor(node, "foreground"))
                    .background(getColor(node, "background"))
                    .cursor(getColorOpt(node, "cursor"))
                    .ansi(0, getColor(node, "color_01")).ansi(1, getColor(node, "color_02"))
                    .ansi(2, getColor(node, "color_03")).ansi(3, getColor(node, "color_04"))
                    .ansi(4, getColor(node, "color_05")).ansi(5, getColor(node, "color_06"))
                    .ansi(6, getColor(node, "color_07")).ansi(7, getColor(node, "color_08"))
                    .bright(0, getColor(node, "color_09")).bright(1, getColor(node, "color_10"))
                    .bright(2, getColor(node, "color_11")).bright(3, getColor(node, "color_12"))
                    .bright(4, getColor(node, "color_13")).bright(5, getColor(node, "color_14"))
                    .bright(6, getColor(node, "color_15")).bright(7, getColor(node, "color_16"))
                    .build();
        } catch (Exception e) { return null; }
    }

    private String getStr(JsonNode node, String key) {
        var val = node.get(key);
        return (val != null && !val.isNull()) ? val.asText().trim() : null;
    }

    private String getColor(JsonNode node, String key) {
        var val = node.get(key);
        if (val == null || val.isNull()) throw new IllegalArgumentException("Missing: " + key);
        return val.asText().trim();
    }

    private String getColorOpt(JsonNode node, String key) {
        var val = node.get(key);
        if (val == null || val.isNull()) return null;
        String text = val.asText().trim();
        return ColorUtil.isValidColor(text) ? text : null;
    }
}
