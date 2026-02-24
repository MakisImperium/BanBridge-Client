package org.banbridge.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class BackendClient {

    private final String baseUrl;
    private final String serverKey;

    /**
     * SERVER_API_TOKEN used for:
     * Authorization: Bearer <SERVER_API_TOKEN>
     */
    private final String serverToken;

    private final HttpClient http;
    private final ObjectMapper om;

    private final int maxAttempts;
    private final long baseBackoffMillis;
    private final long maxBackoffMillis;

    private final Random jitter = new Random();

    public BackendClient(
            String baseUrl,
            String serverKey,
            String serverToken,
            Duration connectTimeout,
            int maxAttempts,
            long baseBackoffMillis,
            long maxBackoffMillis
    ) {
        String normalized = (baseUrl == null) ? "" : baseUrl.trim();
        this.baseUrl = trimTrailingSlash(normalized);

        this.serverKey = (serverKey == null) ? "" : serverKey.trim();
        this.serverToken = (serverToken == null) ? "" : serverToken.trim();

        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMillis = Math.max(50, baseBackoffMillis);
        this.maxBackoffMillis = Math.max(this.baseBackoffMillis, maxBackoffMillis);

        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout)
                .build();

        this.om = new ObjectMapper();
    }

    public BackendClient(String baseUrl, String serverKey, String serverToken, Duration connectTimeout) {
        this(baseUrl, serverKey, serverToken, connectTimeout, 4, 250, 5_000);
    }

    // ----------------------------
    // Public API (async callbacks)
    // ----------------------------

    public void healthCheckAsync(Consumer<Optional<HealthResponse>> callback) {
        try {
            String url = baseUrl + "/api/server/health";
            HttpRequest req = baseRequest(url).GET().build();

            sendJsonWithRetry(req, HealthResponse.class, "health")
                    .thenApply(Optional::ofNullable)
                    .exceptionally(ex -> {
                        logFail("health", url, ex);
                        return Optional.empty();
                    })
                    .thenAccept(callback);
        } catch (Exception e) {
            logFail("health", baseUrl + "/api/server/health", e);
            callback.accept(Optional.empty());
        }
    }

    public void fetchBanChangesAsync(String sinceCursor, Consumer<Optional<BanChangesResponse>> callback) {
        String cursor = (sinceCursor == null) ? "" : sinceCursor;
        String url = baseUrl + "/api/server/bans/changes?since=" + encodeQuery(cursor);

        try {
            HttpRequest req = baseRequest(url).GET().build();

            sendJsonWithRetry(req, BanChangesResponse.class, "banChanges")
                    .thenApply(Optional::ofNullable)
                    .exceptionally(ex -> {
                        logFail("banChanges", url, ex);
                        return Optional.empty();
                    })
                    .thenAccept(callback);
        } catch (Exception e) {
            logFail("banChanges", url, e);
            callback.accept(Optional.empty());
        }
    }

    public void postStatsBatchAsync(StatsBatchRequest batch, Consumer<Boolean> callback) {
        postJsonAsync("/api/server/stats/batch", batch, "statsBatch", callback);
    }

    public void postPresenceAsync(PresenceRequest presence, Consumer<Boolean> callback) {
        postJsonAsync("/api/server/presence", presence, "presence", callback);
    }

    public void reportBanEnforcedAsync(BanReportRequest report, Consumer<Boolean> callback) {
        postJsonAsync("/api/server/bans/report", report, "banReport", callback);
    }

    /**
     * Posts metrics and returns HTTP status via callback (for per-send logging).
     */
    public void postMetricsAsync(ServerMetricsRequest metrics, Consumer<PostResult> callback) {
        postJsonAsyncWithStatus("/api/server/metrics", metrics, "metrics", callback);
    }

    public void pollCommandsAsync(String sinceId, Consumer<Optional<CommandsPollResponse>> callback) {
        String sid = (sinceId == null) ? "0" : sinceId;
        String url = baseUrl + "/api/server/commands/poll?serverKey=" + encodeQuery(serverKey) + "&sinceId=" + encodeQuery(sid);

        try {
            HttpRequest req = baseRequest(url).GET().build();

            sendJsonWithRetry(req, CommandsPollResponse.class, "commandsPoll")
                    .thenApply(Optional::ofNullable)
                    .exceptionally(ex -> {
                        logFail("commandsPoll", url, ex);
                        return Optional.empty();
                    })
                    .thenAccept(callback);
        } catch (Exception e) {
            logFail("commandsPoll", url, e);
            callback.accept(Optional.empty());
        }
    }

    public void ackCommandAsync(long id, Consumer<Boolean> callback) {
        CommandAckRequest body = new CommandAckRequest(serverKey, id);
        postJsonAsync("/api/server/commands/ack", body, "commandsAck", callback);
    }

    // ----------------------------
    // Internals: POST helper
    // ----------------------------

    private void postJsonAsync(String path, Object body, String op, Consumer<Boolean> callback) {
        String url = baseUrl + path;

        try {
            String json = om.writeValueAsString(body);

            HttpRequest req = baseRequest(url)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            sendWithRetry(req, op)
                    .thenApply(resp -> resp.statusCode() / 100 == 2)
                    .exceptionally(ex -> {
                        logFail(op, url, ex);
                        return false;
                    })
                    .thenAccept(callback);
        } catch (Exception e) {
            logFail(op, url, e);
            callback.accept(false);
        }
    }

    private void postJsonAsyncWithStatus(String path, Object body, String op, Consumer<PostResult> callback) {
        String url = baseUrl + path;

        try {
            String json = om.writeValueAsString(body);

            HttpRequest req = baseRequest(url)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            sendWithRetry(req, op)
                    .thenApply(resp -> new PostResult(resp.statusCode() / 100 == 2, resp.statusCode()))
                    .exceptionally(ex -> {
                        logFail(op, url, ex);
                        return new PostResult(false, null);
                    })
                    .thenAccept(callback);
        } catch (Exception e) {
            logFail(op, url, e);
            callback.accept(new PostResult(false, null));
        }
    }

    private <T> CompletableFuture<T> sendJsonWithRetry(HttpRequest req, Class<T> clazz, String op) {
        return sendWithRetry(req, op).thenApply(resp -> {
            int sc = resp.statusCode();
            if (sc / 100 != 2) {
                throw new CompletionException(new IOException("HTTP " + sc + " body=" + clip(resp.body(), 240)));
            }
            try {
                return om.readValue(resp.body(), clazz);
            } catch (Exception e) {
                throw new CompletionException(new IOException("JSON parse failed: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage() + " body=" + clip(resp.body(), 240), e));
            }
        });
    }

    // ----------------------------
    // Retry/Backoff
    // ----------------------------

    private CompletableFuture<HttpResponse<String>> sendWithRetry(HttpRequest req, String op) {
        return sendWithRetry0(req, op, 1);
    }

    private CompletableFuture<HttpResponse<String>> sendWithRetry0(HttpRequest req, String op, int attempt) {
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((resp, err) -> {
                    if (err == null) {
                        int sc = resp.statusCode();

                        // DO NOT retry auth errors; log once
                        if (sc == 401 || sc == 403) {
                            System.out.println("[BanBridge] op=" + op + " HTTP " + sc + " (auth failed?) url=" + req.uri());
                            return CompletableFuture.completedFuture(resp);
                        }

                        // Retry on 5xx + 429
                        if (((sc >= 500 && sc <= 599) || sc == 429) && attempt < maxAttempts) {
                            long delay = computeDelayMillis(attempt, sc == 429);
                            return delayFuture(delay).thenCompose(v -> sendWithRetry0(req, op, attempt + 1));
                        }

                        return CompletableFuture.completedFuture(resp);
                    }

                    Throwable root = unwrap(err);

                    if (isRetryableNetworkError(root) && attempt < maxAttempts) {
                        long delay = computeDelayMillis(attempt, false);
                        return delayFuture(delay).thenCompose(v -> sendWithRetry0(req, op, attempt + 1));
                    }

                    CompletableFuture<HttpResponse<String>> failed = new CompletableFuture<>();
                    failed.completeExceptionally(root);
                    return failed;
                })
                .thenCompose(f -> f);
    }

    private boolean isRetryableNetworkError(Throwable t) {
        if (t == null) return false;
        if (t instanceof ConnectException) return true;
        if (t instanceof HttpConnectTimeoutException) return true;
        if (t instanceof java.net.SocketTimeoutException) return true;
        return t instanceof IOException;
    }

    private Throwable unwrap(Throwable t) {
        if (t instanceof CompletionException ce && ce.getCause() != null) return ce.getCause();
        return t;
    }

    private long computeDelayMillis(int attempt, boolean isRateLimit) {
        long exp = baseBackoffMillis * (1L << Math.min(10, attempt - 1));
        long capped = Math.min(exp, maxBackoffMillis);
        if (isRateLimit) capped = Math.min(Math.max(capped, 1_000), maxBackoffMillis);
        long jitterPart = (long) (capped * (0.20 * jitter.nextDouble()));
        return capped + jitterPart;
    }

    private CompletableFuture<Void> delayFuture(long millis) {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ----------------------------
    // Request builder / utils
    // ----------------------------

    private HttpRequest.Builder baseRequest(String url) {
        // Contract requires:
        //   Authorization: Bearer <SERVER_API_TOKEN>
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + serverToken)
                .header("Accept", "application/json");
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String encodeQuery(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    private static void logFail(String op, String url, Throwable ex) {
        Throwable root = ex;
        if (root instanceof CompletionException ce && ce.getCause() != null) root = ce.getCause();
        System.out.println("[BanBridge] op=" + op + " failed url=" + url
                + " err=" + root.getClass().getSimpleName() + ": " + root.getMessage());
    }

    // ----------------------------
    // DTOs
    // ----------------------------

    public record HealthResponse(String status, String serverTime, Boolean dbOk) {}

    public record PostResult(boolean ok, Integer statusCode) {}
}