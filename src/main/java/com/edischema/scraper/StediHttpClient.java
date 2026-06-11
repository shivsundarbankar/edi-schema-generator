package com.edischema.scraper;

import com.edischema.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Polite HTTP client for fetching Stedi reference pages.
 *
 * <ul>
 *   <li>Disk cache: each page is fetched at most once per TTL window, so
 *       re-running the generator does not re-hit the website.</li>
 *   <li>Rate limiting: a minimum interval is enforced between live requests.</li>
 *   <li>Retries with exponential backoff on transient failures.</li>
 * </ul>
 */
@Component
public class StediHttpClient {

    private static final Logger log = LoggerFactory.getLogger(StediHttpClient.class);

    private final AppProperties props;
    private final HttpClient http;
    private final Object rateLock = new Object();
    private long lastRequestAt = 0L;

    public StediHttpClient(AppProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Returns the HTML body of the given URL, served from the local cache
     * when a fresh copy exists.
     */
    public String fetchHtml(String url) {
        Path cacheFile = cachePath(url);
        String cached = readCache(cacheFile);
        if (cached != null) {
            log.debug("cache hit: {}", url);
            return cached;
        }

        String body = fetchLive(url);
        writeCache(cacheFile, url, body);
        return body;
    }

    private String fetchLive(String url) {
        IOException lastIo = null;
        for (int attempt = 1; attempt <= Math.max(1, props.maxRetries()); attempt++) {
            throttle();
            try {
                log.info("GET {} (attempt {})", url, attempt);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", props.userAgent())
                        .header("Accept", "text/html,application/xhtml+xml")
                        .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                        .GET()
                        .build();
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                int status = response.statusCode();
                if (status == 200) {
                    return response.body();
                }
                if (status == 404) {
                    throw new ScrapeException("Page not found (HTTP 404): " + url);
                }
                log.warn("HTTP {} for {} - will retry", status, url);
            } catch (IOException e) {
                lastIo = e;
                log.warn("I/O error fetching {} - {}", url, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ScrapeException("Interrupted while fetching " + url, e);
            }
            sleep(props.retryBackoffMillis() * attempt);
        }
        throw new ScrapeException("Failed to fetch after " + props.maxRetries()
                + " attempts: " + url, lastIo);
    }

    private void throttle() {
        synchronized (rateLock) {
            long now = System.currentTimeMillis();
            long waitFor = (lastRequestAt + props.minRequestIntervalMillis()) - now;
            if (waitFor > 0) {
                sleep(waitFor);
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScrapeException("Interrupted while throttling", e);
        }
    }

    private Path cachePath(String url) {
        return Path.of(props.cacheDir(), sha256(url) + ".html");
    }

    private String readCache(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            if (modified.isBefore(Instant.now().minus(Duration.ofHours(props.cacheTtlHours())))) {
                return null; // stale
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            // First line is a metadata comment with the original URL; strip it.
            int nl = content.indexOf('\n');
            return nl >= 0 ? content.substring(nl + 1) : content;
        } catch (IOException e) {
            log.warn("Could not read cache file {} - {}", file, e.getMessage());
            return null;
        }
    }

    private void writeCache(Path file, String url, String body) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "<!-- " + url + " -->\n" + body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not write cache file {} - {}", file, e.getMessage());
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
