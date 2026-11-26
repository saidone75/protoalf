package org.saidone.protoalf.platform;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.alfresco.repo.rendition2.SynchronousTransformClient;
import org.alfresco.service.ServiceRegistry;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.*;

/**
 * Owns the lifecycle of the embedded Alfresco gRPC listener.
 *
 * <p>The module's Spring context invokes {@link #start} after repository services are available and {@link
 * #stop} during shutdown. One bounded worker pool serves all registered repository adapters. Each adapter uses
 * the same {@link TicketInterceptor}; only credential authentication bypasses the ticket check.
 *
 * <p>A certificate and private key enable server-side TLS. Plaintext listeners are restricted to loopback
 * addresses. Lifecycle methods are synchronized and repeated start/stop calls are safe.
 */
@Slf4j
public final class GrpcServer {

    /**
     * Repository services used under the caller's transaction and authentication context.
     */
    private final ServiceRegistry services;

    /**
     * Optional synchronous transform client passed to the content service.
     */
    private final SynchronousTransformClient transforms;

    /**
     * Whether start is allowed to create a listener.
     */
    private final boolean enabled;

    /**
     * Bind hostname and TLS certificate/private-key paths; empty paths select plaintext.
     */
    private final String host, certificate, privateKey;

    /**
     * Listener port, worker count and maximum inbound gRPC message size in bytes.
     */
    private final int port, threads, maxMessageSize;

    /**
     * Active listener, or null when stopped or not yet started.
     */
    private Server server;

    /**
     * Bounded worker pool owned by this listener and released during shutdown.
     */
    private ThreadPoolExecutor executor;

    /**
     * Creates a listener configuration; no network socket or worker pool is allocated until start is called.
     *
     * @param services Alfresco service registry; repository work uses its secured services
     * @param enabled whether the listener should start when requested
     * @param host bind hostname or IP address; plaintext requires a resolved loopback address
     * @param port TCP port in 1..65535
     * @param threads positive worker count for the bounded execution pool
     * @param maxMessageSize maximum inbound message size in bytes, at least 1024
     * @param certificate PEM certificate path, or blank for plaintext
     * @param privateKey PEM private-key path, or blank for plaintext
     */
    public GrpcServer(ServiceRegistry services, boolean enabled, String host, int port, int threads, int maxMessageSize, String certificate, String privateKey) {
        this(services, null, enabled, host, port, threads, maxMessageSize, certificate, privateKey);
    }

    /**
     * Creates a listener configuration; no network socket or worker pool is allocated until start is called.
     *
     * @param services Alfresco service registry; repository work uses its secured services
     * @param transforms synchronous text-transform client, or null to disable non-plain-text extraction
     * @param enabled whether the listener should start when requested
     * @param host bind hostname or IP address; plaintext requires a resolved loopback address
     * @param port TCP port in 1..65535
     * @param threads positive worker count for the bounded execution pool
     * @param maxMessageSize maximum inbound message size in bytes, at least 1024
     * @param certificate PEM certificate path, or blank for plaintext
     * @param privateKey PEM private-key path, or blank for plaintext
     */
    public GrpcServer(ServiceRegistry services, SynchronousTransformClient transforms, boolean enabled, String host, int port, int threads, int maxMessageSize, String certificate, String privateKey) {
        this.services = services;
        this.transforms = transforms;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.threads = threads;
        this.maxMessageSize = maxMessageSize;
        this.certificate = certificate;
        this.privateKey = privateKey;
    }

    /**
     * Starts the listener unless disabled or already running. Validates configuration, allocates a bounded
     * worker pool and registers every repository adapter with ticket interception.
     *
     * <p>TLS requires both a certificate and key. Without TLS, the resolved bind address must be loopback.
     * Startup failures after executor allocation shut down that executor before propagating the error.
     *
     * @throws IllegalArgumentException if listener limits or TLS settings are invalid, or plaintext binds
     *     outside loopback
     * @throws Exception if address resolution, TLS setup or listener startup fails
     */
    public synchronized void start() throws Exception {
        if (!enabled || server != null)
            return;
        if (threads < 1 || port < 1 || port > 65535 || maxMessageSize < 1024)
            throw new IllegalArgumentException("Invalid gRPC configuration");
        val tls = !certificate.isBlank() && !privateKey.isBlank();
        if (certificate.isBlank() != privateKey.isBlank())
            throw new IllegalArgumentException("TLS requires certificate and key");
        if (!tls && !InetAddress.getByName(host).isLoopbackAddress())
            throw new IllegalArgumentException("TLS is required for non-loopback gRPC listeners");
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1024), new ThreadPoolExecutor.AbortPolicy());
        try {
            val tickets = new TicketInterceptor(services.getAuthenticationService());
            val calls = new CallLoggingInterceptor();
            // Intercepted too: the interceptor lets Authenticate through and rejects anything else without a ticket.
            val builder = NettyServerBuilder.forAddress(new InetSocketAddress(host, port)).executor(executor).maxInboundMessageSize(maxMessageSize).maxConcurrentCallsPerConnection(100);
            for (val service : List.of(new GrpcAuthenticationService(services.getAuthenticationService()), new GrpcNodeService(services), new GrpcFileFolderService(services), new GrpcContentService(services, transforms), new GrpcVersionService(services), new GrpcSearchService(services), new GrpcDictionaryService(services))) {
                // The last interceptor runs first: call logging wraps ticket validation, so rejected calls are logged too.
                builder.addService(ServerInterceptors.intercept(service, tickets, calls));
            }
            if (tls)
                builder.useTransportSecurity(new File(certificate), new File(privateKey));
            server = builder.build().start();
            log.info("Alfresco gRPC listening on {}:{} (TLS: {})", host, port, tls);
        } catch (Exception e) {
            executor.shutdownNow();
            executor = null;
            throw e;
        }
    }

    /**
     * Stops the listener and its worker pool. Allows up to ten seconds for graceful server termination, then
     * forces shutdown. Restores the interrupt flag if interrupted and clears lifecycle state so another start
     * is possible.
     */
    public synchronized void stop() {
        try {
            if (server != null && !server.shutdown().awaitTermination(10, TimeUnit.SECONDS))
                server.shutdownNow();
        } catch (InterruptedException e) {
            if (server != null)
                server.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            if (executor != null)
                executor.shutdownNow();
            server = null;
            executor = null;
        }
    }
}
