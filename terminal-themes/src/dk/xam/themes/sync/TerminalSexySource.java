package dk.xam.themes.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.xam.themes.ColorUtil;
import dk.xam.themes.TerminalColorScheme;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports themes from terminal.sexy (https://github.com/stayradiated/terminal.sexy).
 * <p>
 * The repo stores scheme data in {@code schemes/} as JSON files with color arrays.
 */
public class TerminalSexySource implements ThemeSource {
    private static final String API_URL =
            "https://api.github.com/repos/stayradiated/terminal.sexy/contents/schemes";
    private static final String RAW_BASE =
            "https://raw.githubusercontent.com/stayradiated/terminal.sexy/master/schemes/";

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() { return "terminal.sexy"; }

    @Override
    public String url() { return "https://terminal.sexy/"; }

    @Override
    public List<TerminalColorScheme> fetch() throws Exception {
        System.out.println("  Fetching file list from terminal.sexy...");
        // terminal.sexy has subdirectories per category
        var allThemes = new ArrayList<TerminalColorScheme>();
        fetchDir(API_URL, allThemes);
        System.out.printf("  Imported %d themes from terminal.sexy%n", allThemes.size());
        return allThemes;
    }

    private void fetchDir(String apiUrl, List<TerminalColorScheme> themes) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(apiUrl))
                .header("Accept", "application/vnd.github.v3+json")
                .GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            System.err.println("    WARN: API returned " + resp.statusCode() + " for " + apiUrl);
            return;
        }

        var files = mapper.readTree(resp.body());
        for (var file : files) {
            String type = file.get("type").asText();
            String name = file.get("name").asText();

            if ("dir".equals(type)) {
                // Recurse into subdirectories
                fetchDir(file.get("url").asText(), themes);
            } else if (name.endsWith(".json")) {
                try {
                    String downloadUrl = file.has("download_url")
                            ? file.get("download_url").asText()
                            : RAW_BASE + name;
                    var themeReq = HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build();
                    var themeResp = client.send(themeReq, HttpResponse.BodyHandlers.ofString());
                    if (themeResp.statusCode() != 200) continue;

                    var parsed = parseTerminalSexyJson(name.replace(".json", ""), themeResp.body());
                    if (parsed != null) themes.add(parsed);
                } catch (Exception e) {
                    System.err.println("    WARN: Failed to parse " + name + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Parse terminal.sexy JSON format.
     * Expected format: { "color": ["#hex"x16], "foreground": "#hex", "background": "#hex" }
     */
    private TerminalColorScheme parseTerminalSexyJson(String name, String json) throws Exception {
        var node = mapper.readTree(json);

        // Try array-of-colors format
        JsonNode colors = node.get("color");
        if (colors == null || !colors.isArray() || colors.size() < 16) {
            // Try flat format with named keys
            return parseNamedKeys(name, node);
        }

        String fg = getColorOpt(node, "foreground");
        String bg = getColorOpt(node, "background");
        if (fg == null) fg = colors.get(7).asText();
        if (bg == null) bg = colors.get(0).asText();

        return TerminalColorScheme.builder(name)
                .sourceName(name())
                .sourceUrl(url())
                .foreground(fg)
                .background(bg)
                .cursor(getColorOpt(node, "cursor"))
                .ansi(0, colors.get(0).asText())
                .ansi(1, colors.get(1).asText())
                .ansi(2, colors.get(2).asText())
                .ansi(3, colors.get(3).asText())
                .ansi(4, colors.get(4).asText())
                .ansi(5, colors.get(5).asText())
                .ansi(6, colors.get(6).asText())
                .ansi(7, colors.get(7).asText())
                .bright(0, colors.get(8).asText())
                .bright(1, colors.get(9).asText())
                .bright(2, colors.get(10).asText())
                .bright(3, colors.get(11).asText())
                .bright(4, colors.get(12).asText())
                .bright(5, colors.get(13).asText())
                .bright(6, colors.get(14).asText())
                .bright(7, colors.get(15).asText())
                .build();
    }

    private TerminalColorScheme parseNamedKeys(String name, JsonNode node) {
        // Some terminal.sexy exports use named keys
        try {
            return TerminalColorScheme.builder(name)
                    .sourceName(name())
                    .sourceUrl(url())
                    .foreground(getColor(node, "foreground"))
                    .background(getColor(node, "background"))
                    .ansi(0, getColor(node, "black"))
                    .ansi(1, getColor(node, "red"))
                    .ansi(2, getColor(node, "green"))
                    .ansi(3, getColor(node, "yellow"))
                    .ansi(4, getColor(node, "blue"))
                    .ansi(5, getColor(node, "magenta"))
                    .ansi(6, getColor(node, "cyan"))
                    .ansi(7, getColor(node, "white"))
                    .bright(0, getColor(node, "brightBlack"))
                    .bright(1, getColor(node, "brightRed"))
                    .bright(2, getColor(node, "brightGreen"))
                    .bright(3, getColor(node, "brightYellow"))
                    .bright(4, getColor(node, "brightBlue"))
                    .bright(5, getColor(node, "brightMagenta"))
                    .bright(6, getColor(node, "brightCyan"))
                    .bright(7, getColor(node, "brightWhite"))
                    .build();
        } catch (Exception e) {
            return null; // Skip unparseable formats
        }
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
