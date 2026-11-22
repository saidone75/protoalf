package org.saidone.protoalf.client;

import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import lombok.val;
import org.saidone.protoalf.api.AuthenticationServiceGrpc.AuthenticationServiceBlockingStub;
import org.saidone.protoalf.api.AuthenticationServiceGrpc;
import org.saidone.protoalf.api.LoginRequest;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Client of the AuthenticationService of the gRPC module: exchanges user and password for an Alfresco ticket,
 * gRPC only, no REST. Authenticate sends no ticket header: it is the only call that needs none.
 * <p>
 * Built with credentials it is also the {@link TicketProvider} shared by the repository service clients: it logs in lazily,
 * on the first {@link #ticket()}, caches the ticket and logs in again after {@link #invalidate}. Wrong credentials
 * surface as the {@link StatusRuntimeException} of the server (UNAUTHENTICATED).
 *
 * <pre>{@code
 * val tickets = new AuthenticationServiceClient(channel, "mcp", "secret", Duration.ofSeconds(30));
 * val client = new NodeServiceClient(channel, tickets, Duration.ofSeconds(30));
 * }</pre>
 */
public class AuthenticationServiceClient implements TicketProvider {

    private final AuthenticationServiceBlockingStub stub;
    private final Duration deadline;
    private final String user;
    private final String password;
    private volatile String ticket;

    /**
     * Creates this service client with shared authentication and a per-call deadline.
     *
     * <p>The caller retains ownership of the channel; closing an application should close its shared channel
     * once.
     *
     * @param channel shared gRPC channel; ownership remains with the caller
     * @param deadline per-call deadline applied to every RPC attempt
     */
    public AuthenticationServiceClient(Channel channel, Duration deadline) {
        this(channel, null, null, deadline);
    }

    /**
     * Creates this service client with shared authentication and a per-call deadline.
     *
     * <p>The caller retains ownership of the channel; closing an application should close its shared channel
     * once.
     *
     * @param channel shared gRPC channel; ownership remains with the caller
     * @param user Alfresco username used for authentication
     * @param password password for the supplied username
     * @param deadline per-call deadline applied to every RPC attempt
     */
    public AuthenticationServiceClient(Channel channel, String user, String password, Duration deadline) {
        this(AuthenticationServiceGrpc.newBlockingStub(channel), user, password, deadline);
    }

    /**
     * Creates this service client with shared authentication and a per-call deadline.
     *
     * <p>The caller retains ownership of the channel; closing an application should close its shared channel
     * once.
     *
     * @param stub preconfigured stub for this repository service
     * @param deadline per-call deadline applied to every RPC attempt
     */
    public AuthenticationServiceClient(AuthenticationServiceBlockingStub stub, Duration deadline) {
        this(stub, null, null, deadline);
    }

    /**
     * Creates this service client with shared authentication and a per-call deadline.
     *
     * <p>The caller retains ownership of the channel; closing an application should close its shared channel
     * once.
     *
     * @param stub preconfigured stub for this repository service
     * @param user Alfresco username used for authentication
     * @param password password for the supplied username
     * @param deadline per-call deadline applied to every RPC attempt
     */
    public AuthenticationServiceClient(AuthenticationServiceBlockingStub stub, String user, String password, Duration deadline) {
        this.stub = stub;
        this.deadline = deadline;
        this.user = user;
        this.password = password;
    }

    /**
     * Exchanges user and password for a new ticket. Wrong credentials are UNAUTHENTICATED.
     *
     * @param user Alfresco username used for authentication
     * @param password password for the supplied username
     * @return the authenticated repository ticket
     */
    public String authenticate(String user, String password) {
        val value = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS).authenticate(LoginRequest.newBuilder().setUser(user).setPassword(password).build()).getTicket();
        if (value.isEmpty())
            throw new IllegalStateException("The server returned no ticket for user %s".formatted(user));
        return value;
    }

    /**
     * Returns the cached ticket, logging in with the credentials of the constructor if there is none.
     *
     * @return the authenticated repository ticket
     */
    @Override public String ticket() {
        val current = ticket;
        if (current != null)
            return current;
        if (user == null)
            throw new IllegalStateException("No credentials: build the client with user and password");
        synchronized (this) {
            if (ticket == null)
                ticket = authenticate(user, password);
            return ticket;
        }
    }

    /**
     * Drops {@code expired} so that the next {@link #ticket()} call logs in again.
     *
     * @param expired ticket rejected by a server call; only the matching cached ticket is invalidated
     */
    @Override public synchronized void invalidate(String expired) {
        if (expired != null && expired.equals(ticket))
            ticket = null;
    }
}
