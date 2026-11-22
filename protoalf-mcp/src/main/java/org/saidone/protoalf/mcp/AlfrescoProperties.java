package org.saidone.protoalf.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Connection settings for the Alfresco repository.
 *
 * @param username user the MCP server acts as: every tool call runs with this user's permissions
 * @param password password of {@code username}
 * @param grpc     settings of the gRPC endpoint exposed by the protoalf-platform module
 * @param export   settings of the bulk import exports (alfresco_export_bulk)
 */
@ConfigurationProperties("alfresco")
public record AlfrescoProperties(@DefaultValue("admin") String username, @DefaultValue("admin") String password, @DefaultValue Grpc grpc, @DefaultValue Export export) {

    /**
     * @param host               gRPC host
     * @param port               gRPC port
     * @param trustCertificate   PEM CA certificate used to verify the server; empty means plaintext
     *                           (the Alfresco module only accepts plaintext on loopback addresses)
     * @param authorityOverride  TLS host name to verify instead of {@code host}; empty to use {@code host}
     * @param deadline           per-call deadline
     */
    public record Grpc(@DefaultValue("localhost") String host, @DefaultValue("9090") int port, @DefaultValue("") String trustCertificate, @DefaultValue("") String authorityOverride, @DefaultValue("30s") Duration deadline) {
    }

    /**
     * @param directory directory on the MCP server the exports are written into, one subdirectory each
     * @param deadline  deadline of each call of an export: a download must complete within it, so it is longer
     *                  than the per-call deadline of the tools
     */
    public record Export(@DefaultValue("export") Path directory, @DefaultValue("30m") Duration deadline) {
    }
}
