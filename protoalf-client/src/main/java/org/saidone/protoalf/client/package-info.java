/**
 * Java clients for the repository services exposed by the protoalf platform module.
 *
 * <p>Each service has a matching client and operation names: nodes, file/folder operations, content, versions,
 * search, dictionary models and authentication. Clients exchange Protobuf data objects and require no Spring or
 * MCP runtime. The application owns its channel and can share one {@link AuthenticationServiceClient} as the
 * {@link TicketProvider} for all service clients.
 *
 * <p>Unary calls attach a ticket and deadline, renewing an expired ticket and retrying once when a different
 * ticket becomes available. Downloads retry only before any payload bytes are written; uploads never replay
 * their input. Callers retain ownership of supplied input/output streams.
 *
 * <p>{@link BulkExporter} is an independent utility that composes the specific service clients for exports. Its
 * channel-based constructor applies a common export deadline; its client-based constructor allows applications
 * to provide separately configured clients.
 */
package org.saidone.protoalf.client;
