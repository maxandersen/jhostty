package dk.xam.themes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry of terminal color schemes.
 * <p>
 * Loads bundled schemes from classpath or a file path on first access.
 * Custom schemes can be registered at runtime or loaded from user-provided JSON files.
 * <p>
 * Lookup is case-insensitive and tolerant of hyphens/underscores/whitespace differences.
 * Aliases are also searchable.
 */
public final class ThemeRegistry {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<TerminalColorScheme> schemes = new ArrayList<>();
    private final Map<String, TerminalColorScheme> byName = new ConcurrentHashMap<>();

    private static volatile ThemeRegistry defaultInstance;

    private ThemeRegistry() {}

    /** Returns the default shared registry, lazily loading bundled themes. */
    public static ThemeRegistry getDefault() {
        if (defaultInstance == null) {
            synchronized (ThemeRegistry.class) {
                if (defaultInstance == null) {
                    var reg = new ThemeRegistry();
                    reg.loadBundled();
                    defaultInstance = reg;
                }
            }
        }
        return defaultInstance;
    }

    /** Creates a new empty registry. */
    public static ThemeRegistry create() {
        return new ThemeRegistry();
    }

    // ---- Static convenience methods using the default registry ----

    /** Look up a scheme by name or alias from the default registry. */
    public static Optional<TerminalColorScheme> get(String nameOrAlias) {
        return getDefault().find(nameOrAlias);
    }

    /** List all schemes from the default registry. */
    public static List<TerminalColorScheme> list() {
        return getDefault().all();
    }

    /** List all scheme names from the default registry. */
    public static List<String> names() {
        return getDefault().allNames();
    }

    /** Search schemes by query from the default registry. */
    public static List<TerminalColorScheme> search(String query) {
        return getDefault().searchSchemes(query);
    }

    // ---- Instance methods ----

    /** Find a scheme by exact name or alias (case-insensitive, punctuation-tolerant). */
    public Optional<TerminalColorScheme> find(String nameOrAlias) {
        if (nameOrAlias == null) return Optional.empty();
        return Optional.ofNullable(byName.get(ColorUtil.normalizeName(nameOrAlias)));
    }

    /** Get all registered schemes in insertion order. */
    public List<TerminalColorScheme> all() {
        return Collections.unmodifiableList(schemes);
    }

    /** Get all canonical scheme names in sorted order. */
    public List<String> allNames() {
        return schemes.stream()
                .map(TerminalColorScheme::name)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    /** Search schemes whose name or aliases contain the query (case-insensitive). */
    public List<TerminalColorScheme> searchSchemes(String query) {
        if (query == null || query.isBlank()) return all();
        String normalized = ColorUtil.normalizeName(query);
        return schemes.stream()
                .filter(s -> {
                    if (ColorUtil.normalizeName(s.name()).contains(normalized)) return true;
                    return s.aliases().stream()
                            .anyMatch(a -> ColorUtil.normalizeName(a).contains(normalized));
                })
                .collect(Collectors.toList());
    }

    /** Register a scheme. Replaces any existing scheme with the same normalized name. */
    public void register(TerminalColorScheme scheme) {
        Objects.requireNonNull(scheme);
        String normalizedName = ColorUtil.normalizeName(scheme.name());
        schemes.removeIf(s -> ColorUtil.normalizeName(s.name()).equals(normalizedName));
        schemes.add(scheme);
        byName.put(normalizedName, scheme);
        for (String alias : scheme.aliases()) {
            byName.put(ColorUtil.normalizeName(alias), scheme);
        }
    }

    /** Register multiple schemes. */
    public void registerAll(Collection<TerminalColorScheme> themes) {
        themes.forEach(this::register);
    }

    /** Load schemes from a JSON input stream (array of TerminalColorScheme). */
    public void loadFromJson(InputStream in) throws IOException {
        List<TerminalColorScheme> loaded = MAPPER.readValue(in, new TypeReference<>() {});
        registerAll(loaded);
    }

    /** Load schemes from a JSON file. */
    public void loadFromFile(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            loadFromJson(in);
        }
    }

    /**
     * Load bundled themes. Tries classpath resource first, then falls back to
     * {@code themes/builtin-themes.json} relative to the working directory.
     */
    public void loadBundled() {
        // Try classpath resource first
        var in = ThemeRegistry.class.getClassLoader().getResourceAsStream("themes/builtin-themes.json");
        if (in != null) {
            try (in) {
                loadFromJson(in);
                return;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load bundled themes from classpath", e);
            }
        }
        // Fall back to file system (JBang style — themes/ dir next to the script)
        var filePath = Path.of("themes/builtin-themes.json");
        if (Files.exists(filePath)) {
            try {
                loadFromFile(filePath);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load bundled themes from " + filePath, e);
            }
        }
    }

    /** Number of registered schemes. */
    public int size() {
        return schemes.size();
    }

    /** Clear all registered schemes. */
    public void clear() {
        schemes.clear();
        byName.clear();
    }

    /** Reset the default singleton (forces re-load on next access). Mainly for testing. */
    public static synchronized void resetDefault() {
        defaultInstance = null;
    }
}
