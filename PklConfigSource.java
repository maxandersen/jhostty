import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;

import io.smallrye.config.PropertiesConfigSource;

/**
 * A SmallRye Config {@code ConfigSource} for Apple's Pkl (https://pkl-lang.org)
 * configuration files.
 *
 * This intentionally does NOT depend on the Pkl Java library at runtime.
 * Instead, when a {@code .pkl} file is present it shells out to the {@code pkl}
 * CLI to render the file into a format SmallRye Config already understands and
 * parses that output in memory. As of the current Pkl CLI, {@code pkl eval}
 * supports rendering to json/jsonnet/pcf/plist/properties/textproto/xml/yaml
 * (no toml renderer exists upstream yet), so {@code properties} is used.
 *
 * Requires the {@code pkl} CLI (https://pkl-lang.org/main/current/pkl-cli) to
 * be on {@code PATH}, or pointed at via the {@code PKL_EXE} environment
 * variable. If the CLI can't be found or evaluation fails, callers get an
 * {@link IOException} describing why so they can decide how to degrade.
 */
public class PklConfigSource extends PropertiesConfigSource {

    public PklConfigSource(String name, Path pklFile, int ordinal) throws IOException {
        super(toProperties(pklFile), name, ordinal);
    }

    private static Properties toProperties(Path pklFile) throws IOException {
        var properties = new Properties();
        try (var reader = new StringReader(evalToProperties(pklFile))) {
            properties.load(reader);
        }
        return properties;
    }

    private static String evalToProperties(Path pklFile) throws IOException {
        var pklExe = System.getenv().getOrDefault("PKL_EXE", "pkl");
        var pb = new ProcessBuilder(pklExe, "eval", "-f", "properties", pklFile.toAbsolutePath().toString());
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("could not run '" + pklExe + "' -- install the Pkl CLI from " +
                    "https://pkl-lang.org/main/current/pkl-cli/index.html#installation or set PKL_EXE", e);
        }
        String out;
        String err;
        try (var stdout = process.getInputStream(); var stderr = process.getErrorStream()) {
            out = new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
            err = new String(stderr.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running pkl eval on " + pklFile, e);
        }
        if (exit != 0) {
            throw new IOException("pkl eval failed (exit " + exit + ") for " + pklFile + ": " + err.strip());
        }
        return out;
    }
}
