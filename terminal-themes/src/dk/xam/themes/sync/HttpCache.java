package dk.xam.themes.sync;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * Disk-backed HTTP cache for theme sync.
 * <p>
 * Caches GET responses by URL hash under {@code .cache/themes/}.
 * Serves from cache if the file exists and is younger than {@code maxAge}.
 * Handles concurrency via a semaphore to limit parallel requests.
 */
public class HttpCache {

    private final HttpClient client;
    private final Path cacheDir;
    private final Duration maxAge;
    private final boolean enabled;
    private final Semaphore semaphore;
    private int cacheHits;
    private int networkRequests;

    /**
     * @param cacheDir  directory for cached responses
     * @param maxAge    max age before a cached entry is considered stale
     * @param enabled   false to bypass cache entirely
     * @param concurrency max parallel HTTP requests
     */
    public HttpCache(Path cacheDir, Duration maxAge, boolean enabled, int concurrency) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.cacheDir = cacheDir;
        this.maxAge = maxAge;
        this.enabled = enabled;
        this.semaphore = new Semaphore(concurrency);
    }

    /** Synchronous cached GET. */
    public String get(URI uri) throws IOException, InterruptedException {
        if (enabled) {
            var cached = readCache(uri);
            if (cached != null) {
                cacheHits++;
                return cached;
            }
        }

        semaphore.acquire();
        try {
            var req = HttpRequest.newBuilder(uri).GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            networkRequests++;
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + uri);
            }
            String body = resp.body();
            if (enabled) writeCache(uri, body);
            return body;
        } finally {
            semaphore.release();
        }
    }

    /** Async cached GET — runs on the common ForkJoinPool. */
    public CompletableFuture<String> getAsync(URI uri) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return get(uri);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Build a URI that handles spaces and non-ASCII in the path. */
    public static URI safeUri(String raw) {
        try {
            var u = URI.create(raw.replace(" ", "%20"));
            return new URI(u.getScheme(), u.getAuthority(), u.getPath(), u.getQuery(), u.getFragment());
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad URI: " + raw, e);
        }
    }

    public int cacheHits() { return cacheHits; }
    public int networkRequests() { return networkRequests; }

    /** Clear all cached files. */
    public void clearAll() throws IOException {
        if (Files.exists(cacheDir)) {
            try (var walk = Files.walk(cacheDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }
    }

    // --- internals ---

    private String readCache(URI uri) {
        try {
            var path = cachePath(uri);
            if (!Files.exists(path)) return null;
            var modified = Files.getLastModifiedTime(path).toInstant();
            if (Duration.between(modified, Instant.now()).compareTo(maxAge) > 0) return null;
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }

    private void writeCache(URI uri, String body) {
        try {
            var path = cachePath(uri);
            Files.createDirectories(path.getParent());
            Files.writeString(path, body);
        } catch (IOException ignored) {}
    }

    private Path cachePath(URI uri) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(uri.toString().getBytes());
            String hex = HexFormat.of().formatHex(hash).substring(0, 16);
            // Use first 2 chars as subdirectory to avoid huge flat directories
            return cacheDir.resolve(hex.substring(0, 2)).resolve(hex + ".txt");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
