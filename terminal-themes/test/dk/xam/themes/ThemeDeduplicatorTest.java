///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2
//SOURCES ../../../../src/dk/xam/themes/ColorUtil.java
//SOURCES ../../../../src/dk/xam/themes/TerminalColorScheme.java
//SOURCES ../../../../src/dk/xam/themes/ThemeRegistry.java
//SOURCES ../../../../src/dk/xam/themes/sync/ThemeDeduplicator.java

package dk.xam.themes;

import dk.xam.themes.sync.ThemeDeduplicator;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.util.List;

class ThemeDeduplicatorTest {

    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(ThemeDeduplicatorTest.class))
                .build();
        var launcher = LauncherFactory.create();
        launcher.execute(request, listener);
        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.err));
        if (summary.getTestsFailedCount() > 0) System.exit(1);
    }

    private static final List<String> PALETTE_A = List.of(
            "#000000", "#CC0000", "#00CC00", "#CCCC00",
            "#0000CC", "#CC00CC", "#00CCCC", "#CCCCCC",
            "#555555", "#FF0000", "#00FF00", "#FFFF00",
            "#5555FF", "#FF00FF", "#00FFFF", "#FFFFFF");

    private static final List<String> PALETTE_B = List.of(
            "#111111", "#DD0000", "#00DD00", "#DDDD00",
            "#0000DD", "#DD00DD", "#00DDDD", "#DDDDDD",
            "#666666", "#EE0000", "#00EE00", "#EEEE00",
            "#6666FF", "#EE00EE", "#00EEEE", "#EEEEEE");

    @Test
    void noDuplicates_passThrough() {
        var themes = List.of(
                theme("Alpha", "SourceA", "#FFFFFF", "#000000", PALETTE_A),
                theme("Beta", "SourceB", "#FFFFFF", "#000000", PALETTE_B));
        var result = ThemeDeduplicator.deduplicate(themes);
        assertEquals(2, result.size());
        assertEquals("Alpha", result.get(0).name());
        assertEquals("Beta", result.get(1).name());
    }

    @Test
    void identicalColors_mergedIntoOne() {
        var t1 = theme("Dracula", "iTerm2-Color-Schemes", "#F8F8F2", "#282A36", PALETTE_A);
        var t2 = theme("Dracula", "Gogh", "#F8F8F2", "#282A36", PALETTE_A);
        var result = ThemeDeduplicator.deduplicate(List.of(t1, t2));

        assertEquals(1, result.size(), "Identical themes should merge into one");
        assertEquals("Dracula", result.getFirst().name());
        // Source should mention both
        assertTrue(result.getFirst().sourceName().contains("iTerm2-Color-Schemes"));
        assertTrue(result.getFirst().sourceName().contains("Gogh"));
    }

    @Test
    void differentColors_disambiguatedWithSuffix() {
        var t1 = theme("GitHub Dark", "iTerm2-Color-Schemes", "#FFFFFF", "#000000", PALETTE_A);
        var t2 = theme("GitHub Dark", "Gogh", "#EEEEEE", "#111111", PALETTE_B);
        var result = ThemeDeduplicator.deduplicate(List.of(t1, t2));

        assertEquals(2, result.size(), "Different themes should stay separate");
        // Both should have source suffix
        assertTrue(result.stream().anyMatch(s -> s.name().equals("GitHub Dark (iTerm2-Color-Schemes)")));
        assertTrue(result.stream().anyMatch(s -> s.name().equals("GitHub Dark (Gogh)")));
        // Bare name should be an alias on both
        assertTrue(result.stream().allMatch(s -> s.aliases().contains("GitHub Dark")));
    }

    @Test
    void threeWay_twoIdenticalOneDifferent() {
        var t1 = theme("Nord", "SourceA", "#D8DEE9", "#2E3440", PALETTE_A);
        var t2 = theme("Nord", "SourceB", "#D8DEE9", "#2E3440", PALETTE_A); // same colors as t1
        var t3 = theme("Nord", "SourceC", "#CCCCCC", "#333333", PALETTE_B); // different
        var result = ThemeDeduplicator.deduplicate(List.of(t1, t2, t3));

        assertEquals(2, result.size(), "Two identical + one different = 2 results");
        // The merged identical one should have source suffix
        var names = result.stream().map(TerminalColorScheme::name).toList();
        assertTrue(names.stream().anyMatch(n -> n.contains("SourceA")));
        assertTrue(names.stream().anyMatch(n -> n.contains("SourceC")));
    }

    @Test
    void identicalColors_preservesNonNullOptionals() {
        // t1 has cursor=null, t2 has cursor set
        var t1 = TerminalColorScheme.builder("Test")
                .sourceName("A").foreground("#FFFFFF").background("#000000")
                .palette(PALETTE_A).build();
        var t2 = TerminalColorScheme.builder("Test")
                .sourceName("B").foreground("#FFFFFF").background("#000000")
                .cursor("#AABBCC").selectionBackground("#112233")
                .palette(PALETTE_A).build();

        var result = ThemeDeduplicator.deduplicate(List.of(t1, t2));
        assertEquals(1, result.size());
        // Should pick up cursor from t2
        assertEquals("#AABBCC", result.getFirst().cursor());
        assertEquals("#112233", result.getFirst().selectionBackground());
    }

    @Test
    void resultIsSorted() {
        var themes = List.of(
                theme("Zebra", "S", "#FFF", "#000", PALETTE_A),
                theme("Alpha", "S", "#FFF", "#000", PALETTE_B),
                theme("Monokai", "S", "#FFF", "#000", PALETTE_A));
        var result = ThemeDeduplicator.deduplicate(themes);
        assertEquals("Alpha", result.get(0).name());
        assertEquals("Monokai", result.get(1).name());
        assertEquals("Zebra", result.get(2).name());
    }

    @Test
    void singleTheme_unchanged() {
        var t = theme("Solo", "Source", "#FFF", "#000", PALETTE_A);
        var result = ThemeDeduplicator.deduplicate(List.of(t));
        assertEquals(1, result.size());
        assertEquals("Solo", result.getFirst().name());
    }

    @Test
    void emptyInput() {
        var result = ThemeDeduplicator.deduplicate(List.of());
        assertTrue(result.isEmpty());
    }

    private static TerminalColorScheme theme(String name, String source,
                                              String fg, String bg, List<String> palette) {
        return TerminalColorScheme.builder(name)
                .sourceName(source)
                .foreground(fg)
                .background(bg)
                .palette(palette)
                .build();
    }
}
