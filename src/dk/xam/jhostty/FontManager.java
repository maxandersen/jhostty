package dk.xam.jhostty;

import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

public final class FontManager {
    private FontManager() {}

    private static String loadedFamily;
    private static String regularFontName;

    public static String defaultFontFamily() {
        var families = Font.getFamilies();
        for (var family : List.of(
                "JetBrainsMono Nerd Font Mono", "JetBrainsMono Nerd Font",
                "JetBrains Mono", "SF Mono", "Menlo", "Monaco", "Consolas")) {
            if (families.contains(family)) return family;
        }
        return "Monospaced";
    }

    public static Font resolveFont(String family, double size) {
        // ghosttyfx uses font.getFamily() to derive bold/italic via Font.font(family, BOLD, size).
        // For system fonts, getFamily() can return a name (e.g. "JetBrainsMono NFM") that
        // Font.font() doesn't recognize — it only knows the name from Font.getFamilies()
        // (e.g. "JetBrainsMono Nerd Font Mono"). We construct the font using the exact
        // Regular font name so at least the base font renders correctly.
        // Bold/italic resolution is a ghosttyfx issue:
        // https://github.com/vlaaad/ghosttyfx/issues/8
        if (!family.equals(loadedFamily)) {
            var names = Font.getFontNames(family);
            regularFontName = names.stream()
                    .filter(n -> n.endsWith(" Regular") || n.equals(family))
                    .findFirst().orElse(family);
            JHostty.debug("font: using '" + regularFontName + "' (from family '" + family + "')");
            loadedFamily = family;
            debugFontResolution(family, size);
        }
        return new Font(regularFontName, size);
    }

    /** Log what JavaFX actually resolves for each style combination. */
    public static void debugFontResolution(String family, double size) {
        for (var weight : List.of(FontWeight.NORMAL, FontWeight.BOLD)) {
            for (var posture : List.of(FontPosture.REGULAR, FontPosture.ITALIC)) {
                var f = Font.font(family, weight, posture, size);
                JHostty.debug("font resolve: " + weight + "/" + posture + " → '" + f.getName() + "' family='" + f.getFamily() + "' style='" + f.getStyle() + "'");
            }
        }
    }

    /** Check if Font.font(family) resolves to a font whose getFamily() matches — i.e. bold/italic will work. */
    static boolean hasSaneResolution(String family) {
        var font = Font.font(family, 14);
        return font.getFamily().equals(family);
    }

    /** Check if a font is monospaced by comparing glyph widths. */
    static boolean isMonospaced(String family) {
        var text = new javafx.scene.text.Text();
        text.setFont(Font.font(family, 14));
        text.setText("M"); var wM = text.getLayoutBounds().getWidth();
        text.setText("i"); var wi = text.getLayoutBounds().getWidth();
        text.setText("."); var wd = text.getLayoutBounds().getWidth();
        return wM == wi && wi == wd;
    }

    /** Return family names from all downloaded Nerd Fonts. */
    static List<String> downloadedFontFamilies() {
        var result = new ArrayList<String>();
        for (var nf : NERD_FONTS) {
            result.addAll(loadedNerdFontFamilies(nf));
        }
        return result;
    }

    public static List<String> detectFonts() {
        var preferred = List.of(
                // Nerd Font variants (popular terminal fonts patched with icons)
                "JetBrainsMono Nerd Font Mono", "JetBrainsMono Nerd Font",
                "FiraCode Nerd Font", "Hack Nerd Font", "Hack Nerd Font Mono",
                "CaskaydiaCove Nerd Font", "CaskaydiaCove Nerd Font Mono",
                "CaskaydiaMono Nerd Font", "CaskaydiaMono Nerd Font Mono",
                "Iosevka Nerd Font", "Iosevka Nerd Font Mono",
                "UbuntuMono Nerd Font", "UbuntuMono Nerd Font Mono",
                "VictorMono Nerd Font", "RobotoMono Nerd Font",
                "SourceCodePro Nerd Font", "Inconsolata Nerd Font",
                "GeistMono Nerd Font", "CommitMono Nerd Font",
                "Monaspace Neon", "Monaspace Argon",
                // Standard fonts
                "JetBrains Mono", "Cascadia Code", "Cascadia Mono",
                "Fira Code", "Source Code Pro", "IBM Plex Mono",
                "Victor Mono", "Iosevka", "Hack", "Inconsolata",
                "Roboto Mono", "Ubuntu Mono", "Geist Mono",
                "SF Mono", "Menlo", "Monaco", "Consolas",
                "PT Mono", "Courier New");
        var all = new ArrayList<>(Font.getFamilies());
        // Include fonts loaded via Font.loadFont() (not in getFamilies)
        for (var f : downloadedFontFamilies()) {
            if (!all.contains(f)) all.add(f);
        }
        var preferredSet = new LinkedHashSet<>(preferred);
        var result = new ArrayList<String>();
        var added = new LinkedHashSet<String>();

        // 1. Preferred monospaced with working bold/italic
        for (var family : preferred) {
            if (all.contains(family) && isMonospaced(family) && hasSaneResolution(family) && added.add(family)) result.add(family);
        }
        // 2. Preferred monospaced (broken resolution)
        for (var family : preferred) {
            if (all.contains(family) && isMonospaced(family) && added.add(family)) result.add(family);
        }
        // 3. Other monospaced fonts
        for (var family : all) {
            if (!preferredSet.contains(family) && isMonospaced(family) && added.add(family)) result.add(family);
        }
        // 4. Remaining preferred (non-mono like Cascadia Code with ligatures)
        for (var family : preferred) {
            if (all.contains(family) && added.add(family)) result.add(family);
        }
        // 5. Everything else
        for (var family : all) {
            if (added.add(family)) result.add(family);
        }
        return result;
    }

    // --- Nerd Font download support ---

    private static final String NERD_FONTS_VERSION = "v3.4.0";
    private static final String NERD_FONTS_URL = "https://github.com/ryanoasis/nerd-fonts/releases/download/" + NERD_FONTS_VERSION + "/";

    /** Nerd Font zip name → display label for downloadable fonts. */
    static final List<NerdFont> NERD_FONTS = List.of(
        new NerdFont("0xProto", "0xProto"),
        new NerdFont("AnonymousPro", "Anonymous Pro"),
        new NerdFont("AtkinsonHyperlegibleMono", "Atkinson Hyperlegible Mono"),
        new NerdFont("CascadiaCode", "Cascadia Code"),
        new NerdFont("CascadiaMono", "Cascadia Mono"),
        new NerdFont("CommitMono", "Commit Mono"),
        new NerdFont("DejaVuSansMono", "DejaVu Sans Mono"),
        new NerdFont("DepartureMono", "Departure Mono"),
        new NerdFont("FiraCode", "Fira Code"),
        new NerdFont("FiraMono", "Fira Mono"),
        new NerdFont("GeistMono", "Geist Mono"),
        new NerdFont("Go-Mono", "Go Mono"),
        new NerdFont("Hack", "Hack"),
        new NerdFont("Hasklig", "Hasklig"),
        new NerdFont("IBMPlexMono", "IBM Plex Mono"),
        new NerdFont("Inconsolata", "Inconsolata"),
        new NerdFont("IntelOneMono", "Intel One Mono"),
        new NerdFont("Iosevka", "Iosevka"),
        new NerdFont("IosevkaTerm", "Iosevka Term"),
        new NerdFont("JetBrainsMono", "JetBrains Mono"),
        new NerdFont("Lilex", "Lilex"),
        new NerdFont("MartianMono", "Martian Mono"),
        new NerdFont("Meslo", "Meslo"),
        new NerdFont("Monaspace", "Monaspace"),
        new NerdFont("Monoid", "Monoid"),
        new NerdFont("Mononoki", "Mononoki"),
        new NerdFont("Noto", "Noto"),
        new NerdFont("Recursive", "Recursive"),
        new NerdFont("RobotoMono", "Roboto Mono"),
        new NerdFont("SourceCodePro", "Source Code Pro"),
        new NerdFont("SpaceMono", "Space Mono"),
        new NerdFont("Ubuntu", "Ubuntu"),
        new NerdFont("UbuntuMono", "Ubuntu Mono"),
        new NerdFont("UbuntuSans", "Ubuntu Sans"),
        new NerdFont("VictorMono", "Victor Mono"),
        new NerdFont("ZedMono", "Zed Mono")
    );

    record NerdFont(String zipName, String label) {}

    /** Directory where downloaded Nerd Fonts are stored. */
    static Path fontDir() {
        return JHostty.configDir.resolve("fonts");
    }

    /** Check if a Nerd Font has been downloaded. */
    static boolean isNerdFontDownloaded(NerdFont nf) {
        var dir = fontDir().resolve(nf.zipName);
        return Files.isDirectory(dir) && Files.exists(dir.resolve("loaded"));
    }

    /** Get the family names registered by a downloaded Nerd Font. */
    static List<String> loadedNerdFontFamilies(NerdFont nf) {
        var marker = fontDir().resolve(nf.zipName).resolve("loaded");
        try {
            if (Files.exists(marker)) return Files.readAllLines(marker).stream().filter(s -> !s.isBlank()).toList();
        } catch (IOException _) {}
        return List.of();
    }

    /**
     * Download a Nerd Font zip, extract Mono variants (Regular/Bold/Italic/BoldItalic),
     * load them via Font.loadFont, and write a marker with the registered family names.
     * Runs on a background thread; calls onDone on FX thread when complete.
     */
    static void downloadNerdFont(NerdFont nf, Consumer<List<String>> onDone, Consumer<String> onError) {
        Thread.startVirtualThread(() -> {
            try {
                var dir = fontDir().resolve(nf.zipName);
                Files.createDirectories(dir);
                var url = URI.create(NERD_FONTS_URL + nf.zipName + ".zip").toURL();
                JHostty.debug("font: downloading " + url);
                var families = new LinkedHashSet<String>();
                try (var zis = new ZipInputStream(url.openStream())) {
                    var entry = zis.getNextEntry();
                    while (entry != null) {
                        var name = entry.getName();
                        // Extract only NerdFontMono variants with the 4 key styles
                        if (name.contains("NerdFontMono-") &&
                            (name.endsWith("-Regular.ttf") || name.endsWith("-Bold.ttf") ||
                             name.endsWith("-Italic.ttf") || name.endsWith("-BoldItalic.ttf"))) {
                            var target = dir.resolve(name);
                            Files.copy(zis, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            JHostty.debug("font: extracted " + name);
                        }
                        zis.closeEntry();
                        entry = zis.getNextEntry();
                    }
                }
                // Load extracted fonts on FX thread
                javafx.application.Platform.runLater(() -> {
                    var loadedFamilies = loadFontsFromDir(dir);
                    // Write marker with family names
                    try { Files.writeString(dir.resolve("loaded"), String.join("\n", loadedFamilies)); }
                    catch (IOException _) {}
                    onDone.accept(loadedFamilies);
                });
            } catch (Exception e) {
                JHostty.debug("font: download failed: " + e.getMessage());
                javafx.application.Platform.runLater(() -> onError.accept(e.getMessage()));
            }
        });
    }

    /** Load all .ttf/.otf files in a directory via Font.loadFont and return registered family names. */
    static List<String> loadFontsFromDir(Path dir) {
        var families = new LinkedHashSet<String>();
        try (var files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".ttf") || p.toString().endsWith(".otf"))
                 .sorted()
                 .forEach(p -> {
                     var font = Font.loadFont(p.toUri().toString(), 1);
                     if (font != null) {
                         JHostty.debug("font: loaded " + p.getFileName() + " → '" + font.getName() + "' family='" + font.getFamily() + "'");
                         families.add(font.getFamily());
                     }
                 });
        } catch (IOException _) {}
        return new ArrayList<>(families);
    }

    /** Load all previously downloaded Nerd Fonts on startup. */
    static void loadDownloadedFonts() {
        var dir = fontDir();
        if (!Files.isDirectory(dir)) return;
        try (var dirs = Files.list(dir)) {
            dirs.filter(Files::isDirectory).forEach(d -> {
                var loaded = loadFontsFromDir(d);
                if (!loaded.isEmpty()) {
                    try { Files.writeString(d.resolve("loaded"), String.join("\n", loaded)); }
                    catch (IOException _) {}
                }
            });
        } catch (IOException _) {}
    }
}
