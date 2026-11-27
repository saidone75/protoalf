package org.saidone.protoalf.client.benchmark;

import com.google.protobuf.Struct;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** REST side uses a cached Alfresco ticket, persistent connections and streamed bodies without compression. */
final class BenchmarkRestClient implements AutoCloseable {
    private static final String API = "/api/-default-/public/alfresco/versions/1";
    private final String base;
    private final Duration timeout;
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
    private final HttpClient http;
    private String authorization;

    BenchmarkRestClient(String base, Duration timeout) {
        this.base = base.replaceAll("/+$", "");
        this.timeout = timeout;
        timer.setRemoveOnCancelPolicy(true);
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).executor(executor).build();
    }
    void login(String username, String password) throws Exception {
        var request = request("/api/-default-/public/authentication/versions/1/tickets")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BenchmarkJson.print(Map.of("userId", username, "password", password))));
        var ticket = BenchmarkJson.text(BenchmarkJson.child(json(request), "entry"), "id");
        if (ticket.isBlank()) throw new IOException("REST authentication returned an empty ticket");
        authorization = "Basic " + Base64.getEncoder().encodeToString(ticket.getBytes(StandardCharsets.UTF_8));
    }
    Struct node(String id) throws Exception {
        return BenchmarkJson.child(json(request(API + "/nodes/" + path(id) + "?include=properties,aspectNames")), "entry");
    }
    Struct list(String folder, int size) throws Exception {
        return BenchmarkJson.child(json(request(API + "/nodes/" + path(folder)
                + "/children?skipCount=0&maxItems=" + size + "&orderBy=name%20ASC&include=properties,aspectNames")), "list");
    }
    void download(String id, OutputStream sink) throws Exception {
        transfer(request(API + "/nodes/" + path(id) + "/content").build(), sink);
    }
    private HttpRequest.Builder request(String path) {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                .header("Accept-Encoding", "identity");
        if (authorization != null) request.header("Authorization", authorization);
        return request;
    }
    private Struct json(HttpRequest.Builder request) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var bounded = new OutputStream() {
            @Override public void write(int value) throws IOException {
                if (bytes.size() == 32 * 1024 * 1024) throw new IOException("REST JSON exceeds 32 MiB");
                bytes.write(value);
            }
            @Override public void write(byte[] buffer, int offset, int length) throws IOException {
                if (length > 32 * 1024 * 1024 - bytes.size()) throw new IOException("REST JSON exceeds 32 MiB");
                bytes.write(buffer, offset, length);
            }
        };
        transfer(request.build(), bounded);
        return BenchmarkJson.parse(bytes.toString(StandardCharsets.UTF_8));
    }
    private void transfer(HttpRequest request, OutputStream sink) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new HttpStatusException(response.statusCode());
            var expired = new AtomicBoolean();
            var guard = timer.schedule(() -> {
                expired.set(true);
                try { body.close(); } catch (IOException ignored) { }
            }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            try {
                body.transferTo(sink);
                if (expired.get() || System.nanoTime() > deadline) throw new java.net.http.HttpTimeoutException("REST body timeout");
            } catch (IOException e) {
                if (expired.get()) throw new java.net.http.HttpTimeoutException("REST body timeout");
                throw e;
            } finally { guard.cancel(false); }
        }
    }
    static String path(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    static final class HttpStatusException extends IOException {
        final int status;
        HttpStatusException(int status) { super("HTTP " + status); this.status = status; }
    }
    @Override public void close() { timer.shutdownNow(); executor.shutdownNow(); }
}
