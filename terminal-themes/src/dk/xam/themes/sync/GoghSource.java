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
 * Imports themes from Gogh (https://github.com/Gogh-Co/Gogh).
 * <p>
 * Gogh stores themes as JSON files in the {@code themes/} directory.
 * Each file has keys like COLOR_01..COLOR_16, BACKGROUND_COLOR, FOREGROUND_COLOR, etc.
 */
public class GoghSource implements ThemeSource {
    private static final String API_URL =
            "https://api.github.com/repos/Gogh-Co/Gogh/contents/themes";
    private static final String RAW_BASE =
            "https://raw.githubusercontent.com/Gogh-Co/Gogh/master/themes/";

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() { return "Gogh"; }

    @Override
    public String url() { return "https://github.com/Gogh-Co/Gogh"; }

    @Override
    public List<TerminalColorScheme> fetch() throws Exception {
        System.out.println("  Fetching file list from Gogh...");
        var req = HttpRequest.newBuilder(URI.create(API_URL))
                .header("Accept", "application/vnd.github.v3+json")
                .GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("GitHub API returned " + resp.statusCode() + ": " + resp.body());
        }

        var files = mapper.readTree(resp.body());
        var themes = new ArrayList<TerminalColorScheme>();
        int total = files.size(), count = 0;

        for (var file : files) {
            String fileName = file.get("name").asText();
            if (!fileName.endsWith(".json")) continue;
            count++;
            if (count % 50 == 0) System.out.printf("    %d/%d themes...%n", count, total);

            try {
                String downloadUrl = file.has("download_url")
                        ? file.get("download_url").asText()
                        : RAW_BASE + fileName;
                var themeReq = HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build();
                var themeResp = client.send(themeReq, HttpResponse.BodyHandlers.ofString());
                if (themeResp.statusCode() != 200) continue;

                var scheme = parseGoghJson(themeResp.body());
                if (scheme != null) themes.add(scheme);
            } catch (Exception e) {
                System.err.println("    WARN: Failed to parse " + fileName + ": " + e.getMessage());
            }
        }
        System.out.printf("  Imported %d themes from Gogh%n", themes.size());
        return themes;
    }

    private TerminalColorScheme parseGoghJson(String json) throws Exception {
        var node = mapper.readTree(json);
        String name = getStr(node, "name");
        if (name == null || name.isBlank()) return null;

        return TerminalColorScheme.builder(name)
                .sourceName(name())
                .sourceUrl(url())
                .foreground(getColor(node, "foreground"))
                .background(getColor(node, "background"))
                .cursor(getColorOpt(node, "cursor"))
                .ansi(0, getColor(node, "color_01"))
                .ansi(1, getColor(node, "color_02"))
                .ansi(2, getColor(node, "color_03"))
                .ansi(3, getColor(node, "color_04"))
                .ansi(4, getColor(node, "color_05"))
                .ansi(5, getColor(node, "color_06"))
                .ansi(6, getColor(node, "color_07"))
                .ansi(7, getColor(node, "color_08"))
                .bright(0, getColor(node, "color_09"))
                .bright(1, getColor(node, "color_10"))
                .bright(2, getColor(node, "color_11"))
                .bright(3, getColor(node, "color_12"))
                .bright(4, getColor(node, "color_13"))
                .bright(5, getColor(node, "color_14"))
                .bright(6, getColor(node, "color_15"))
                .bright(7, getColor(node, "color_16"))
                .build();
    }

    private String getStr(JsonNode node, String key) {
        var val = node.get(key);
        return (val != null && !val.isNull()) ? val.asText() : null;
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
