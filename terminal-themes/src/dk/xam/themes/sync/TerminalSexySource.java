package dk.xam.themes.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.xam.themes.ColorUtil;
import dk.xam.themes.TerminalColorScheme;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports themes from terminal.sexy (https://github.com/stayradiated/terminal.sexy).
 * <p>
 * Scheme data lives in {@code dist/schemes/}. An {@code index.json} lists all
 * available scheme paths (e.g. "base16/3024.dark", "collection/monokai").
 * Each scheme is a JSON file with a {@code color} array of 16 hex strings
 * plus optional {@code foreground} and {@code background}.
 */
public class TerminalSexySource implements ThemeSource {
    private static final String INDEX_URL =
            "https://raw.githubusercontent.com/stayradiated/terminal.sexy/master/dist/schemes/index.json";
    private static final String SCHEME_BASE =
            "https://raw.githubusercontent.com/stayradiated/terminal.sexy/master/dist/schemes/";

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() { return "terminal.sexy"; }

    @Override
    public String url() { return "https://terminal.sexy/"; }

    @Override
    public List<TerminalColorScheme> fetch() throws Exception {
        System.out.println("  Fetching index from terminal.sexy...");
        var req = HttpRequest.newBuilder(URI.create(INDEX_URL)).GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch index: " + resp.statusCode());
        }

        List<String> schemePaths = mapper.readValue(resp.body(), new TypeReference<>() {});
        var themes = new ArrayList<TerminalColorScheme>();
        int total = schemePaths.size(), count = 0;

        for (String path : schemePaths) {
            count++;
            if (count % 25 == 0) System.out.printf("    %d/%d themes...%n", count, total);

            try {
                var themeReq = HttpRequest.newBuilder(safeUri(SCHEME_BASE + path + ".json")).GET().build();
                var themeResp = client.send(themeReq, HttpResponse.BodyHandlers.ofString());
                if (themeResp.statusCode() != 200) continue;

                var scheme = parseScheme(path, themeResp.body());
                if (scheme != null) themes.add(scheme);
            } catch (Exception e) {
                System.err.println("    WARN: Failed to parse " + path + ": " + e.getMessage());
            }
        }
        System.out.printf("  Imported %d themes from terminal.sexy%n", themes.size());
        return themes;
    }

    private TerminalColorScheme parseScheme(String path, String json) throws Exception {
        var node = mapper.readTree(json);

        JsonNode colors = node.get("color");
        if (colors == null || !colors.isArray() || colors.size() < 16) return null;

        // Use the "name" field if present, otherwise derive from path
        String name = node.has("name") && !node.get("name").asText().isBlank()
                ? node.get("name").asText()
                : prettifyPath(path);

        String fg = getColorOpt(node, "foreground");
        String bg = getColorOpt(node, "background");
        if (fg == null) fg = colors.get(7).asText();  // white
        if (bg == null) bg = colors.get(0).asText();   // black

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

    /** Turn "collection/monokai" or "xcolors.net/Baskerville - Count Von Count" into a readable name. */
    private String prettifyPath(String path) {
        // Take the last segment after /
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        // Replace dots and dashes with spaces for readability
        return base.replace('.', ' ').replace('-', ' ').trim();
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
