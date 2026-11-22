/**
 * Adapts Alfresco repository services to authenticated gRPC operations.
 *
 * <h2>Service boundaries</h2>
 * <p>Each {@code Grpc<Service>} corresponds to an Alfresco service: node metadata, file/folder operations,
 * content, version history, search, dictionary lookups and credential authentication. Protobuf messages
 * replace local repository objects. Aggregate node snapshots and updates, materialized content access and
 * streaming transfers are documented remote extensions rather than implementations of Alfresco Java interfaces.
 *
 * <h2>Authentication and transactions</h2>
 * <p>{@link TicketInterceptor} requires an {@code alfresco-ticket} header on
 * every operation except credential authentication. It records the validated username in the gRPC context;
 * repository callbacks run as that user. Both ticket validation and repository execution restore the previous
 * Alfresco thread authentication. Read operations use read-only transactions and mutations use retryable
 * read-write transactions. Unary responses are emitted only after the transaction succeeds.
 *
 * <p>Downloads resolve their content reader inside a transaction, then stream bytes outside it so a retry cannot
 * duplicate output. Uploads spool their input before creating a document. The transactional write reopens the
 * spool for every attempt, and temporary files are cleaned up on completion, cancellation and failure.
 *
 * <h2>References and values</h2>
 * <p>Node arguments accept a full repository reference, a bare workspace node ID, or {@code -root-},
 * {@code -my-} and {@code -shared-}. Aliases resolve as the caller. Qualified property, aspect and type names
 * accept {@code prefix:localName} or expanded {@code {namespace-uri}localName} notation. Read snapshots preserve
 * supported scalar types, collection cardinality and null markers, while exposing content metadata without
 * content-store URLs. Write inputs are text values converted according to dictionary definitions.
 *
 * <h2>Failure delivery</h2>
 * <p>RPC failures are sent to response observers using gRPC statuses; they are not declared as synchronous
 * exceptions on service method signatures. Shared repository error translation uses these mappings:
 * <table>
 * <caption>Repository failures and their transport status</caption>
 * <tr><th scope="col">Condition</th><th scope="col">Status</th></tr>
 * <tr><td>Missing/invalid ticket or rejected credentials</td><td>{@code UNAUTHENTICATED}</td></tr>
 * <tr><td>Repository permission denied</td><td>{@code PERMISSION_DENIED}</td></tr>
 * <tr><td>Unresolved or missing node/model</td><td>{@code NOT_FOUND}</td></tr>
 * <tr><td>Duplicate child name</td><td>{@code ALREADY_EXISTS}</td></tr>
 * <tr><td>Lock or repository integrity constraint</td><td>{@code FAILED_PRECONDITION}</td></tr>
 * <tr><td>Invalid input or cyclic folder operation</td><td>{@code INVALID_ARGUMENT}</td></tr>
 * <tr><td>Unexpected repository failure</td><td>{@code INTERNAL}</td></tr>
 * </table>
 * <p>Explicit status exceptions keep their status. Content extraction and streaming add operation-specific
 * statuses described on the corresponding methods. Internal exception details are omitted from permission and
 * unexpected-failure responses, while diagnostic causes remain available in server logs.
 */
package org.saidone.protoalf.platform;
