# Code review TODO

Review date: 2026-11-26. Only outstanding work is listed.

1. **Restrict development services to localhost by default.**

    Source: `docker/docker-compose.yml`.

    The unauthenticated MCP endpoint uses `8090:8090`; the active Java debugger uses `8888:8888`. These mappings do not restrict access to localhost, despite the stack being intended for local development. Bind development ports to `127.0.0.1` and enable the debugger through an explicit profile.

2. **Reuse extracted text across paged reads.**

    Source: `protoalf-platform/.../ContentFetcher.java`, `toText`.

    Every read can transform the entire PDF or Office document before applying the requested byte offset. Reading many slices can repeat the full transformation many times. Reuse extraction results with bounded retention, keyed by document/content version and the relevant caller permission context. Test invalidation after content changes and prevent reuse across unauthorized callers.

3. **Process trashcan purges in bounded batches.**

    Source: `protoalf-platform/.../GrpcNodeArchiveService.java`, `purgeAllArchivedNodes`.

    The current implementation collects every reference in memory and purges them in one transaction. For large trashcans, use a background job with bounded batches, progress reporting, and defined cancellation/retry behavior. Account for concurrent deletions and pagination changes; validate behavior against a live repository.

4. **Preserve configured stub options during uploads.**

    Source: `protoalf-client/.../ContentServiceClient.java`, `uploadContent`.

    Uploads create a new asynchronous stub from `stub.getChannel()`, losing options attached to the original stub, such as compression or per-call credentials. Make unary and streaming call configuration consistent and add regression tests for configured call options.

5. **Add live ACS integration tests to CI.**

    Sources: `.github/workflows/ci.yml` and module test suites.

    Existing coverage primarily uses mocks and in-process gRPC servers. Exercise real permissions, authentication context isolation, rollback, frozen versions, and text transformations. Include the regressions above and validate the packaged/shaded platform module in its actual runtime.

6. **Make protocol compatibility explicit.**

    Source: `proto/protoalf/v1/alfresco.proto`.

    Former property field numbers are correctly reserved, but incompatible clients and servers still use `protoalf.v1`. Old property fields can be ignored as unknown fields instead of producing a clear incompatibility error. Define a versioning or compatibility negotiation policy and reject incompatible writes before mutation. Add automated schema compatibility checks and tests with mismatched client/server contracts.

7. **Extract and document property conversion.**

    Sources: `protoalf-platform/.../NodeWriter.java`, `NodeMapper.java`, and the typed property API.

    Keep the single typed map and explicit scalar/list distinction, but move conversion and validation into a dedicated component. Document supported Alfresco datatypes and representations that cannot round-trip without information loss, especially multilingual text and additional dictionary types. Test read/write symmetry and explicit rejection of unsupported types.

8. **Exercise listing pagination under concurrent mutations.**

    Source: `protoalf-platform/.../GrpcFileFolderService.java`, `list`.

    Exact counts are now optional through count_total, with exact counting preserved for existing callers and totalItems=-1 when disabled. Paging offsets, page sizes and continuation are covered by unit tests, and the independent-page semantics are documented. Add live integration cases with concurrent creates, deletes and renames and heterogeneous ACLs to exercise page shifts, omissions and repeated entries across requests; the API does not provide snapshot isolation.

9. **Consolidate startup scripts and escape provisioning JSON correctly.**

    Sources: `run.sh`, `run-docker.sh`, their Windows equivalents, and `docker/mcp-user.sh`.

    Consolidate overlapping startup entry points and keep failure handling consistent. Provisioning currently interpolates environment variables directly into JSON. Passwords containing quotes or backslashes can produce invalid requests. Use proper JSON encoding and test special characters in configurable values.

## Performance optimization

Assessment date: 2026-11-27. The goal is to maximize Alfresco throughput and reduce latency compared with equivalent REST operations. These priorities come from static code inspection; expected benefits must be validated on a live ACS instance. Preserve repository permissions, transaction semantics and bounded resource usage.

1. **High priority: support selective node responses.**

    Sources: `protoalf-platform/.../NodeMapper.java`, `GrpcFileFolderService.java`, `GrpcSearchService.java`, and the Protobuf contract.

    Listings and searches still return full node snapshots. Listings now reuse FileInfo type/properties and deduplicate parent permission checks within each page; search still maps individual references. Add explicit field selection and a lightweight response mode for bulk consumers that only need references and names. Skip repository lookups and property conversion for omitted fields. Measure service invocations, SQL queries, allocations and response sizes separately: Alfresco caches mean service calls do not necessarily correspond to SQL queries.

2. **Follow-up: profile optimized listings with heterogeneous ACLs.**

    Sources: `protoalf-platform/.../GrpcFileFolderService.java`, `NodeMapper.java`.

    Implemented: listings reuse type/properties already returned in secured FileInfo objects, preserve complete snapshots and cache both grants and denials for actual primary parents only within one page transaction attempt. Secondary associations still resolve their real primary parent; permission decisions are not shared between users, requests or retries. Existing calls retain exact totals; count_total=false skips counting and returns -1. Unit tests cover full snapshot equality, permission context, denied parents, transaction retries and client wire compatibility.

    The runner now prepares a stable 1,000-document folder and measures full-response listing pages. Live before/after exact-count runs are retained under ignored benchmark-results/listing-before-20261128 and listing-after-20261128; list-no-count is reported separately as a gRPC-only workload. Remaining work: profile SQL, allocations and ACL filtering on larger folders with realistic mixed permissions. Service call reduction alone does not imply the same reduction in database queries.

    Observed on 2026-11-28 with 100-node pages and three repetitions: median gRPC exact-count throughput increased by 28.4% at concurrency 1 and 39.6% at concurrency 4. The unchanged REST control improved by 11.0% and 6.5%, so not all variation can be attributed to the mapper. Skipping counts added 17.2% and 13.2% over the optimized exact-count profile. All timed pages returned 100 entries without errors. These local warm-cache results are exploratory, not statistical guarantees; comparison.md in the after-run directory records the details.

3. **High priority: avoid repeated text extraction and offer text streaming.**

    Sources: `protoalf-platform/.../ContentFetcher.java`, `GrpcContentService.java`.

    Implement the bounded extraction reuse described in code review item 2. Additionally, consider streaming extracted UTF-8 text so sequential consumers can use one extraction and one RPC instead of repeatedly reopening and paging the representation. Preserve character boundaries, cancellation and backpressure; recheck access for every new request and invalidate cached output when the content changes.

4. **High priority for bulk workloads: add bounded batch operations.**

    Sources: `proto/protoalf/v1/alfresco.proto`, platform service adapters and Java clients.

    Reading or updating many nodes currently requires individual RPCs, ticket validations and transactions. Add batch reads and writes with bounded request/response sizes and explicit transaction boundaries. Define ordering, per-item errors, partial success and retry semantics before implementation. Benchmark batch sizes and concurrency; avoid a single transaction spanning an entire import or export.

5. **Medium-high priority: make write responses lightweight.**

    Sources: `protoalf-platform/.../GrpcNodeService.java`, `GrpcFileFolderService.java`, `GrpcContentService.java`, and `NodeMapper.java`.

    Creation, metadata changes and content writes map and return complete node snapshots. Let callers request only the resulting reference or an acknowledgement when they do not need the full node. Reuse selective response mapping to avoid unnecessary reads and serialization after mutations. Preserve the rule that responses are emitted only after successful transaction completion.

6. **Measure on large files: reduce upload staging I/O.**

    Sources: `protoalf-platform/.../GrpcContentService.java`, `NodeWriter.java`.

    Uploads write a temporary spool, then reread it to write to the content store. Measure the additional temporary-file I/O and make staging placement configurable if it is a bottleneck. Evaluate a more direct content-store staging path only if it preserves repeatable input for transaction retries, rollback behavior and cleanup on cancellation or failure. Bound concurrent uploads and temporary storage consumption.

7. **Medium priority: separate concurrency limits by workload.**

    Sources: `protoalf-platform/.../GrpcServer.java`, `ContentDownloads.java`, `GrpcContentService.java`, and `ContentFetcher.java`.

    Repository RPCs share a worker pool, while synchronous text transformations can occupy workers needed by short metadata calls. Downloads already have a dedicated bounded executor and transport backpressure, but their concurrency is tied to the repository thread setting. Make repository, transfer and transformation limits independently configurable where appropriate. Size them against the database connection pool, storage and transform capacity; validate mixed-workload latency and overload behavior rather than simply increasing thread counts.

8. **Bound history expansion and maintenance transactions.**

    Sources: `protoalf-platform/.../GrpcVersionService.java`, `GrpcNodeArchiveService.java`.

    Version history always returns every version with a complete frozen-node snapshot. Add a metadata-only mode and optional snapshot expansion, and investigate bounded history retrieval for documents with many versions. For trashcan purges, implement the bounded background processing described in code review item 3 instead of collecting all references and purging them in one transaction.

9. **Extend the REST versus gRPC benchmark and server profiling (partially completed).**

    Sources: benchmark tooling, live ACS test infrastructure and CI reporting.

    Completed: the read-only harness in `protoalf-client/src/test/benchmark/run.sh` supports full-node reads, first-page listings, binary transfers and a separate gRPC-only paged-text scenario, with preflight checks, warmup, concurrency, repetitions, raw samples and JSON summaries. It is tested against fake HTTP/gRPC servers, and live ACS runs have exercised node reads, listings and downloads. Instructions are in `doc/BENCHMARK.md`.

    Completed: `./run-benchmark.bb` builds/tests the project, recreates Docker, prepares stable 10 KiB/1 MiB/100 MiB fixtures and a populated listing folder, writes aligned terminal, Markdown and JSON reports and stops containers while retaining volumes. The full lifecycle has been validated against live ACS. It compares absolute throughput (MiB/s for downloads, nodes/s for listings) and p95 against a saved baseline and can fail on a configurable regression threshold. Exact-count full-response listings and the gRPC-only no-count profile are separate workloads. Results and raw samples are kept in ignored `benchmark-results/` directories; no baseline is committed. Runs currently use warm caches on localhost, with REST HTTP and gRPC TLS.

    Next step: keep the unchanged exact-count full-response profile as the reference for listing comparisons and measure selective responses separately. Before implementing extraction caching, establish a live PDF/Office text-extraction baseline: binary download results do not measure transformation cost. Add automated node-read, search, write, upload, bulk/batch and version-history workloads as the corresponding optimizations are introduced.

    Outstanding: controlled cold-cache runs, detailed SQL/GC/allocation and queue profiling, and performance result reporting in CI. Docker CPU/memory/I/O sampling is already available, but does not replace that profiling. Repeated runs have shown variation, so confirm suspected regressions on the same idle host rather than treating a single threshold failure as proof.

    Compare equivalent operations with the same user permissions, response fields, content, cache conditions and consistency requirements. Cover metadata reads, large-folder listing, search, writes, bulk workloads, binary transfers and text extraction. Record nodes/s, MB/s, p50/p95/p99 latency, errors, SQL activity, CPU, allocations/GC and queue saturation across concurrency levels, with cold and warm caches. Separate transport savings from reduced repository work; use the baseline to validate the priorities above and detect regressions.
