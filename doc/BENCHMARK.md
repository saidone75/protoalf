# REST/gRPC repository benchmark

Opt-in, read-only end-to-end benchmark in the client's test sources. It is not packaged in the client JAR and does not run during normal Maven tests or CI. The ordinary `*Test` classes verify the harness against local fake HTTP/gRPC servers; their timings are not ACS performance results.

Requires JDK 21+, Maven and a running ACS instance with the matching protoalf platform module. The runner does not start Docker, create/edit/delete fixture nodes, or clear caches.

## Run

From the repository root, start the existing development stack if needed:

```bash
./run-docker.sh build_start
```

Use a stable corpus of existing document IDs and a folder the same user can read through both APIs. For meaningful listing tests, use a folder containing at least `--page-size` entries. For extraction tests, include PDF/Office documents as well as a plain-text control. Use separate runs for different document formats/sizes and ACL profiles.

Configure endpoints and credentials; credentials are not command-line options or report fields:

```bash
export BENCH_USERNAME=mcp
read -rsp 'Alfresco password: ' BENCH_PASSWORD
export BENCH_PASSWORD
export BENCH_REST_URL=http://localhost:8080/alfresco
export BENCH_GRPC_HOST=localhost
export BENCH_GRPC_PORT=9090

# Use the current stack's CA, rather than an unrelated example certificate.
docker compose -f docker/docker-compose.yml exec -T acs cat /grpc-certs/ca.crt > /tmp/protoalf-benchmark-ca.crt
export BENCH_GRPC_CA=/tmp/protoalf-benchmark-ca.crt
```

`BENCH_GRPC_AUTHORITY` optionally overrides the verified TLS hostname. A missing/empty CA selects plaintext, which the platform accepts only on loopback. HTTPS REST endpoints use the JDK trust store. The default stack uses plaintext REST and TLS gRPC: record this difference when interpreting results.

```bash
./protoalf-client/src/test/benchmark/run.sh \
  --nodes=DOCUMENT_ID_1,DOCUMENT_ID_2 \
  --folder=FOLDER_ID \
  --scenarios=node,list,download \
  --concurrency=1,4,8 \
  --requests=200 --warmup=20 --repetitions=3 \
  --output=protoalf-client/target/benchmark/baseline-reads
```

Measure the current repeated-extraction path separately, before implementing a cache:

```bash
./protoalf-client/src/test/benchmark/run.sh \
  --nodes=PDF_DOCUMENT_ID \
  --scenarios=text --protocols=grpc \
  --text-chunk-bytes=100000 \
  --concurrency=1,4 --requests=30 --warmup=5 --repetitions=3 \
  --output=protoalf-client/target/benchmark/baseline-text
```

Repeat with larger text slices (up to `2097152`) to quantify the effect of repeated extraction/RPCs. Keep the corpus, user, ACS settings, chunk sizes and hardware identical for before/after cache comparisons. Plain-text controls help separate extraction work from paging/transport overhead.

Run `run.sh --help` for all options. `--requests` and `--warmup` count total operations per protocol/scenario/concurrency/repetition cell, not per worker. Existing result directories must be empty; use a new output directory for each run. Build artifacts and default results remain under ignored `target/` directories.

## Workloads and comparability

| Scenario | REST | gRPC | One timed operation |
|---|---|---|---|
| `node` | Get node with `include=properties,aspectNames` | `NodeService.GetNode` | Retrieve/decode a full node response |
| `list` | First children page, name ascending, including properties/aspects | `FileFolderService.List`, same page size | Retrieve/decode one page; units are returned nodes |
| `download` | Original `/nodes/{id}/content` | `ContentService.DownloadContent` | Consume the entire binary stream into a counting sink |
| `text` | Not run | Repeated `ContentService.GetReader(text=true)` | Read the entire extracted UTF-8 document across all pages |

The runner authenticates both protocols outside measurements, reuses tickets/connections, cycles deterministically through the supplied node IDs, warms each cell, and reverses protocol order on alternating repetitions. Authentication renewal during a timed operation is included if it occurs.

When both protocols are selected, preflight checks compare node IDs, names, types and non-system aspects, ordered folder page identities, and complete binary SHA-256/size fingerprints. Preflight aborts on mismatches. Binary hashing occurs only in preflight; timed downloads stream without retaining entire files or hashing them. No content or credentials are written to results. Keep the corpus unchanged during the run; there is no repository snapshot or per-request integrity hash.

The response profiles perform comparable full-node operations, but are not byte-identical: REST excludes some system metadata and exposes some fields differently, while gRPC currently always maps a complete snapshot. Preflight does not claim that every property has an equivalent representation. REST and gRPC may also differ in repository-side ancillary work, such as download activity recording. This is a service-stack baseline, not proof that one wire format alone is faster. Selective responses can later be measured as a separate profile.

`text` is deliberately gRPC-only: the public REST original-content endpoint is not equivalent to synchronous text extraction. A rendition download must not be presented as the same workload without accounting for rendition creation and reuse. Its latency and byte throughput describe a whole extracted document, not a single slice; gRPC context deadlines cover the entire document operation.

## Results

Each run writes:

1. `report.json`: configuration (without secrets), client Git revision/runtime, corpus fingerprints, per-cell counts, errors, elapsed time, successful operations/s, units/s, content MiB/s and successful latency mean/p50/p95/p99/max in milliseconds. Non-content scenarios report zero content bytes; these are not wire-byte measurements.
2. `samples.csv`: every timed attempt, including failures, latency in nanoseconds, units, content bytes and a sanitized error category. Warmup samples are excluded.

Percentiles use nearest rank and include successful requests only; failed latency samples remain in CSV. The process exits unsuccessfully on preflight, warmup or measured errors, and records an aborted/error outcome. A partial report retains completed cells. Do not treat low successful latency under heavy failure as an improvement. Compare repetitions individually and keep all raw samples.

The load generator is closed-loop: each worker waits for its operation to finish before starting another. Worker startup is excluded from elapsed measurement, and latency excludes queueing before a worker. This is throughput under fixed concurrency, not an open-loop arrival-rate/SLO test; coordinated omission is not corrected.

## Cache and server measurements

These are warm-cache runs: preflight and warmup already touch the corpus. `--label` is only an annotation and does not reset any cache. Cold-cache experiments require a separately controlled procedure for ACS, database, content store and OS caches; do not compare the first protocol's cold run with the second protocol's warmed run.

Save the ACS/platform revision, user permission profile, fixture sizes/formats, JVM/container settings, host resources, and any background workload alongside results. The runner records the client revision, not the deployed server revision. Observe ACS/database/transform CPU, SQL activity, GC, storage I/O and queue saturation independently; this harness does not infer them from client latency. For example, run `docker stats` in another terminal and use the same server instrumentation for every comparison.

No live baseline is checked into this repository. Search, mutations, bulk/batch operations, version histories, automatic fixture preparation, cold-cache orchestration and server profiling are follow-up work. Establish this read-only baseline first, then extend scenarios while preserving equivalent response fields, permission checks and transaction semantics.
