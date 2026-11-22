/**
 * Reusable export utilities built on the protoalf Java service clients.
 *
 * <p>{@link BulkExporter} writes repository folders, metadata and historical document content in the
 * Alfresco Bulk Filesystem Import layout. Applications supply service clients or a shared channel,
 * ticket provider and deadline. This module has no dependency on Spring, MCP or the Alfresco platform runtime.
 * Callers own the channel and decide when and where an export runs.
 */
package org.saidone.protoalf.bulkexporter;
