package org.saidone.protoalf.client.benchmark;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.saidone.protoalf.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Verifies the harness over real local HTTP/gRPC transports, without requiring or modifying ACS. */
class RepositoryBenchmarkTest {
    @TempDir Path temporary;
    private HttpServer rest;
    private Server grpc;
    private final java.util.concurrent.ExecutorService httpExecutor = Executors.newCachedThreadPool();
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger nodeReads = new AtomicInteger();
    private volatile boolean mismatch;
    private volatile boolean failMeasured;
    private volatile boolean stallContent;
    private final java.util.concurrent.CountDownLatch releaseBody = new java.util.concurrent.CountDownLatch(1);
    private static final byte[] DATA = "benchmark content".getBytes(StandardCharsets.UTF_8);
    private static final String CM = "http://www.alfresco.org/model/content/1.0";

    @BeforeEach
    void start() throws Exception {
        var node = Node.newBuilder().setNodeRef("workspace://SpacesStore/doc").setName("document.txt")
                .setType("{" + CM + "}content").addAspects("{" + CM + "}titled")
                .addAspects("{http://www.alfresco.org/model/system/1.0}referenceable").build();
        grpc = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(new AuthenticationServiceGrpc.AuthenticationServiceImplBase() {
                    @Override public void authenticate(LoginRequest request, StreamObserver<LoginResponse> observer) {
                        observer.onNext(LoginResponse.newBuilder().setTicket("grpc-private-ticket").build()); observer.onCompleted();
                    }
                }).addService(new NodeServiceGrpc.NodeServiceImplBase() {
                    @Override public void getNode(GetNodeRequest request, StreamObserver<Node> observer) {
                        observer.onNext(node); observer.onCompleted();
                    }
                }).addService(new DictionaryServiceGrpc.DictionaryServiceImplBase() {
                    @Override public void getAllModels(ListModelsRequest request, StreamObserver<ListModelsResponse> observer) {
                        observer.onNext(ListModelsResponse.newBuilder().addModels(ModelInfo.newBuilder()
                                .addNamespaces(NamespaceInfo.newBuilder().setPrefix("cm").setUri(CM))).build()); observer.onCompleted();
                    }
                }).addService(new FileFolderServiceGrpc.FileFolderServiceImplBase() {
                    @Override public void list(ListChildrenRequest request, StreamObserver<ListChildrenResponse> observer) {
                        observer.onNext(ListChildrenResponse.newBuilder().addEntries(node).setTotalItems(1).build()); observer.onCompleted();
                    }
                }).addService(new ContentServiceGrpc.ContentServiceImplBase() {
                    @Override public void downloadContent(DownloadContentRequest request, StreamObserver<DownloadContentResponse> observer) {
                        observer.onNext(DownloadContentResponse.newBuilder().setInfo(ContentInfo.newBuilder().setSize(DATA.length)).build());
                        observer.onNext(DownloadContentResponse.newBuilder().setData(ByteString.copyFrom(DATA)).build()); observer.onCompleted();
                    }
                    @Override public void getReader(GetContentRequest request, StreamObserver<GetContentResponse> observer) {
                        int start = (int) request.getOffset();
                        int size = Math.min(request.getMaxBytes(), DATA.length - start);
                        observer.onNext(GetContentResponse.newBuilder().setSize(DATA.length).setHasMore(start + size < DATA.length)
                                .setContent(Content.newBuilder().setData(ByteString.copyFrom(DATA, start, size))).build()); observer.onCompleted();
                    }
                }).build().start();
        rest = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rest.setExecutor(httpExecutor);
        rest.createContext("/alfresco", exchange -> {
            int status = 200;
            byte[] body;
            String entry = "{\"id\":\"doc\",\"name\":\"" + (mismatch ? "different.txt" : "document.txt")
                    + "\",\"nodeType\":\"cm:content\",\"aspectNames\":[\"cm:titled\"],\"properties\":{}}";
            if (exchange.getRequestURI().getPath().endsWith("/tickets")) {
                logins.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                body = "{\"entry\":{\"id\":\"rest-private-ticket\"}}".getBytes(StandardCharsets.UTF_8);
            } else if (!exchange.getRequestHeaders().getFirst("Authorization").equals("Basic "
                    + Base64.getEncoder().encodeToString("rest-private-ticket".getBytes(StandardCharsets.UTF_8)))) {
                status = 401; body = new byte[0];
            } else if (exchange.getRequestURI().getPath().endsWith("/children")) {
                body = ("{\"list\":{\"entries\":[{\"entry\":" + entry + "}],\"pagination\":{\"hasMoreItems\":false,\"totalItems\":1}}}").getBytes(StandardCharsets.UTF_8);
            } else if (exchange.getRequestURI().getPath().endsWith("/content")) body = DATA;
            else {
                if (failMeasured && nodeReads.incrementAndGet() > 1) status = 503;
                body = ("{\"entry\":" + entry + "}").getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                if (stallContent && exchange.getRequestURI().getPath().endsWith("/content")) {
                    output.write(body, 0, 1); output.flush();
                    try { releaseBody.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                } else output.write(body);
            }
            exchange.close();
        });
        rest.start();
    }
    @AfterEach
    void stop() throws Exception {
        releaseBody.countDown();
        if (rest != null) rest.stop(0);
        httpExecutor.shutdownNow();
        if (grpc != null) { grpc.shutdownNow(); grpc.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
    }
    private RepositoryBenchmark.Config config(String output, String... extra) {
        var args = new java.util.ArrayList<>(java.util.List.of("--nodes=doc", "--concurrency=2", "--requests=3",
                "--warmup=1", "--repetitions=2", "--output=" + temporary.resolve(output), "--text-chunk-bytes=4"));
        for (String option : extra) {
            String key = option.substring(0, option.indexOf('=') + 1);
            args.removeIf(existing -> existing.startsWith(key));
            args.add(option);
        }
        return RepositoryBenchmark.Config.parse(args.toArray(String[]::new), Map.of("BENCH_PASSWORD", "private-password",
                "BENCH_REST_URL", "http://127.0.0.1:" + rest.getAddress().getPort() + "/alfresco",
                "BENCH_GRPC_HOST", "127.0.0.1", "BENCH_GRPC_PORT", String.valueOf(grpc.getPort())));
    }
    @Test
    void runsReadOnlyScenariosAndWritesVerifiedReproducibleResultsWithoutSecrets() throws Exception {
        RepositoryBenchmark.run(config("results", "--scenarios=node,list,download,text"));
        String json = Files.readString(temporary.resolve("results/report.json"));
        var report = BenchmarkJson.parse(json);
        assertThat(BenchmarkJson.text(report, "status")).isEqualTo("completed");
        var runs = BenchmarkJson.field(report, "runs").getListValue().getValuesList();
        assertThat(runs).hasSize(14);
        for (var run : runs) {
            assertThat(BenchmarkJson.field(run.getStructValue(), "successes").getNumberValue()).isEqualTo(3);
            assertThat(BenchmarkJson.field(run.getStructValue(), "errors").getStructValue().getFieldsMap()).isEmpty();
        }
        assertThat(logins.get()).isEqualTo(1);
        assertThat(json).doesNotContain("private-password", "rest-private-ticket", "grpc-private-ticket");
        assertThat(Files.readAllLines(temporary.resolve("results/samples.csv"))).hasSize(43);
        assertThatIllegalArgumentException().isThrownBy(() -> RepositoryBenchmark.run(config("results", "--scenarios=node")));
    }
    @Test
    void rejectsDifferentCorporaBeforeTimingAnything() throws Exception {
        mismatch = true;
        assertThatThrownBy(() -> RepositoryBenchmark.run(config("mismatch", "--scenarios=node"))).hasMessageContaining("mismatch");
        var report = BenchmarkJson.parse(Files.readString(temporary.resolve("mismatch/report.json")));
        assertThat(BenchmarkJson.text(report, "status")).isEqualTo("aborted");
        assertThat(BenchmarkJson.field(report, "runs").getListValue().getValuesList()).isEmpty();
    }
    @Test
    void measuredErrorsProduceNonzeroOutcomeAndRemainInReports() throws Exception {
        failMeasured = true;
        assertThatThrownBy(() -> RepositoryBenchmark.run(config("errors", "--scenarios=node", "--protocols=rest", "--repetitions=1")))
                .isInstanceOf(IllegalStateException.class);
        String report = Files.readString(temporary.resolve("errors/report.json"));
        assertThat(report).contains("HTTP_503", "completed_with_errors");
        assertThat(Files.readString(temporary.resolve("errors/samples.csv"))).contains("HTTP_503");
    }
    @Test
    void restTimeoutAlsoCoversAStalledResponseBody() throws Exception {
        stallContent = true;
        try (var client = new BenchmarkRestClient("http://127.0.0.1:" + rest.getAddress().getPort() + "/alfresco", java.time.Duration.ofSeconds(1))) {
            client.login("mcp", "private-password");
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () ->
                    assertThatThrownBy(() -> client.download("doc", java.io.OutputStream.nullOutputStream()))
                            .isInstanceOf(java.net.http.HttpTimeoutException.class));
        } finally { releaseBody.countDown(); }
    }

}
