package org.saidone.protoalf.client;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import lombok.val;

import java.io.File;
import java.io.IOException;

/** Creates channels to the protoalf-platform module. */
public final class AlfrescoGrpcChannels {

    private AlfrescoGrpcChannels() {
    }

    /**
     * A channel to {@code host:port}. It connects lazily, on the first call; shut it down when done.
     *
     * @param trustCertificate  PEM CA certificate used to verify the server; null or blank means plaintext
     *                          (the Alfresco module only accepts plaintext on loopback addresses)
     * @param authorityOverride TLS host name to verify instead of {@code host}; null or blank to use {@code host}
     */
    public static ManagedChannel create(String host, int port, String trustCertificate, String authorityOverride) throws IOException {
        val credentials = isBlank(trustCertificate)
                ? InsecureChannelCredentials.create()
                : TlsChannelCredentials.newBuilder().trustManager(new File(trustCertificate)).build();
        val builder = Grpc.newChannelBuilderForAddress(host, port, credentials);
        if (!isBlank(authorityOverride)) builder.overrideAuthority(authorityOverride);
        return builder.build();
    }

    /** A plaintext channel: only for an Alfresco module listening on a loopback address. */
    public static ManagedChannel plaintext(String host, int port) throws IOException {
        return create(host, port, null, null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
