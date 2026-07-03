package dk.xam.themes.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dk.xam.themes.ColorUtil;
import dk.xam.themes.TerminalColorScheme;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports themes from Gogh (https://github.com/Gogh-Co/Gogh).
 * <p>
 * Gogh stores themes as YAML files in the {@code themes/} directory.
 * Each file has keys like color_01..color_16, background, foreground, cursor.
 */
public class GoghSource implements ThemeSource {
    private static final String API_URL =
            "https://api.github.com/repos/Gogh-Co/Gogh/contents/themes";

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

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

        var files = jsonMapper.readTree(resp.body());
        var themes = new ArrayList<TerminalColorScheme>();
        int total = files.size(), count = 0;

        for (var file : files) {
            String fileName = file.get("name").asText();
            if (!fileName.endsWith(".yml") && !fileName.endsWith(".yaml") && !fileName.endsWith(".json")) continue;
            count++;
            if (count % 50 == 0) System.out.printf("    %d/%d themes...%n", count, total);

            try {
                var themeReq = HttpRequest.newBuilder(safeUri(file.get("download_url").asText())).GET().build();
                var themeResp = client.send(themeReq, HttpResponse.BodyHandlers.ofString());
                if (themeResp.statusCode() != 200) continue;

                boolean isYaml = fileName.endsWith(".yml") || fileName.endsWith(".yaml");
                var mapper = isYaml ? yamlMapper : jsonMapper;
                var scheme = parseGogh(mapper.readTree(themeResp.body()));
                if (scheme != null) themes.add(scheme);
            } catch (Exception e) {
                System.err.println("    WARN: Failed to parse " + fileName + ": " + e.getMessage());
            }
        }
        System.out.printf("  Imported %d themes from Gogh%n", themes.size());
        return themes;
    }

    private TerminalColorScheme parseGogh(JsonNode node) {
        String name = getStr(node, "name");
        if (name == null || name.isBlank()) return null;

        try {
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
        } catch (Exception e) {
            return null;
        }
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

    /** Build a URI that handles spaces and non-ASCII in the path. */
    private static URI safeUri(String raw) {
        try {
            var u = URI.create(raw.replace(" ", "%20"));
            return new URI(u.getScheme(), u.getAuthority(), u.getPath(), u.getQuery(), u.getFragment());
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad URI: " + raw, e);
        }
    }

    private String getColorOpt(JsonNode node, String key) {
        var val = node.get(key);
        if (val == null || val.isNull()) return null;
        String text = val.asText().trim();
        return ColorUtil.isValidColor(text) ? text : null;
    }
}
