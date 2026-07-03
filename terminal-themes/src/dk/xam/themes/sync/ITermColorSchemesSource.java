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
 * Imports themes from iTerm2-Color-Schemes via their JSON export format.
 * <p>
 * Uses the windowsterminal/ directory which has clean JSON files,
 * or falls back to the GitHub API to list and fetch JSON scheme files.
 */
public class ITermColorSchemesSource implements ThemeSource {
    // GitHub API to list JSON files in the windowsterminal directory
    private static final String API_URL =
            "https://api.github.com/repos/mbadolato/iTerm2-Color-Schemes/contents/windowsterminal";
    private static final String RAW_BASE =
            "https://raw.githubusercontent.com/mbadolato/iTerm2-Color-Schemes/master/windowsterminal/";

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() { return "iTerm2-Color-Schemes"; }

    @Override
    public String url() { return "https://github.com/mbadolato/iTerm2-Color-Schemes"; }

    @Override
    public List<TerminalColorScheme> fetch() throws Exception {
        System.out.println("  Fetching file list from iTerm2-Color-Schemes...");
        var req = HttpRequest.newBuilder(URI.create(API_URL))
                .header("Accept", "application/vnd.github.v3+json")
                .GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("GitHub API returned " + resp.statusCode() + ": " + resp.body());
        }

        var files = mapper.readTree(resp.body());
        var themes = new ArrayList<TerminalColorScheme>();
        int total = files.size();
        int count = 0;

        for (var file : files) {
            String fileName = file.get("name").asText();
            if (!fileName.endsWith(".json")) continue;
            String themeName = fileName.replace(".json", "");
            count++;
            if (count % 50 == 0) System.out.printf("    %d/%d themes...%n", count, total);

            try {
                String downloadUrl = file.has("download_url")
                        ? file.get("download_url").asText()
                        : RAW_BASE + fileName;
                var themeReq = HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build();
                var themeResp = client.send(themeReq, HttpResponse.BodyHandlers.ofString());
                if (themeResp.statusCode() != 200) continue;

                var scheme = parseWindowsTerminalJson(themeName, themeResp.body());
                if (scheme != null) themes.add(scheme);
            } catch (Exception e) {
                System.err.println("    WARN: Failed to parse " + fileName + ": " + e.getMessage());
            }
        }
        System.out.printf("  Imported %d themes from iTerm2-Color-Schemes%n", themes.size());
        return themes;
    }

    /**
     * Parse a Windows Terminal JSON color scheme.
     * Format has keys like "background", "foreground", "black", "red", etc.
     */
    private TerminalColorScheme parseWindowsTerminalJson(String name, String json) throws Exception {
        var node = mapper.readTree(json);
        return TerminalColorScheme.builder(node.has("name") ? node.get("name").asText() : name)
                .sourceName(name())
                .sourceUrl(url())
                .foreground(getColor(node, "foreground"))
                .background(getColor(node, "background"))
                .cursor(getColorOpt(node, "cursorColor"))
                .selectionBackground(getColorOpt(node, "selectionBackground"))
                .ansi(0, getColor(node, "black"))
                .ansi(1, getColor(node, "red"))
                .ansi(2, getColor(node, "green"))
                .ansi(3, getColor(node, "yellow"))
                .ansi(4, getColor(node, "blue"))
                .ansi(5, getColor(node, "purple"))
                .ansi(6, getColor(node, "cyan"))
                .ansi(7, getColor(node, "white"))
                .bright(0, getColor(node, "brightBlack"))
                .bright(1, getColor(node, "brightRed"))
                .bright(2, getColor(node, "brightGreen"))
                .bright(3, getColor(node, "brightYellow"))
                .bright(4, getColor(node, "brightBlue"))
                .bright(5, getColor(node, "brightPurple"))
                .bright(6, getColor(node, "brightCyan"))
                .bright(7, getColor(node, "brightWhite"))
                .build();
    }

    private String getColor(JsonNode node, String key) {
        var val = node.get(key);
        if (val == null || val.isNull()) throw new IllegalArgumentException("Missing required color: " + key);
        return val.asText();
    }

    private String getColorOpt(JsonNode node, String key) {
        var val = node.get(key);
        if (val == null || val.isNull()) return null;
        String text = val.asText();
        return ColorUtil.isValidColor(text) ? text : null;
    }
}
