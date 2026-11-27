package org.saidone.protoalf.client.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Closed-loop load generator. Latencies include the operation, not waiting for a worker. */
final class BenchmarkRunner {
    record Work(long units, long bytes) {}
    @FunctionalInterface interface Operation { Work run(int index) throws Exception; }
    record Sample(int index, long nanos, Work work, String error) {}
    record Result(double seconds, List<Sample> samples) {
        long successes() { return samples.stream().filter(s -> s.error == null).count(); }
        Map<String, Object> summary() {
            long[] times = samples.stream().filter(s -> s.error == null).mapToLong(Sample::nanos).sorted().toArray();
            long units = samples.stream().filter(s -> s.error == null).mapToLong(s -> s.work.units).sum();
            long bytes = samples.stream().filter(s -> s.error == null).mapToLong(s -> s.work.bytes).sum();
            var errors = new TreeMap<String, Long>();
            samples.stream().filter(s -> s.error != null).forEach(s -> errors.merge(s.error, 1L, Long::sum));
            var result = new TreeMap<String, Object>();
            result.put("attempts", samples.size()); result.put("successes", successes());
            result.put("errors", errors); result.put("elapsedSeconds", seconds);
            result.put("successfulOperationsPerSecond", successes() / seconds);
            result.put("successfulUnitsPerSecond", units / seconds);
            result.put("contentMiBPerSecond", bytes / seconds / (1024 * 1024));
            result.put("contentBytes", bytes);
            result.put("latencyMs", times.length == 0 ? Map.of() : Map.of(
                    "mean", Arrays.stream(times).average().orElseThrow() / 1e6,
                    "p50", percentile(times, .50) / 1e6, "p95", percentile(times, .95) / 1e6,
                    "p99", percentile(times, .99) / 1e6, "max", times[times.length - 1] / 1e6));
            return result;
        }
    }

    static long percentile(long[] sorted, double quantile) {
        if (sorted.length == 0) throw new IllegalArgumentException("Empty sample");
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * quantile) - 1)];
    }

    static Result run(int concurrency, int requests, Operation operation) throws Exception {
        if (concurrency < 1 || requests < 1) throw new IllegalArgumentException("Positive worker/request counts required");
        var samples = new Sample[requests];
        var next = new AtomicInteger();
        var ready = new CountDownLatch(concurrency);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(concurrency);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int worker = 0; worker < concurrency; worker++) futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int index = next.getAndIncrement(); index < requests; index = next.getAndIncrement()) {
                        long before = System.nanoTime();
                        Work work = null;
                        String error = null;
                        try { work = operation.run(index); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
                        catch (Exception e) { error = errorCode(e); }
                        samples[index] = new Sample(index, Math.max(1, System.nanoTime() - before), work, error);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Benchmark worker interrupted", e);
                }
            }));
            ready.await();
            long before = System.nanoTime();
            start.countDown();
            for (var future : futures) future.get();
            return new Result(Math.max(1, System.nanoTime() - before) / 1e9, List.of(samples));
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    static String errorCode(Exception error) {
        if (error instanceof BenchmarkRestClient.HttpStatusException http) return "HTTP_" + http.status;
        if (error instanceof io.grpc.StatusRuntimeException grpc) return "GRPC_" + grpc.getStatus().getCode();
        // Do not persist exception messages, URLs, content, credentials or tickets.
        return error.getClass().getSimpleName();
    }
}
