#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../../.."

# Test classes are not packaged into the client JAR and the benchmark is never run by Surefire.
mvn -q -pl protoalf-client -am test-compile
mvn -q -f protoalf-client/pom.xml org.apache.maven.plugins:maven-dependency-plugin:3.1.1:build-classpath \
    -DincludeScope=test -Dmdep.outputFile=target/benchmark-classpath.txt
BENCH_GIT_REVISION=$(git rev-parse HEAD)
export BENCH_GIT_REVISION
benchmark_classpath="protoalf-client/target/test-classes:protoalf-client/target/classes:$(cat protoalf-client/target/benchmark-classpath.txt)"
exec java -cp "$benchmark_classpath" org.saidone.protoalf.client.benchmark.RepositoryBenchmark "$@"
