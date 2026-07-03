import io.smallrye.config.PropertiesConfigSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Turns a <a href="https://pkl-lang.org">Apple Pkl</a> config file into a SmallRye
 * {@link PropertiesConfigSource}.
 *
 * <p>This class never imports any Pkl Java/JVM library, so jhostty has no Pkl dependency at
 * build or run time. Instead, when a {@code .pkl} file is present, it shells out to the
 * {@code pkl} CLI ({@code pkl eval -f properties <file>}) and caches the resulting Java
 * properties next to it (e.g. {@code jhostty.pkl} -> {@code jhostty.pkl.properties}). The {@code
 * pkl} CLI is only invoked when that cache is missing or older than the source {@code .pkl}
 * file — otherwise the cached properties are reused as-is, so {@code pkl} doesn't need to be
 * installed at all once a fresh cache exists. If the {@code pkl} executable isn't available and
 * there is no usable cache, {@link #load} returns {@code null} so the caller can fall back to
 * whatever other config sources it has.
 */
final class PklConfigSource {

    private PklConfigSource() {
    }

    static PropertiesConfigSource load(Path pklFile, Path pklExecutable, int ordinal) {
        var cacheFile = pklFile.resolveSibling(pklFile.getFileName() + ".properties");
        try {
            if (!isFresh(pklFile, cacheFile)) {
                if (pklExecutable != null) {
                    var generated = eval(pklFile, pklExecutable);
                    if (generated != null) {
                        writeAtomic(cacheFile, generated);
                    }
                } else if (Files.isRegularFile(cacheFile)) {
                    System.err.println("[jhostty] " + pklFile
                            + " changed but no 'pkl' executable on PATH to regenerate it; using stale " + cacheFile);
                } else {
                    System.err.println("[jhostty] found " + pklFile
                            + " but no 'pkl' executable on PATH; install it from https://pkl-lang.org to use it");
                }
            }
            if (!Files.isRegularFile(cacheFile)) {
                return null;
            }
            var properties = new Properties();
            try (var in = Files.newInputStream(cacheFile)) {
                properties.load(in);
            }
            return new PropertiesConfigSource(properties, "PklConfigSource[source=" + pklFile + "]", ordinal);
        } catch (IOException e) {
            System.err.println("[jhostty] failed to load pkl config " + pklFile + ": " + e.getMessage());
            return null;
        }
    }

    /** True when {@code cacheFile} exists and is at least as new as {@code pklFile}. */
    private static boolean isFresh(Path pklFile, Path cacheFile) throws IOException {
        return Files.isRegularFile(cacheFile)
                && Files.getLastModifiedTime(cacheFile).compareTo(Files.getLastModifiedTime(pklFile)) >= 0;
    }

    /** Runs {@code pkl eval -f properties <pklFile>}, returning its stdout or {@code null} on failure. */
    private static byte[] eval(Path pklFile, Path pklExecutable) {
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
            return stdout;
        } catch (IOException e) {
            System.err.println("[jhostty] failed to run pkl for " + pklFile + ": " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[jhostty] interrupted while running pkl for " + pklFile);
            return null;
        }
    }

    private static void writeAtomic(Path file, byte[] content) throws IOException {
        var tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        Files.write(tmp, content);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
