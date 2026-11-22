package org.saidone.protoalf.platform;

import io.grpc.*;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.alfresco.repo.security.authentication.AuthenticationException;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.saidone.protoalf.api.AuthenticationServiceGrpc;

/**
 * Validates repository tickets and propagates the authenticated user through the gRPC context.
 *
 * <p>The exact credential-authentication RPC is passed through without a ticket. Every other RPC requires a
 * non-blank {@code alfresco-ticket} header that Alfresco accepts. The interceptor restores thread-local
 * Alfresco authentication immediately after validation and records only the resulting username in {@link
 * #USER}. Repository adapters establish that identity again when executing their transaction callbacks.
 *
 * <p>Missing or invalid tickets close the call with {@code UNAUTHENTICATED} before the handler is invoked.
 */
@RequiredArgsConstructor
public final class TicketInterceptor implements ServerInterceptor {

    /**
     * Authenticated username carried by the gRPC context and used by repository transaction callbacks.
     */
    static final Context.Key<String> USER = Context.key("alfresco-user");

    /**
     * Exact RPC method name allowed to exchange credentials without a ticket.
     */
    static final String AUTHENTICATE_METHOD = AuthenticationServiceGrpc.getAuthenticateMethod().getFullMethodName();

    /**
     * ASCII metadata key used to carry the Alfresco repository ticket.
     */
    private static final Metadata.Key<String> TICKET = Metadata.Key.of("alfresco-ticket", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Alfresco authentication service used to validate incoming repository tickets.
     */
    private final AuthenticationService authentication;

    /**
     * Allows credential authentication or validates the incoming ticket before starting a repository call.
     * Alfresco authentication is pushed and restored during validation; only the username is propagated through
     * the gRPC context.
     *
     * @param <ReqT> request message type
     * @param <RespT> response message type
     * @param call incoming RPC and its method descriptor
     * @param headers request metadata containing alfresco-ticket for protected RPCs
     * @param next downstream handler, invoked only after successful validation or for authentication
     * @return the downstream listener, or an inert listener after rejecting the call
     */
    @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (isAuthenticate(call))
            return next.startCall(call, headers);
        val ticket = headers.get(TICKET);
        if (ticket == null || ticket.isBlank()) {
            call.close(Status.UNAUTHENTICATED.withDescription("Alfresco ticket required"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        String user;
        AuthenticationUtil.pushAuthentication();
        try {
            authentication.validate(ticket);
            user = authentication.getCurrentUserName();
        } catch (AuthenticationException e) {
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid Alfresco ticket"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        } finally {
            AuthenticationUtil.popAuthentication();
        }
        return Contexts.interceptCall(Context.current().withValue(USER, user), call, headers, next);
    }

    /**
     * Checks the exact fully qualified method name rather than trusting the service name or a missing
     * descriptor.
     *
     * @param call incoming RPC to classify
     * @return true only for the credential-authentication RPC
     */
    private static boolean isAuthenticate(ServerCall<?, ?> call) {
        val method = call.getMethodDescriptor();
        return method != null && AUTHENTICATE_METHOD.equals(method.getFullMethodName());
    }
}
