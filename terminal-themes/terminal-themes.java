///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2
//SOURCES src/dk/xam/themes/*.java

import dk.xam.themes.ThemeRegistry;
import dk.xam.themes.TerminalColorScheme;

/**
 * Demo / CLI for the terminal themes API.
 * <p>
 * Usage:
 *   jbang terminal-themes.java                     # list all themes
 *   jbang terminal-themes.java list                # list all theme names
 *   jbang terminal-themes.java get "Dracula"       # show a theme
 *   jbang terminal-themes.java search "dark"       # search themes
 *   jbang terminal-themes.java info "Dracula"      # show full theme info
 */
class terminalthemes {

    public static void main(String[] args) {
        var registry = ThemeRegistry.create();
        registry.loadBundled();

        if (args.length == 0 || "list".equalsIgnoreCase(args[0])) {
            listThemes(registry);
        } else if ("get".equalsIgnoreCase(args[0]) && args.length > 1) {
            getTheme(registry, args[1]);
        } else if ("search".equalsIgnoreCase(args[0]) && args.length > 1) {
            searchThemes(registry, args[1]);
        } else if ("info".equalsIgnoreCase(args[0]) && args.length > 1) {
            infoTheme(registry, args[1]);
        } else if ("count".equalsIgnoreCase(args[0])) {
            System.out.println(registry.size() + " themes loaded");
        } else {
            System.out.println("""
                Usage:
                  jbang terminal-themes.java list              List all theme names
                  jbang terminal-themes.java get "Dracula"      Show a theme's colors
                  jbang terminal-themes.java search "dark"      Search themes
                  jbang terminal-themes.java info "Dracula"     Show full theme info
                  jbang terminal-themes.java count              Count loaded themes
                """);
        }
    }

    private static void listThemes(ThemeRegistry registry) {
        var names = registry.allNames();
        System.out.printf("%d themes available:%n%n", names.size());
        for (var name : names) {
            System.out.println("  " + name);
        }
    }

    private static void getTheme(ThemeRegistry registry, String name) {
        registry.find(name).ifPresentOrElse(
                scheme -> {
                    System.out.printf("Theme: %s%n", scheme.name());
                    System.out.printf("  FG: %s  BG: %s%n", scheme.foreground(), scheme.background());
                    System.out.printf("  Palette:%n");
                    var palette = scheme.palette();
                    System.out.printf("    Normal: ");
                    for (int i = 0; i < 8; i++) System.out.printf("%s ", palette.get(i));
                    System.out.println();
                    System.out.printf("    Bright: ");
                    for (int i = 8; i < 16; i++) System.out.printf("%s ", palette.get(i));
                    System.out.println();
                    printColorPreview(scheme);
                },
                () -> System.out.println("Theme not found: " + name)
        );
    }

    private static void searchThemes(ThemeRegistry registry, String query) {
        var results = registry.searchSchemes(query);
        System.out.printf("Found %d theme(s) matching \"%s\":%n%n", results.size(), query);
        for (var scheme : results) {
            System.out.printf("  %-30s [%s]%n", scheme.name(),
                    scheme.sourceName() != null ? scheme.sourceName() : "builtin");
        }
    }

    private static void infoTheme(ThemeRegistry registry, String name) {
        registry.find(name).ifPresentOrElse(
                scheme -> {
                    System.out.printf("Name:       %s%n", scheme.name());
                    if (!scheme.aliases().isEmpty())
                        System.out.printf("Aliases:    %s%n", String.join(", ", scheme.aliases()));
                    if (scheme.sourceName() != null)
                        System.out.printf("Source:     %s%n", scheme.sourceName());
                    if (scheme.sourceUrl() != null)
                        System.out.printf("URL:        %s%n", scheme.sourceUrl());
                    System.out.printf("Foreground: %s%n", scheme.foreground());
                    System.out.printf("Background: %s%n", scheme.background());
                    if (scheme.cursor() != null)
                        System.out.printf("Cursor:     %s%n", scheme.cursor());
                    if (scheme.cursorText() != null)
                        System.out.printf("CursorText: %s%n", scheme.cursorText());
                    if (scheme.selectionBackground() != null)
                        System.out.printf("SelBG:      %s%n", scheme.selectionBackground());
                    if (scheme.selectionForeground() != null)
                        System.out.printf("SelFG:      %s%n", scheme.selectionForeground());
                    System.out.println("Palette:");
                    String[] labels = {"Black", "Red", "Green", "Yellow", "Blue", "Magenta", "Cyan", "White"};
                    var palette = scheme.palette();
                    for (int i = 0; i < 8; i++) {
                        System.out.printf("  %-8s  normal=%s  bright=%s%n", labels[i], palette.get(i), palette.get(i + 8));
                    }
                    System.out.println();
                    printColorPreview(scheme);
                },
                () -> System.out.println("Theme not found: " + name)
        );
    }

    /** Print ANSI escape color preview blocks. */
    private static void printColorPreview(TerminalColorScheme scheme) {
        var palette = scheme.palette();
        System.out.println("  Preview (ANSI blocks):");
        System.out.print("    Normal: ");
        for (int i = 0; i < 8; i++) {
            int[] rgb = hexToRgb(palette.get(i));
            System.out.printf("\033[48;2;%d;%d;%dm   \033[0m", rgb[0], rgb[1], rgb[2]);
        }
        System.out.println();
        System.out.print("    Bright: ");
        for (int i = 8; i < 16; i++) {
            int[] rgb = hexToRgb(palette.get(i));
            System.out.printf("\033[48;2;%d;%d;%dm   \033[0m", rgb[0], rgb[1], rgb[2]);
        }
        System.out.println();
    }

    private static int[] hexToRgb(String hex) {
        hex = hex.replace("#", "");
        return new int[]{
                Integer.parseInt(hex.substring(0, 2), 16),
                Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16)
        };
    }
}
