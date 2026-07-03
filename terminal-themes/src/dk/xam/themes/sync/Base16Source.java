package dk.xam.themes.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dk.xam.themes.TerminalColorScheme;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Imports Base16 / Tinted Theming schemes.
 * <p>
 * Supports both the old flat format ({@code base00: "RRGGBB"}) and the newer
 * spec-0.11 format ({@code palette: { base00: "#RRGGBB" }}).
 * <p>
 * Terminal ANSI mapping (standard Base16):
 * <pre>
 *   ansi0=base00  ansi1=base08  ansi2=base0B  ansi3=base0A
 *   ansi4=base0D  ansi5=base0E  ansi6=base0C  ansi7=base05
 *   bright0=base03 bright1=base09 bright2=base01 bright3=base02
 *   bright4=base04 bright5=base06 bright6=base0F bright7=base07
 * </pre>
 */
public class Base16Source implements ThemeSource {
    private static final String API_URL =
            "https://api.github.com/repos/tinted-theming/schemes/contents/base16";

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Override
    public String name() { return "Base16"; }

    @Override
    public String url() { return "https://github.com/tinted-theming/schemes"; }

    @Override
    public List<TerminalColorScheme> fetch() throws Exception {
        System.out.println("  Fetching file list from Base16/Tinted Theming...");
        var req = HttpRequest.newBuilder(URI.create(API_URL))
                .header("Accept", "application/vnd.github.v3+json")
                .GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("GitHub API returned " + resp.statusCode() + ": " + resp.body());
        }

        var files = jsonMapper.readTree(resp.body());
        var themes = new ArrayList<TerminalColorScheme>();
        int total = files.size(), count = 0;

        for (var file : files) {
            String fileName = file.get("name").asText();
            if (!fileName.endsWith(".yaml") && !fileName.endsWith(".yml")) continue;
            count++;
            if (count % 50 == 0) System.out.printf("    %d/%d themes...%n", count, total);

            try {
                String downloadUrl = file.get("download_url").asText();
                var themeReq = HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build();
                var themeResp = client.send(themeReq, HttpResponse.BodyHandlers.ofString());
                if (themeResp.statusCode() != 200) continue;

                var scheme = parseBase16Yaml(themeResp.body());
                if (scheme != null) themes.add(scheme);
            } catch (Exception e) {
                System.err.println("    WARN: Failed to parse " + fileName + ": " + e.getMessage());
            }
        }
        System.out.printf("  Imported %d themes from Base16%n", themes.size());
        return themes;
    }

    private TerminalColorScheme parseBase16Yaml(String yaml) throws Exception {
        var root = yamlMapper.readTree(yaml);
        String name = null;
        if (root.has("name")) name = root.get("name").asText();
        else if (root.has("scheme")) name = root.get("scheme").asText();
        if (name == null || name.isBlank()) return null;

        // Extract the 16 base colors, handling both formats
        String[] bases = new String[16];
        for (int i = 0; i < 16; i++) {
            String key = String.format("base%02X", i);
            String hex = resolveBaseColor(root, key);
            if (hex == null) return null; // skip incomplete schemes
            bases[i] = hex;
        }

        return TerminalColorScheme.builder("Base16 " + name)
                .sourceName(name())
                .sourceUrl(url())
                .foreground(bases[5])
                .background(bases[0])
                .cursor(bases[5])
                .cursorText(bases[0])
                .selectionBackground(bases[2])
                .selectionForeground(bases[5])
                .ansi(0, bases[0])   // black
                .ansi(1, bases[8])   // red
                .ansi(2, bases[11])  // green
                .ansi(3, bases[10])  // yellow
                .ansi(4, bases[13])  // blue
                .ansi(5, bases[14])  // magenta
                .ansi(6, bases[12])  // cyan
                .ansi(7, bases[5])   // white
                .bright(0, bases[3]) // bright black
                .bright(1, bases[9]) // bright red
                .bright(2, bases[1]) // bright green
                .bright(3, bases[2]) // bright yellow
                .bright(4, bases[4]) // bright blue
                .bright(5, bases[6]) // bright magenta
                .bright(6, bases[15])// bright cyan
                .bright(7, bases[7]) // bright white
                .build();
    }

    /**
     * Resolve a base color key from either format:
     * <ul>
     *   <li>New: {@code palette: { base00: "#282a36" }}</li>
     *   <li>Old: {@code base00: "282a36"}</li>
     * </ul>
     * Returns normalized #RRGGBB.
     */
    private String resolveBaseColor(JsonNode root, String key) {
        // Try new format: palette.baseXX
        JsonNode palette = root.get("palette");
        if (palette != null && palette.has(key)) {
            return normalizeBase16Hex(palette.get(key).asText());
        }
        // Try old flat format: baseXX at root
        if (root.has(key)) {
            return normalizeBase16Hex(root.get(key).asText());
        }
        return null;
    }

    /**
     * Normalize a base16 hex value. May be "282a36" or "#282a36" or "\"282a36\"".
     */
    private String normalizeBase16Hex(String raw) {
        if (raw == null) return null;
        raw = raw.trim().replace("\"", "").replace("'", "");
        if (raw.isEmpty()) return null;
        if (!raw.startsWith("#")) raw = "#" + raw;
        // Validate it's 7 chars (#RRGGBB)
        if (raw.length() != 7) return null;
        return raw.toUpperCase(Locale.ROOT);
    }
}
