package dk.xam.themes.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dk.xam.themes.TerminalColorScheme;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Imports Base16 / Tinted Theming schemes.
 * <p>
 * Uses the tinted-theming/schemes repo which has YAML scheme definitions.
 * Maps base00..base0F to terminal color slots using the conventional Base16 mapping:
 * <pre>
 *   base00 - background
 *   base01 - lighter background (selection)
 *   base02 - selection background
 *   base03 - comments, line highlighting
 *   base04 - dark foreground
 *   base05 - foreground
 *   base06 - light foreground
 *   base07 - light background
 *   base08 - red
 *   base09 - orange (bright red)
 *   base0A - yellow
 *   base0B - green
 *   base0C - cyan
 *   base0D - blue
 *   base0E - magenta
 *   base0F - brown (bright yellow for some mappings)
 * </pre>
 * Terminal ANSI mapping:
 * <pre>
 *   ansi0=base00  ansi1=base08  ansi2=base0B  ansi3=base0A
 *   ansi4=base0D  ansi5=base0E  ansi6=base0C  ansi7=base05
 *   bright0=base03  bright1=base09  bright2=base01  bright3=base02
 *   bright4=base04  bright5=base06  bright6=base0F  bright7=base07
 * </pre>
 */
public class Base16Source implements ThemeSource {
    private static final String API_URL =
            "https://api.github.com/repos/tinted-theming/schemes/contents/base16";
    private static final String RAW_BASE =
            "https://raw.githubusercontent.com/tinted-theming/schemes/main/base16/";

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
                String downloadUrl = file.has("download_url")
                        ? file.get("download_url").asText()
                        : RAW_BASE + fileName;
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

    @SuppressWarnings("unchecked")
    private TerminalColorScheme parseBase16Yaml(String yaml) throws Exception {
        var data = yamlMapper.readValue(yaml, Map.class);
        String name = (String) data.getOrDefault("name", data.get("scheme"));
        if (name == null || name.isBlank()) return null;

        // Get hex colors — base16 YAML stores them without # prefix
        String[] bases = new String[16];
        for (int i = 0; i < 16; i++) {
            String key = String.format("base%02X", i);
            Object val = data.get(key);
            if (val == null) return null;
            bases[i] = "#" + val.toString().toUpperCase();
        }

        // Standard Base16 → terminal mapping
        return TerminalColorScheme.builder("Base16 " + name)
                .sourceName(name())
                .sourceUrl(url())
                .foreground(bases[5])   // base05
                .background(bases[0])   // base00
                .cursor(bases[5])       // base05
                .cursorText(bases[0])   // base00
                .selectionBackground(bases[2])  // base02
                .selectionForeground(bases[5])  // base05
                .ansi(0, bases[0])   // black = base00
                .ansi(1, bases[8])   // red = base08
                .ansi(2, bases[11])  // green = base0B
                .ansi(3, bases[10])  // yellow = base0A
                .ansi(4, bases[13])  // blue = base0D
                .ansi(5, bases[14])  // magenta = base0E
                .ansi(6, bases[12])  // cyan = base0C
                .ansi(7, bases[5])   // white = base05
                .bright(0, bases[3])  // bright black = base03
                .bright(1, bases[9])  // bright red = base09
                .bright(2, bases[1])  // bright green = base01
                .bright(3, bases[2])  // bright yellow = base02
                .bright(4, bases[4])  // bright blue = base04
                .bright(5, bases[6])  // bright magenta = base06
                .bright(6, bases[15]) // bright cyan = base0F
                .bright(7, bases[7])  // bright white = base07
                .build();
    }
}
