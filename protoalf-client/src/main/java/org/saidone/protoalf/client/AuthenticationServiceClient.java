package org.saidone.protoalf.client;

import lombok.val;
import io.grpc.Channel;
import org.saidone.protoalf.api.AuthenticationServiceGrpc;
import org.saidone.protoalf.api.AuthenticationServiceGrpc.AuthenticationServiceBlockingStub;
import org.saidone.protoalf.api.LoginRequest;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Client of the AuthenticationService of the gRPC module: exchanges user and password for an Alfresco ticket,
 * gRPC only, no REST. Login sends no ticket header: it is the only call that needs none.
 * <p>
 * Built with credentials it is also the {@link TicketProvider} of a {@link NodeServiceClient}: it logs in lazily,
 * on the first {@link #ticket()}, caches the ticket and logs in again after {@link #invalidate}. Wrong credentials
 * surface as the {@link io.grpc.StatusRuntimeException} of the server (UNAUTHENTICATED).
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
     * Without credentials: only {@link #login(String, String)} can be used.
     *
     * @param channel  channel to the gRPC module (plain or TLS, see AlfrescoGrpcChannels)
     * @param deadline deadline of each login call
     */
    public AuthenticationServiceClient(Channel channel, Duration deadline) {
        this(channel, null, null, deadline);
    }

    /**
     * @param channel  channel to the gRPC module (plain or TLS, see AlfrescoGrpcChannels)
     * @param user     user {@link #ticket()} logs in as
     * @param password password of {@code user}
     * @param deadline deadline of each login call
     */
    public AuthenticationServiceClient(Channel channel, String user, String password, Duration deadline) {
        this.stub = AuthenticationServiceGrpc.newBlockingStub(channel);
        this.deadline = deadline;
        this.user = user;
        this.password = password;
    }

    /** Exchanges user and password for a new ticket. Wrong credentials are UNAUTHENTICATED. */
    public String login(String user, String password) {
        val value = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .login(LoginRequest.newBuilder().setUser(user).setPassword(password).build())
                .getTicket();
        if (value.isEmpty()) throw new IllegalStateException("The server returned no ticket for user %s".formatted(user));
        return value;
    }

    /** Returns the cached ticket, logging in with the credentials of the constructor if there is none. */
    @Override
    public String ticket() {
        val current = ticket;
        if (current != null) return current;
        if (user == null) throw new IllegalStateException("No credentials: build the client with user and password");
        synchronized (this) {
            if (ticket == null) ticket = login(user, password);
            return ticket;
        }
    }

    /** Drops {@code expired} so that the next {@link #ticket()} call logs in again. */
    @Override
    public synchronized void invalidate(String expired) {
        if (expired != null && expired.equals(ticket)) ticket = null;
    }
}
