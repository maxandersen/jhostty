import io.smallrye.config.PropertiesConfigSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Turns a <a href="https://pkl-lang.org">Apple Pkl</a> config file into a SmallRye
 * {@link PropertiesConfigSource}.
 *
 * <p>This class never imports any Pkl Java/JVM library, so jhostty has no Pkl dependency at
 * build or run time. Instead, when a {@code .pkl} file is present, it shells out to the
 * {@code pkl} CLI ({@code pkl eval -f properties <file>}) and parses the resulting Java
 * properties output. If the {@code pkl} executable isn't installed, or evaluation fails, {@link
 * #load} returns {@code null} so the caller can fall back to whatever other config sources it
 * has.
 */
final class PklConfigSource {

    private PklConfigSource() {
    }

    static PropertiesConfigSource load(Path pklFile, Path pklExecutable, int ordinal) {
        if (pklExecutable == null) {
            System.err.println("[jhostty] found " + pklFile
                    + " but no 'pkl' executable on PATH; install it from https://pkl-lang.org to use it");
            return null;
        }
        try {
            var process = new ProcessBuilder(pklExecutable.toString(), "eval", "-f", "properties", pklFile.toString())
                    .start();
            process.getOutputStream().close();
            byte[] stdout;
            byte[] stderr;
            try (var out = process.getInputStream(); var err = process.getErrorStream()) {
                stdout = out.readAllBytes();
                stderr = err.readAllBytes();
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                System.err.println("[jhostty] pkl eval timed out for " + pklFile);
                return null;
            }
            if (process.exitValue() != 0) {
                System.err.println("[jhostty] pkl eval failed for " + pklFile + ": "
                        + new String(stderr, StandardCharsets.UTF_8).strip());
                return null;
            }
            var properties = new Properties();
            try (var in = new ByteArrayInputStream(stdout)) {
                properties.load(in);
            }
            return new PropertiesConfigSource(properties, "PklConfigSource[source=" + pklFile + "]", ordinal);
        } catch (IOException e) {
            System.err.println("[jhostty] failed to run pkl for " + pklFile + ": " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[jhostty] interrupted while running pkl for " + pklFile);
            return null;
        }
    }
}
