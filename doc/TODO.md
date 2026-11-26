# Code review TODO

Review date: 2026-11-26. Only outstanding work is listed.

1. **Restrict development services to localhost by default.**

    Source: `docker/docker-compose.yml`.

    The unauthenticated MCP endpoint uses `8090:8090`; the active Java debugger uses `8888:8888`. These mappings do not restrict access to localhost, despite the stack being intended for local development. Bind development ports to `127.0.0.1` and enable the debugger through an explicit profile.

2. **Fix UTF-8 slice boundaries.**

    Source: `protoalf-platform/.../ContentFetcher.java`, `slice`.

    Reproduced: reading text beginning with `è` using `maxBytes=1` returns an incomplete UTF-8 byte sequence, decoded as `�`. When the detected boundary is zero, the implementation keeps the original byte cutoff. Define behavior for limits smaller than a character and offsets inside a character. Avoid empty responses that cannot advance the caller. Add tests covering accented characters, emoji, small limits, arbitrary offsets, and continuation across slices.

3. **Reuse extracted text across paged reads.**

    Source: `protoalf-platform/.../ContentFetcher.java`, `toText`.

    Every read can transform the entire PDF or Office document before applying the requested byte offset. Reading many slices can repeat the full transformation many times. Reuse extraction results with bounded retention, keyed by document/content version and the relevant caller permission context. Test invalidation after content changes and prevent reuse across unauthorized callers.

4. **Process trashcan purges in bounded batches.**

    Source: `protoalf-platform/.../GrpcNodeArchiveService.java`, `purgeAllArchivedNodes`.

    The current implementation collects every reference in memory and purges them in one transaction. For large trashcans, use a background job with bounded batches, progress reporting, and defined cancellation/retry behavior. Account for concurrent deletions and pagination changes; validate behavior against a live repository.

5. **Preserve configured stub options during uploads.**

    Source: `protoalf-client/.../ContentServiceClient.java`, `uploadContent`.

    Uploads create a new asynchronous stub from `stub.getChannel()`, losing options attached to the original stub, such as compression or per-call credentials. Make unary and streaming call configuration consistent and add regression tests for configured call options.

6. **Add live ACS integration tests to CI.**

    Sources: `.github/workflows/ci.yml` and module test suites.

    Existing coverage primarily uses mocks and in-process gRPC servers. Exercise real permissions, authentication context isolation, rollback, frozen versions, and text transformations. Include the regressions above and validate the packaged/shaded platform module in its actual runtime.

7. **Make protocol compatibility explicit.**

    Source: `proto/protoalf/v1/alfresco.proto`.

    Former property field numbers are correctly reserved, but incompatible clients and servers still use `protoalf.v1`. Old property fields can be ignored as unknown fields instead of producing a clear incompatibility error. Define a versioning or compatibility negotiation policy and reject incompatible writes before mutation. Add automated schema compatibility checks and tests with mismatched client/server contracts.

8. **Extract and document property conversion.**

    Sources: `protoalf-platform/.../NodeWriter.java`, `NodeMapper.java`, and the typed property API.

    Keep the single typed map and explicit scalar/list distinction, but move conversion and validation into a dedicated component. Document supported Alfresco datatypes and representations that cannot round-trip without information loss, especially multilingual text and additional dictionary types. Test read/write symmetry and explicit rejection of unsupported types.

9. **Make exact listing counts optional.**

    Source: `protoalf-platform/.../GrpcFileFolderService.java`, `list`.

    Every listing requests an exact child count, even when a caller only needs the current page and continuation flag. Make exact counting optional and measure listing/mapping costs for large folders. Define and test pagination behavior when nodes are added, deleted, or renamed between pages.

10. **Consolidate startup scripts and escape provisioning JSON correctly.**

    Sources: `run.sh`, `run-docker.sh`, their Windows equivalents, and `docker/mcp-user.sh`.

    Consolidate overlapping startup entry points and keep failure handling consistent. Provisioning currently interpolates environment variables directly into JSON. Passwords containing quotes or backslashes can produce invalid requests. Use proper JSON encoding and test special characters in configurable values.
