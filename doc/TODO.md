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

8. **Make exact listing counts optional.**

    Source: `protoalf-platform/.../GrpcFileFolderService.java`, `list`.

    Every listing requests an exact child count, even when a caller only needs the current page and continuation flag. Make exact counting optional and measure listing/mapping costs for large folders. Define and test pagination behavior when nodes are added, deleted, or renamed between pages.

9. **Consolidate startup scripts and escape provisioning JSON correctly.**

    Sources: `run.sh`, `run-docker.sh`, their Windows equivalents, and `docker/mcp-user.sh`.

    Consolidate overlapping startup entry points and keep failure handling consistent. Provisioning currently interpolates environment variables directly into JSON. Passwords containing quotes or backslashes can produce invalid requests. Use proper JSON encoding and test special characters in configurable values.

## Performance optimization

Assessment date: 2026-11-27. The goal is to maximize Alfresco throughput and reduce latency compared with equivalent REST operations. These priorities come from static code inspection; expected benefits must be validated on a live ACS instance. Preserve repository permissions, transaction semantics and bounded resource usage.

1. **High priority: support selective node responses.**

    Sources: `protoalf-platform/.../NodeMapper.java`, `GrpcFileFolderService.java`, `GrpcSearchService.java`, and the Protobuf contract.

    Listings and searches always map full node snapshots, fetching type, properties, aspects and the primary parent, including a parent permission check for every result. Add explicit field selection and a lightweight response mode for bulk consumers that only need references and names. Skip repository lookups and property conversion for omitted fields. Measure service invocations, SQL queries, allocations and response sizes separately: Alfresco caches mean service calls do not necessarily correspond to SQL queries.

2. **High priority: reuse information already obtained during listing.**

    Sources: `protoalf-platform/.../GrpcFileFolderService.java`, `NodeMapper.java`.

    Listing obtains `FileInfo` objects but passes only their references to the mapper, which reads their metadata again. Reuse available listing data where it satisfies the requested response fields. Cache repeated parent permission decisions within the current request and caller context; do not assume that a listed parent is always the primary parent. Combine this work with code review item 8, which makes exact counts optional. Measure large-folder listings with and without exact totals.

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

9. **Establish a reproducible REST versus gRPC benchmark.**

    Sources: benchmark tooling, live ACS test infrastructure and CI reporting.

    Compare equivalent operations with the same user permissions, response fields, content, cache conditions and consistency requirements. Cover metadata reads, large-folder listing, search, writes, bulk workloads, binary transfers and text extraction. Record nodes/s, MB/s, p50/p95/p99 latency, errors, SQL activity, CPU, allocations/GC and queue saturation across concurrency levels, with cold and warm caches. Separate transport savings from reduced repository work; use the baseline to validate the priorities above and detect regressions.
