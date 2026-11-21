package org.saidone.protoalf.platform;

import lombok.val;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.alfresco.service.ServiceRegistry;
import java.net.InetSocketAddress;
import java.io.File;
import java.util.concurrent.*;

public final class GrpcServer {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GrpcServer.class);
    private final ServiceRegistry services;
    private final org.alfresco.repo.rendition2.SynchronousTransformClient transforms;
    private final boolean enabled;
    private final String host, certificate, privateKey;
    private final int port, threads, maxMessageSize;
    private Server server;
    private ThreadPoolExecutor executor;
    public GrpcServer(ServiceRegistry services, boolean enabled, String host, int port,
                      int threads, int maxMessageSize, String certificate, String privateKey) {
        this(services, null, enabled, host, port, threads, maxMessageSize, certificate, privateKey);
    }
    /** @param transforms synchronous transforms used by GetContent to extract text, null when not available */
    public GrpcServer(ServiceRegistry services, org.alfresco.repo.rendition2.SynchronousTransformClient transforms, boolean enabled,
                      String host, int port, int threads, int maxMessageSize, String certificate, String privateKey) {
        this.services=services; this.transforms=transforms; this.enabled=enabled; this.host=host; this.port=port;
        this.threads=threads; this.maxMessageSize=maxMessageSize; this.certificate=certificate; this.privateKey=privateKey;
    }
    public synchronized void start() throws Exception {
        if (!enabled || server != null) return;
        if (threads < 1 || port < 1 || port > 65535 || maxMessageSize < 1024) throw new IllegalArgumentException("Invalid gRPC configuration");
        val tls = !certificate.isBlank() && !privateKey.isBlank();
        if (certificate.isBlank() != privateKey.isBlank()) throw new IllegalArgumentException("TLS requires certificate and key");
        if (!tls && !java.net.InetAddress.getByName(host).isLoopbackAddress())
            throw new IllegalArgumentException("TLS is required for non-loopback gRPC listeners");
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1024), new ThreadPoolExecutor.AbortPolicy());
        try {
            val tickets = new TicketInterceptor(services.getAuthenticationService());
            val builder = NettyServerBuilder.forAddress(new InetSocketAddress(host, port)).executor(executor)
                    .maxInboundMessageSize(maxMessageSize).maxConcurrentCallsPerConnection(100)
                    // Intercepted too: the interceptor lets Login through and rejects anything else without a ticket.
                    .addService(ServerInterceptors.intercept(new GrpcAuthenticationService(services.getAuthenticationService()), tickets))
                    .addService(ServerInterceptors.intercept(new GrpcNodeService(services, transforms), tickets));
            if (tls) builder.useTransportSecurity(new File(certificate), new File(privateKey));
            server = builder.build().start();
            LOG.info("Alfresco gRPC listening on {}:{} (TLS: {})", host, port, tls);
        } catch (Exception e) { executor.shutdownNow(); executor=null; throw e; }
    }
    public synchronized void stop() {
        try {
            if (server != null && !server.shutdown().awaitTermination(10, TimeUnit.SECONDS)) server.shutdownNow();
        } catch (InterruptedException e) { if (server != null) server.shutdownNow(); Thread.currentThread().interrupt();
        } finally { if (executor != null) executor.shutdownNow(); server=null; executor=null; }
    }
}
