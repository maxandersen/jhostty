///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//SOURCES ../../../../../src/dk/xam/themes/ColorUtil.java

package dk.xam.themes;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;

class ColorUtilTest {

    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(ColorUtilTest.class))
                .build();
        var launcher = LauncherFactory.create();
        launcher.execute(request, listener);
        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.err));
        if (summary.getTestsFailedCount() > 0) System.exit(1);
    }

    // --- Hex normalization ---

    @Test void normalizeFullHexWithHash() {
        assertEquals("#FF0000", ColorUtil.normalize("#FF0000"));
    }

    @Test void normalizeFullHexWithoutHash() {
        assertEquals("#FF0000", ColorUtil.normalize("FF0000"));
    }

    @Test void normalizeLowercaseHex() {
        assertEquals("#FF0000", ColorUtil.normalize("ff0000"));
        assertEquals("#FF0000", ColorUtil.normalize("#ff0000"));
    }

    @Test void normalizeMixedCaseHex() {
        assertEquals("#AABB00", ColorUtil.normalize("aAbB00"));
    }

    @Test void normalizeShortHex() {
        assertEquals("#AABBCC", ColorUtil.normalize("#abc"));
        assertEquals("#AABBCC", ColorUtil.normalize("abc"));
        assertEquals("#FF0000", ColorUtil.normalize("#f00"));
    }

    @Test void normalizeHexWithAlpha() {
        assertEquals("#FF0000", ColorUtil.normalize("#FF0000FF"));
        assertEquals("#FF0000", ColorUtil.normalize("FF000080"));
    }

    // --- RGB function ---

    @Test void normalizeRgb() {
        assertEquals("#FF0000", ColorUtil.normalize("rgb(255, 0, 0)"));
        assertEquals("#1E1E2E", ColorUtil.normalize("rgb(30, 30, 46)"));
    }

    @Test void normalizeRgba() {
        assertEquals("#FF0000", ColorUtil.normalize("rgba(255, 0, 0, 0.5)"));
        assertEquals("#00FF00", ColorUtil.normalize("rgba(0, 255, 0, 1)"));
    }

    @Test void normalizeRgbNoSpaces() {
        assertEquals("#FF0000", ColorUtil.normalize("rgb(255,0,0)"));
    }

    // --- Null/empty ---

    @Test void normalizeNull() {
        assertNull(ColorUtil.normalize(null));
    }

    @Test void normalizeEmpty() {
        assertNull(ColorUtil.normalize(""));
        assertNull(ColorUtil.normalize("  "));
    }

    // --- Invalid ---

    @Test void invalidColor() {
        assertThrows(IllegalArgumentException.class, () -> ColorUtil.normalize("not-a-color"));
        assertThrows(IllegalArgumentException.class, () -> ColorUtil.normalize("#GGHHII"));
        assertThrows(IllegalArgumentException.class, () -> ColorUtil.normalize("rgb(300, 0, 0)"));
    }

    // --- Name normalization ---

    @Test void normalizeNameBasic() {
        assertEquals("catppuccin mocha", ColorUtil.normalizeName("Catppuccin Mocha"));
    }

    @Test void normalizeNameHyphens() {
        assertEquals("catppuccin mocha", ColorUtil.normalizeName("catppuccin-mocha"));
    }

    @Test void normalizeNameUnderscores() {
        assertEquals("catppuccin mocha", ColorUtil.normalizeName("catppuccin_mocha"));
    }

    @Test void normalizeNameWhitespace() {
        assertEquals("github dark", ColorUtil.normalizeName("  GitHub  Dark  "));
    }

    @Test void normalizeNameAllCaps() {
        assertEquals("nord", ColorUtil.normalizeName("NORD"));
    }

    @Test void normalizeNameNull() {
        assertEquals("", ColorUtil.normalizeName(null));
    }

    // --- isValidColor ---

    @Test void isValidColorTrue() {
        assertTrue(ColorUtil.isValidColor("#FF0000"));
        assertTrue(ColorUtil.isValidColor("FF0000"));
        assertTrue(ColorUtil.isValidColor("#abc"));
        assertTrue(ColorUtil.isValidColor("rgb(0,0,0)"));
    }

    @Test void isValidColorFalse() {
        assertFalse(ColorUtil.isValidColor(null));
        assertFalse(ColorUtil.isValidColor("nope"));
        assertFalse(ColorUtil.isValidColor("#GGHHII"));
    }
}
