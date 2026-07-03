package dk.xam.themes.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dk.xam.themes.TerminalColorScheme;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Imports Base16 / Tinted Theming schemes.
 * Supports both old flat format and newer spec-0.11 {@code palette:} format.
 */
public class Base16Source implements ThemeSource {
    private static final String API_URL =
            "https://api.github.com/repos/tinted-theming/schemes/contents/base16";

    private final HttpCache cache;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public Base16Source(HttpCache cache) { this.cache = cache; }

    @Override public String name() { return "Base16"; }
    @Override public String url() { return "https://github.com/tinted-theming/schemes"; }

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
            if (n.endsWith(".yaml") || n.endsWith(".yml")) total++;
        }
        int totalFinal = total;

        for (var file : files) {
            String fileName = file.get("name").asText();
            if (!fileName.endsWith(".yaml") && !fileName.endsWith(".yml")) continue;
            String downloadUrl = file.get("download_url").asText();

            futures.add(cache.getAsync(HttpCache.safeUri(downloadUrl)).thenApply(yaml -> {
                int done = progress.incrementAndGet();
                if (done % 100 == 0) System.out.printf("    %d/%d...%n", done, totalFinal);
                try { return parseBase16Yaml(yaml); }
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

    private TerminalColorScheme parseBase16Yaml(String yaml) throws Exception {
        var root = yamlMapper.readTree(yaml);
        String name = null;
        if (root.has("name")) name = root.get("name").asText();
        else if (root.has("scheme")) name = root.get("scheme").asText();
        if (name == null || name.isBlank()) return null;

        String[] bases = new String[16];
        for (int i = 0; i < 16; i++) {
            String key = String.format("base%02X", i);
            String hex = resolveBaseColor(root, key);
            if (hex == null) return null;
            bases[i] = hex;
        }

        return TerminalColorScheme.builder("Base16 " + name)
                .sourceName(name()).sourceUrl(url())
                .foreground(bases[5]).background(bases[0])
                .cursor(bases[5]).cursorText(bases[0])
                .selectionBackground(bases[2]).selectionForeground(bases[5])
                .ansi(0, bases[0]).ansi(1, bases[8]).ansi(2, bases[11]).ansi(3, bases[10])
                .ansi(4, bases[13]).ansi(5, bases[14]).ansi(6, bases[12]).ansi(7, bases[5])
                .bright(0, bases[3]).bright(1, bases[9]).bright(2, bases[1]).bright(3, bases[2])
                .bright(4, bases[4]).bright(5, bases[6]).bright(6, bases[15]).bright(7, bases[7])
                .build();
    }

    private String resolveBaseColor(JsonNode root, String key) {
        JsonNode palette = root.get("palette");
        if (palette != null && palette.has(key))
            return normalizeBase16Hex(palette.get(key).asText());
        if (root.has(key))
            return normalizeBase16Hex(root.get(key).asText());
        return null;
    }

    private String normalizeBase16Hex(String raw) {
        if (raw == null) return null;
        raw = raw.trim().replace("\"", "").replace("'", "");
        if (raw.isEmpty()) return null;
        if (!raw.startsWith("#")) raw = "#" + raw;
        if (raw.length() != 7) return null;
        return raw.toUpperCase(Locale.ROOT);
    }
}
