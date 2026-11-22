package org.saidone.protoalf.platform;

import io.grpc.*;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.alfresco.repo.security.authentication.AuthenticationException;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.saidone.protoalf.api.AuthenticationServiceGrpc;

/**
 * Validates tickets without leaving Alfresco's thread-local authentication behind.
 */
@RequiredArgsConstructor
public final class TicketInterceptor implements ServerInterceptor {

    static final Context.Key<String> USER = Context.key("alfresco-user");

    /**
     * The only method callable without a ticket: it is how a ticket is obtained.
     */
    static final String AUTHENTICATE_METHOD = AuthenticationServiceGrpc.getAuthenticateMethod().getFullMethodName();
    private static final Metadata.Key<String> TICKET = Metadata.Key.of("alfresco-ticket", Metadata.ASCII_STRING_MARSHALLER);
    private final AuthenticationService authentication;

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

    private static boolean isAuthenticate(ServerCall<?, ?> call) {
        val method = call.getMethodDescriptor();
        return method != null && AUTHENTICATE_METHOD.equals(method.getFullMethodName());
    }
}
