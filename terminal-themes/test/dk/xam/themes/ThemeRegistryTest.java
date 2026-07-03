///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2
//SOURCES ../../../../../src/dk/xam/themes/ColorUtil.java
//SOURCES ../../../../../src/dk/xam/themes/TerminalColorScheme.java
//SOURCES ../../../../../src/dk/xam/themes/ThemeRegistry.java

package dk.xam.themes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

class ThemeRegistryTest {

    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(ThemeRegistryTest.class))
                .build();
        var launcher = LauncherFactory.create();
        launcher.execute(request, listener);
        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.err));
        if (summary.getTestsFailedCount() > 0) System.exit(1);
    }

    private ThemeRegistry registry;

    // Resolve the builtin-themes.json relative to this test's location
    private static final Path THEMES_JSON = Path.of(System.getProperty("user.dir"))
            .resolve("../themes/builtin-themes.json").normalize();

    @BeforeEach
    void setUp() {
        registry = ThemeRegistry.create();
    }

    private void loadThemes() {
        try { registry.loadFromFile(THEMES_JSON); }
        catch (Exception e) { registry.loadBundled(); } // fallback if run from project root
    }

    @Test
    void loadBundledThemes() {
        loadThemes();
        assertTrue(registry.size() >= 3, "Should have at least 3 bundled themes, got " + registry.size());
        assertNotNull(registry.find("Dracula").orElse(null));
    }

    @Test
    void caseInsensitiveLookup() {
        loadThemes();
        assertTrue(registry.find("dracula").isPresent());
        assertTrue(registry.find("DRACULA").isPresent());
        assertTrue(registry.find("Dracula").isPresent());
    }

    @Test
    void aliasLookup() {
        loadThemes();
        var scheme = registry.find("catppuccin-mocha");
        assertTrue(scheme.isPresent(), "catppuccin-mocha alias should resolve");
        assertTrue(scheme.get().name().startsWith("Catppuccin Mocha"),
                "Should resolve to a Catppuccin Mocha variant, got: " + scheme.get().name());
    }

    @Test
    void punctuationTolerantLookup() {
        loadThemes();
        assertTrue(registry.find("catppuccin mocha").isPresent());
        assertTrue(registry.find("catppuccin_mocha").isPresent());
        assertTrue(registry.find("solarized dark").isPresent());
        assertTrue(registry.find("solarized-dark").isPresent());
    }

    @Test
    void searchSchemes() {
        loadThemes();
        var results = registry.searchSchemes("dark");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(s -> s.name().equals("Solarized Dark")));
    }

    @Test
    void registerCustomTheme() {
        var custom = TerminalColorScheme.builder("My Custom Theme")
                .aliases(List.of("custom"))
                .foreground("#FFFFFF")
                .background("#000000")
                .palette(List.of(
                        "#000000", "#CC0000", "#00CC00", "#CCCC00",
                        "#0000CC", "#CC00CC", "#00CCCC", "#CCCCCC",
                        "#555555", "#FF0000", "#00FF00", "#FFFF00",
                        "#5555FF", "#FF00FF", "#00FFFF", "#FFFFFF"))
                .build();
        registry.register(custom);
        assertTrue(registry.find("My Custom Theme").isPresent());
        assertTrue(registry.find("custom").isPresent());
    }

    @Test
    void allNamesSorted() {
        loadThemes();
        var names = registry.allNames();
        assertFalse(names.isEmpty());
        for (int i = 1; i < names.size(); i++) {
            assertTrue(String.CASE_INSENSITIVE_ORDER.compare(names.get(i - 1), names.get(i)) <= 0,
                    names.get(i - 1) + " should come before " + names.get(i));
        }
    }

    @Test
    void paletteReturns16Colors() {
        loadThemes();
        var scheme = registry.find("Dracula").orElseThrow();
        assertEquals(16, scheme.palette().size());
        assertTrue(scheme.palette().stream().allMatch(c -> c.matches("#[0-9A-F]{6}")));
    }

    @Test
    void loadFromJsonStream() throws Exception {
        String json = """
                [{
                    "name": "Test Theme",
                    "aliases": [],
                    "foreground": "#FFFFFF",
                    "background": "#000000",
                    "ansi0": "#000000", "ansi1": "#CC0000", "ansi2": "#00CC00", "ansi3": "#CCCC00",
                    "ansi4": "#0000CC", "ansi5": "#CC00CC", "ansi6": "#00CCCC", "ansi7": "#CCCCCC",
                    "bright0": "#555555", "bright1": "#FF0000", "bright2": "#00FF00", "bright3": "#FFFF00",
                    "bright4": "#5555FF", "bright5": "#FF00FF", "bright6": "#00FFFF", "bright7": "#FFFFFF"
                }]
                """;
        registry.loadFromJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, registry.size());
        assertTrue(registry.find("Test Theme").isPresent());
    }

    @Test
    void hexNormalizationOnLoad() {
        var scheme = TerminalColorScheme.builder("Test")
                .foreground("ffffff")
                .background("000000")
                .palette(List.of(
                        "000000", "cc0000", "00cc00", "cccc00",
                        "0000cc", "cc00cc", "00cccc", "cccccc",
                        "555555", "ff0000", "00ff00", "ffff00",
                        "5555ff", "ff00ff", "00ffff", "ffffff"))
                .build();
        assertEquals("#FFFFFF", scheme.foreground());
        assertEquals("#000000", scheme.background());
        assertEquals("#CC0000", scheme.ansi1());
    }

    @Test
    void customThemeOverridesBuiltin() {
        loadThemes();
        assertEquals("#F8F8F2", registry.find("Dracula").orElseThrow().foreground());

        var custom = TerminalColorScheme.builder("Dracula")
                .foreground("#AAAAAA")
                .background("#111111")
                .palette(List.of(
                        "#000000", "#CC0000", "#00CC00", "#CCCC00",
                        "#0000CC", "#CC00CC", "#00CCCC", "#CCCCCC",
                        "#555555", "#FF0000", "#00FF00", "#FFFF00",
                        "#5555FF", "#FF00FF", "#00FFFF", "#FFFFFF"))
                .build();
        registry.register(custom);
        assertEquals("#AAAAAA", registry.find("Dracula").orElseThrow().foreground());
    }

    @Test
    void effectiveColorsFallback() {
        var scheme = TerminalColorScheme.builder("Test")
                .foreground("#FFFFFF")
                .background("#000000")
                .palette(List.of(
                        "#000000", "#CC0000", "#00CC00", "#CCCC00",
                        "#0000CC", "#CC00CC", "#00CCCC", "#CCCCCC",
                        "#555555", "#FF0000", "#00FF00", "#FFFF00",
                        "#5555FF", "#FF00FF", "#00FFFF", "#FFFFFF"))
                .build();
        assertEquals("#FFFFFF", scheme.effectiveCursor());
        assertEquals("#000000", scheme.effectiveCursorText());
        assertEquals("#0000CC", scheme.effectiveSelectionBackground()); // falls back to ansi4
    }

    @Test
    void effectiveColorsExplicit() {
        var scheme = TerminalColorScheme.builder("Test")
                .foreground("#FFFFFF")
                .background("#000000")
                .cursor("#AABBCC")
                .cursorText("#112233")
                .selectionBackground("#445566")
                .selectionForeground("#778899")
                .palette(List.of(
                        "#000000", "#CC0000", "#00CC00", "#CCCC00",
                        "#0000CC", "#CC00CC", "#00CCCC", "#CCCCCC",
                        "#555555", "#FF0000", "#00FF00", "#FFFF00",
                        "#5555FF", "#FF00FF", "#00FFFF", "#FFFFFF"))
                .build();
        assertEquals("#AABBCC", scheme.effectiveCursor());
        assertEquals("#112233", scheme.effectiveCursorText());
        assertEquals("#445566", scheme.effectiveSelectionBackground());
        assertEquals("#778899", scheme.effectiveSelectionForeground());
    }

    @Test
    void emptyRegistryOperations() {
        assertEquals(0, registry.size());
        assertTrue(registry.all().isEmpty());
        assertTrue(registry.allNames().isEmpty());
        assertTrue(registry.find("anything").isEmpty());
        assertTrue(registry.searchSchemes("anything").isEmpty());
    }

    @Test
    void clearRegistry() {
        loadThemes();
        assertTrue(registry.size() > 0);
        registry.clear();
        assertEquals(0, registry.size());
        assertTrue(registry.find("Dracula").isEmpty());
    }
}
