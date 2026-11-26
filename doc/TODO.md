# Code review TODO

Review date: 2026-11-26. Completion is stated explicitly; other items remain recommendations.

Latest validation: `mvn -o clean package` passed with 116 tests, including seven download flow-control and cancellation tests. A UTF-8 slicing failure was reproduced using temporary probes without changing repository sources. A live Alfresco stack was not started; integration behavior and load-related risks still need validation against ACS.

## 1. High priority

1. **Apply download flow control and cancellation handling — completed.**

2. Sources: `protoalf-platform/.../ContentDownloads.java`, `GrpcContentService.java`, and `GrpcServer.java`.
3. Downloads now check transport readiness before reading/sending, retain at most one unsent chunk, and close the input on cancellation, including cancellation during stream opening or a blocked read.
4. A dedicated bounded I/O executor keeps transfers off repository workers. Active transfers are limited to `protoalf.threads`; excess calls receive `RESOURCE_EXHAUSTED`. Server shutdown cancels transfers and closes the executor.
5. Seven regression tests cover pause/resume, cancellation while paused, blocked reads and stream opening, readiness lost during reads, stream cleanup after failures, concurrency limits, and shutdown. Application buffering is bounded by active-transfer and chunk limits; live ACS/network load testing remains outstanding.

6. **Restrict development services to localhost by default.**

7. Source: `docker/docker-compose.yml`.
8. The unauthenticated MCP endpoint uses `8090:8090`; the active Java debugger uses `8888:8888`. These mappings do not restrict access to localhost, despite the stack being intended for local development.
9. Bind development ports to `127.0.0.1` and enable the debugger through an explicit profile.

## 2. Medium priority

10. **Fix UTF-8 slice boundaries.**

11. Source: `protoalf-platform/.../ContentFetcher.java`, `slice`.
12. Reproduced: reading text beginning with `è` using `maxBytes=1` returns an incomplete UTF-8 byte sequence, decoded as `�`.
13. When the detected boundary is zero, the implementation keeps the original byte cutoff.
14. Define behavior for limits smaller than a character and offsets inside a character. Avoid empty responses that cannot advance the caller.
15. Add tests covering accented characters, emoji, small limits, arbitrary offsets, and continuation across slices.

16. **Reuse extracted text across paged reads.**

17. Source: `protoalf-platform/.../ContentFetcher.java`, `toText`.
18. Every read can transform the entire PDF or Office document before applying the requested byte offset. Reading many slices can repeat the full transformation many times.
19. Reuse extraction results with bounded retention, keyed by document/content version and the relevant caller permission context.
20. Test invalidation after content changes and prevent reuse across unauthorized callers.

21. **Process trashcan purges in bounded batches.**

22. Source: `protoalf-platform/.../GrpcNodeArchiveService.java`, `purgeAllArchivedNodes`.
23. The current implementation collects every reference in memory and purges them in one transaction.
24. For large trashcans, use a background job with bounded batches, progress reporting, and defined cancellation/retry behavior.
25. Account for concurrent deletions and pagination changes; validate behavior against a live repository.

26. **Preserve configured stub options during uploads.**

27. Source: `protoalf-client/.../ContentServiceClient.java`, `uploadContent`.
28. Uploads create a new asynchronous stub from `stub.getChannel()`, losing options attached to the original stub, such as compression or per-call credentials.
29. Make unary and streaming call configuration consistent and add regression tests for configured call options.

## 3. Integration and design

30. **Add live ACS integration tests to CI.**

31. Sources: `.github/workflows/ci.yml` and module test suites.
32. Existing coverage primarily uses mocks and in-process gRPC servers.
33. Exercise real permissions, authentication context isolation, rollback, frozen versions, and text transformations.
34. Include the regressions above and validate the packaged/shaded platform module in its actual runtime.

35. **Make protocol compatibility explicit.**

36. Source: `proto/protoalf/v1/alfresco.proto`.
37. Former property field numbers are correctly reserved, but incompatible clients and servers still use `protoalf.v1`. Old property fields can be ignored as unknown fields instead of producing a clear incompatibility error.
38. Define a versioning or compatibility negotiation policy and reject incompatible writes before mutation.
39. Add automated schema compatibility checks and tests with mismatched client/server contracts.

40. **Extract and document property conversion.**

41. Sources: `protoalf-platform/.../NodeWriter.java`, `NodeMapper.java`, and the typed property API.
42. Keep the single typed map and explicit scalar/list distinction, but move conversion and validation into a dedicated component.
43. Document supported Alfresco datatypes and representations that cannot round-trip without information loss, especially multilingual text and additional dictionary types.
44. Test read/write symmetry and explicit rejection of unsupported types.

45. **Make exact listing counts optional.**

46. Source: `protoalf-platform/.../GrpcFileFolderService.java`, `list`.
47. Every listing requests an exact child count, even when a caller only needs the current page and continuation flag.
48. Make exact counting optional and measure listing/mapping costs for large folders.
49. Define and test pagination behavior when nodes are added, deleted, or renamed between pages.

50. **Consolidate startup scripts and escape provisioning JSON correctly.**

51. Sources: `run.sh`, `run-docker.sh`, their Windows equivalents, and `docker/mcp-user.sh`.
52. Consolidate overlapping startup entry points and keep failure handling consistent.
53. Provisioning currently interpolates environment variables directly into JSON. Passwords containing quotes or backslashes can produce invalid requests.
54. Use proper JSON encoding and test special characters in configurable values.

## 4. Suggested implementation order

55. Download flow control and UTF-8 slicing, each with regression tests.
56. Localhost-only development defaults.
57. Batched purges, extraction reuse, and upload option preservation.
58. Live integration coverage, protocol compatibility checks, conversion cleanup, listing optimization, and script consolidation.
