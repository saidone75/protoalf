package org.saidone.protoalf.client.benchmark;

import com.google.protobuf.Struct;
import io.grpc.ManagedChannel;
import org.saidone.protoalf.api.Node;
import org.saidone.protoalf.client.AlfrescoGrpcChannels;
import org.saidone.protoalf.client.AuthenticationServiceClient;
import org.saidone.protoalf.client.ContentServiceClient;
import org.saidone.protoalf.client.DictionaryServiceClient;
import org.saidone.protoalf.client.FileFolderServiceClient;
import org.saidone.protoalf.client.NodeServiceClient;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Opt-in live benchmark; intentionally not named *Test or *IT and never discovers or modifies fixtures. */
public final class RepositoryBenchmark {
    private RepositoryBenchmark() {}

    record Config(String rest, String host, int port, String ca, String authority, String username, String password,
                  List<String> nodes, String folder, List<String> scenarios, List<String> protocols,
                  List<Integer> concurrency, int requests, int warmup, int repetitions, int pageSize,
                  int textChunk, Duration timeout, Path output, String label) {
        static Config parse(String[] arguments, Map<String, String> env) {
            var options = new HashMap<String, String>();
            Set<String> allowed = Set.of("nodes", "folder", "scenarios", "protocols", "concurrency", "requests",
                    "warmup", "repetitions", "page-size", "text-chunk-bytes", "timeout-seconds", "output", "label");
            for (String argument : arguments) {
                String[] parts = argument.replaceFirst("^--", "").split("=", 2);
                if (!argument.startsWith("--") || parts.length != 2 || !allowed.contains(parts[0])
                        || options.putIfAbsent(parts[0], parts[1]) != null) throw new IllegalArgumentException("Unknown, duplicated or malformed benchmark option");
            }
            String rest = env.getOrDefault("BENCH_REST_URL", "http://localhost:8080/alfresco");
            var uri = java.net.URI.create(rest);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException("Invalid BENCH_REST_URL");
            var scenarios = csv(options.getOrDefault("scenarios", "node,list,download"));
            var protocols = csv(options.getOrDefault("protocols", "rest,grpc"));
            if (scenarios.isEmpty() || !Set.of("node", "list", "download", "text").containsAll(scenarios)
                    || protocols.isEmpty() || !Set.of("rest", "grpc").containsAll(protocols)
                    || scenarios.contains("text") && !protocols.contains("grpc")) throw new IllegalArgumentException("Invalid scenarios/protocols");
            var nodes = csv(options.getOrDefault("nodes", ""));
            if (scenarios.stream().anyMatch(s -> !s.equals("list")) && nodes.isEmpty()) throw new IllegalArgumentException("--nodes is required");
            if (nodes.stream().anyMatch(id -> !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))) throw new IllegalArgumentException("Use bare document IDs in --nodes");
            String password = env.get("BENCH_PASSWORD");
            if (password == null || password.isEmpty()) throw new IllegalArgumentException("Set BENCH_PASSWORD in the environment");
            var concurrency = csv(options.getOrDefault("concurrency", "1,4,8")).stream().map(Integer::parseInt).toList();
            if (concurrency.isEmpty() || concurrency.stream().anyMatch(n -> n < 1 || n > 256)) throw new IllegalArgumentException("Concurrency must be 1..256");
            return new Config(rest, env.getOrDefault("BENCH_GRPC_HOST", "localhost"),
                    integer(env.getOrDefault("BENCH_GRPC_PORT", "9090"), 1, 65535), env.getOrDefault("BENCH_GRPC_CA", ""),
                    env.getOrDefault("BENCH_GRPC_AUTHORITY", ""), env.getOrDefault("BENCH_USERNAME", "mcp"), password,
                    nodes, options.getOrDefault("folder", "-root-"), scenarios, protocols, concurrency,
                    number(options, "requests", 200, 1, 1_000_000), number(options, "warmup", 20, 1, 1_000_000),
                    number(options, "repetitions", 3, 1, 100), number(options, "page-size", 100, 1, 1000),
                    number(options, "text-chunk-bytes", 100_000, 4, 2 * 1024 * 1024),
                    Duration.ofSeconds(number(options, "timeout-seconds", 30, 1, 3600)),
                    Path.of(options.getOrDefault("output", "protoalf-client/target/benchmark/" + Instant.now().toString().replace(':', '-'))),
                    options.getOrDefault("label", "warm-cache"));
        }
        private static List<String> csv(String value) {
            if (value.isBlank()) return List.of();
            var values = Arrays.stream(value.split(",", -1)).map(String::strip).toList();
            if (values.stream().anyMatch(String::isEmpty) || Set.copyOf(values).size() != values.size()) throw new IllegalArgumentException("Empty or duplicate list entry");
            return values;
        }
        private static int number(Map<String, String> options, String key, int fallback, int min, int max) {
            return integer(options.getOrDefault(key, String.valueOf(fallback)), min, max);
        }
        private static int integer(String text, int min, int max) {
            int value = Integer.parseInt(text);
            if (value < min || value > max) throw new IllegalArgumentException("Benchmark number out of range");
            return value;
        }
    }

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help")) {
            System.out.println("""
                    RepositoryBenchmark --nodes=ID[,ID] [--folder=-root-] [--scenarios=node,list,download,text]
                      [--protocols=rest,grpc] [--concurrency=1,4,8] [--requests=200] [--warmup=20]
                      [--repetitions=3] [--page-size=100] [--text-chunk-bytes=100000]
                      [--timeout-seconds=30] [--output=DIRECTORY] [--label=warm-cache]
                    Environment: BENCH_PASSWORD (required), BENCH_USERNAME (mcp), BENCH_REST_URL,
                      BENCH_GRPC_HOST, BENCH_GRPC_PORT, BENCH_GRPC_CA, BENCH_GRPC_AUTHORITY.
                    Uses existing fixtures only. Text is gRPC-only; no REST text-extraction equivalence is assumed.
                    See doc/BENCHMARK.md for scope, metrics and cache/transport limitations.
                    """);
            return;
        }
        run(Config.parse(args, System.getenv()));
    }

    static void run(Config config) throws Exception {
        Files.createDirectories(config.output);
        try (var contents = Files.list(config.output)) {
            if (contents.findAny().isPresent()) throw new IllegalArgumentException("Benchmark output directory must be empty");
        }
        var report = new TreeMap<String, Object>();
        var runs = new ArrayList<Map<String, Object>>();
        report.put("schemaVersion", 1); report.put("started", Instant.now().toString());
        report.put("label", config.label); report.put("cacheMode", "warm; preflight and per-cell warmup, no cache reset");
        report.put("java", System.getProperty("java.version")); report.put("os", System.getProperty("os.name"));
        report.put("gitRevision", System.getenv().getOrDefault("BENCH_GIT_REVISION", "unknown"));
        report.put("configuration", Map.ofEntries(Map.entry("restUrl", config.rest), Map.entry("grpcHost", config.host),
                Map.entry("grpcPort", config.port), Map.entry("grpcTls", !config.ca.isBlank()),
                Map.entry("nodes", config.nodes), Map.entry("folder", config.folder), Map.entry("scenarios", config.scenarios),
                Map.entry("protocols", config.protocols), Map.entry("concurrency", config.concurrency),
                Map.entry("requests", config.requests), Map.entry("warmup", config.warmup), Map.entry("repetitions", config.repetitions),
                Map.entry("pageSize", config.pageSize), Map.entry("textChunkBytes", config.textChunk),
                Map.entry("timeoutSeconds", config.timeout.toSeconds())));
        report.put("runs", runs);
        ManagedChannel channel = null;
        var deadlines = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "benchmark-deadline"); thread.setDaemon(true); return thread;
        });
        deadlines.setRemoveOnCancelPolicy(true);
        try (var rest = new BenchmarkRestClient(config.rest, config.timeout);
             var raw = Files.newBufferedWriter(config.output.resolve("samples.csv"), StandardOpenOption.CREATE_NEW)) {
            channel = config.protocols.contains("grpc") ? AlfrescoGrpcChannels.create(config.host, config.port, config.ca, config.authority) : null;
            var tickets = channel == null ? null : new AuthenticationServiceClient(channel, config.username, config.password, config.timeout);
            var nodes = channel == null ? null : new NodeServiceClient(channel, tickets, config.timeout);
            var files = channel == null ? null : new FileFolderServiceClient(channel, tickets, config.timeout);
            var content = channel == null ? null : new ContentServiceClient(channel, tickets, config.timeout);
            var prefixes = new HashMap<String, String>();
            if (config.protocols.contains("grpc")) {
                tickets.ticket();
                for (var model : new DictionaryServiceClient(channel, tickets, config.timeout).getAllModels().getModelsList())
                    for (var namespace : model.getNamespacesList()) prefixes.put(namespace.getUri(), namespace.getPrefix());
            }
            if (config.protocols.contains("rest")) rest.login(config.username, config.password);
            var corpus = new ArrayList<Map<String, Object>>();
            // Preflight checks are outside timed cells and intentionally warm repository caches.
            for (String id : config.nodes) {
                var fixture = new TreeMap<String, Object>();
                fixture.put("id", id);
                if (config.protocols.containsAll(List.of("rest", "grpc"))) {
                    requireEqual(signature(rest.node(id)), signature(nodes.getNode(id), prefixes), "Node identity/type/aspects mismatch");
                }
                if (config.scenarios.contains("download")) {
                    Map<String, Object> reference = null;
                    for (String protocol : config.protocols) {
                        var sink = new Sink(true);
                        if (protocol.equals("rest")) rest.download(id, sink); else content.downloadContent(id, sink);
                        var fingerprint = Map.<String, Object>of("bytes", sink.count, "sha256", sink.hash());
                        if (reference != null) requireEqual(reference, fingerprint, "Binary content mismatch");
                        reference = fingerprint;
                    }
                    fixture.put("binary", reference);
                }
                corpus.add(fixture);
            }
            if (config.scenarios.contains("list") && config.protocols.containsAll(List.of("rest", "grpc"))) {
                var left = new ArrayList<String>();
                for (var entry : BenchmarkJson.field(rest.list(config.folder, config.pageSize), "entries").getListValue().getValuesList())
                    left.add(signature(BenchmarkJson.child(entry.getStructValue(), "entry")));
                var right = files.list(config.folder, 0, config.pageSize).getEntriesList().stream().map(node -> signature(node, prefixes)).toList();
                requireEqual(left, right, "Folder page/order mismatch");
            }
            report.put("corpus", corpus);
            raw.write("scenario,protocol,concurrency,repetition,index,latency_ns,units,content_bytes,error\n");
            for (String scenario : config.scenarios) for (int concurrency : config.concurrency) {
                for (int repetition = 1; repetition <= config.repetitions; repetition++) {
                    var order = new ArrayList<>(config.protocols);
                    if (repetition % 2 == 0) Collections.reverse(order);
                    for (String protocol : order) {
                        if (scenario.equals("text") && protocol.equals("rest")) continue;
                        BenchmarkRunner.Operation operation = index -> {
                            String id = config.nodes.isEmpty() ? "" : config.nodes.get(index % config.nodes.size());
                            return switch (scenario) {
                                case "node" -> {
                                    if (protocol.equals("rest")) rest.node(id); else nodes.getNode(id);
                                    yield new BenchmarkRunner.Work(1, 0);
                                }
                                case "list" -> new BenchmarkRunner.Work(protocol.equals("rest")
                                        ? BenchmarkJson.field(rest.list(config.folder, config.pageSize), "entries").getListValue().getValuesCount()
                                        : files.list(config.folder, 0, config.pageSize).getEntriesCount(), 0);
                                case "download" -> {
                                    var sink = new Sink(false);
                                    if (protocol.equals("rest")) rest.download(id, sink); else content.downloadContent(id, sink);
                                    yield new BenchmarkRunner.Work(1, sink.count);
                                }
                                case "text" -> {
                                    try (var context = io.grpc.Context.current().withDeadlineAfter(config.timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS, deadlines)) {
                                        yield context.call(() -> text(content, id, config.textChunk, config.timeout));
                                    }
                                }
                                default -> throw new IllegalStateException();
                            };
                        };
                        var warmup = BenchmarkRunner.run(concurrency, config.warmup, operation);
                        if (warmup.successes() != config.warmup) throw new IllegalStateException("Warmup failed: " + warmup.summary().get("errors"));
                        var measured = BenchmarkRunner.run(concurrency, config.requests, operation);
                        var summary = new TreeMap<String, Object>(measured.summary());
                        summary.put("scenario", scenario); summary.put("protocol", protocol);
                        summary.put("concurrency", concurrency); summary.put("repetition", repetition);
                        summary.put("latencyScope", scenario.equals("text") ? "whole document, all text pages" : "whole operation, including body consumption");
                        runs.add(summary);
                        for (var sample : measured.samples()) raw.write(String.format(java.util.Locale.ROOT,
                                "%s,%s,%d,%d,%d,%d,%d,%d,%s%n", scenario, protocol, concurrency, repetition, sample.index(),
                                sample.nanos(), sample.work() == null ? 0 : sample.work().units(), sample.work() == null ? 0 : sample.work().bytes(),
                                sample.error() == null ? "" : sample.error()));
                        raw.flush();
                        Files.writeString(config.output.resolve("report.json"), BenchmarkJson.print(report));
                        System.out.printf(java.util.Locale.ROOT, "%s %s workers=%d run=%d: %.2f successful ops/s, errors=%d%n",
                                scenario, protocol, concurrency, repetition, measured.successes() / measured.seconds(), config.requests - measured.successes());
                    }
                }
            }
            boolean errors = runs.stream().anyMatch(run -> ((Number) run.get("successes")).longValue() < config.requests);
            report.put("status", errors ? "completed_with_errors" : "completed");
            if (errors) throw new IllegalStateException("Measured requests failed; inspect report.json");
        } catch (Exception e) {
            report.putIfAbsent("status", "aborted"); report.put("failure", BenchmarkRunner.errorCode(e));
            throw e;
        } finally {
            deadlines.shutdownNow();
            report.put("finished", Instant.now().toString());
            Files.writeString(config.output.resolve("report.json"), BenchmarkJson.print(report));
            if (channel != null) { channel.shutdownNow(); channel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
        }
    }

    static BenchmarkRunner.Work text(ContentServiceClient client, String id, int chunk, Duration timeout) throws Exception {
        long offset = 0;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (System.nanoTime() >= deadline) throw new java.util.concurrent.TimeoutException("Text document timeout");
            var page = client.getReader(id, true, offset, chunk);
            if (System.nanoTime() >= deadline) throw new java.util.concurrent.TimeoutException("Text document timeout");
            long bytes = page.getContent().getData().size();
            if (!page.getContent().getData().isValidUtf8()) throw new IllegalStateException("Invalid UTF-8 text response");
            offset = Math.addExact(offset, bytes);
            if (!page.getHasMore()) return new BenchmarkRunner.Work(1, offset);
            if (bytes == 0) throw new IllegalStateException("Text pagination did not advance");
        }
    }

    private static String prefixed(String value, Map<String, String> prefixes) {
        int end = value.indexOf('}');
        if (!value.startsWith("{") || end < 0) return value;
        String prefix = prefixes.get(value.substring(1, end));
        return prefix == null ? value : prefix + ":" + value.substring(end + 1);
    }
    private static String signature(Node node, Map<String, String> prefixes) {
        var aspects = new TreeSet<String>();
        node.getAspectsList().stream()
                .filter(aspect -> !aspect.startsWith("{http://www.alfresco.org/model/system/1.0}"))
                .forEach(aspect -> aspects.add(prefixed(aspect, prefixes)));
        return node.getNodeRef().substring(node.getNodeRef().lastIndexOf('/') + 1) + "|" + node.getName() + "|" + prefixed(node.getType(), prefixes) + "|" + aspects;
    }
    private static String signature(Struct node) throws Exception {
        var aspects = new TreeSet<String>();
        var values = node.getFieldsMap().get("aspectNames");
        if (values != null) values.getListValue().getValuesList().forEach(value -> aspects.add(value.getStringValue()));
        return BenchmarkJson.text(node, "id") + "|" + BenchmarkJson.text(node, "name") + "|" + BenchmarkJson.text(node, "nodeType") + "|" + aspects;
    }
    private static void requireEqual(Object left, Object right, String message) {
        if (!left.equals(right)) throw new IllegalStateException(message + "; use a stable corpus and equivalent endpoints/permissions");
    }
    static final class Sink extends OutputStream {
        long count;
        private final MessageDigest digest;
        Sink(boolean hashing) throws Exception { digest = hashing ? MessageDigest.getInstance("SHA-256") : null; }
        @Override public void write(int value) { count++; if (digest != null) digest.update((byte) value); }
        @Override public void write(byte[] bytes, int offset, int length) { count += length; if (digest != null) digest.update(bytes, offset, length); }
        String hash() { return HexFormat.of().formatHex(digest.digest()); }
    }
}
