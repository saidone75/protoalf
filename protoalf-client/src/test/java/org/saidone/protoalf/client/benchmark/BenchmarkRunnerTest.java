package org.saidone.protoalf.client.benchmark;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

class BenchmarkRunnerTest {
    @Test
    void executesEachIndexOnceAndReportsFailuresWithoutInflatingThroughput() throws Exception {
        var seen = ConcurrentHashMap.<Integer>newKeySet();
        var result = BenchmarkRunner.run(4, 100, index -> {
            assertThat(seen.add(index)).isTrue();
            if (index % 10 == 0) throw new BenchmarkRestClient.HttpStatusException(503);
            return new BenchmarkRunner.Work(2, 1024);
        });
        assertThat(seen).hasSize(100);
        assertThat(result.successes()).isEqualTo(90);
        assertThat(result.summary()).containsEntry("errors", Map.of("HTTP_503", 10L)).containsEntry("contentBytes", 90L * 1024);
        assertThat((double) result.summary().get("successfulOperationsPerSecond")).isEqualTo(90 / result.seconds());
        assertThat(result.samples()).allSatisfy(sample -> assertThat(sample.nanos()).isPositive());
    }
    @Test
    void percentilesUseNearestRank() {
        assertThat(BenchmarkRunner.percentile(new long[] {1, 2, 3, 4, 5}, .50)).isEqualTo(3);
        assertThat(BenchmarkRunner.percentile(new long[] {1, 2, 3, 4, 5}, .99)).isEqualTo(5);
        assertThatIllegalArgumentException().isThrownBy(() -> BenchmarkRunner.percentile(new long[0], .50));
    }
    @Test
    void configurationRejectsAmbiguousOrUnsafeInputs() {
        var env = Map.of("BENCH_PASSWORD", "secret");
        for (String option : new String[] {"--requests=0", "--concurrency=1,1", "--scenarios=write", "--nodes=../x", "--warmup=0"})
            assertThatIllegalArgumentException().isThrownBy(() -> RepositoryBenchmark.Config.parse(new String[] {"--scenarios=list", option}, env));
        assertThatIllegalArgumentException().isThrownBy(() -> RepositoryBenchmark.Config.parse(new String[] {}, Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> RepositoryBenchmark.Config.parse(new String[] {"--scenarios=list"}, Map.of("BENCH_PASSWORD", "secret", "BENCH_REST_URL", "http://user:secret@localhost/alfresco")));
        var config = RepositoryBenchmark.Config.parse(new String[] {"--nodes=doc", "--protocols=grpc", "--scenarios=text"}, env);
        assertThat(config.scenarios()).containsExactly("text");
    }
}
