package org.saidone.protoalf.platform;

import lombok.val;
import io.grpc.*;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.alfresco.repo.security.authentication.AuthenticationUtil;

/** Validates tickets without leaving Alfresco's thread-local authentication behind. */
public final class TicketInterceptor implements ServerInterceptor {
    static final Context.Key<String> USER = Context.key("alfresco-user");
    /** The only method callable without a ticket: it is how a ticket is obtained. */
    static final String LOGIN_METHOD = org.saidone.protoalf.api.AuthenticationServiceGrpc.getLoginMethod().getFullMethodName();
    private static final Metadata.Key<String> TICKET = Metadata.Key.of("alfresco-ticket", Metadata.ASCII_STRING_MARSHALLER);
    private final AuthenticationService authentication;
    public TicketInterceptor(AuthenticationService authentication) { this.authentication = authentication; }
    @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (isLogin(call)) return next.startCall(call, headers);
        val ticket = headers.get(TICKET);
        if (ticket == null || ticket.isBlank()) {
            call.close(Status.UNAUTHENTICATED.withDescription("Alfresco ticket required"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        String user;
        AuthenticationUtil.pushAuthentication();
        try {
            authentication.validate(ticket);
            user = authentication.getCurrentUserName();
        } catch (org.alfresco.repo.security.authentication.AuthenticationException e) {
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid Alfresco ticket"), new Metadata());
            return new ServerCall.Listener<>() {};
        } finally { AuthenticationUtil.popAuthentication(); }
        return Contexts.interceptCall(Context.current().withValue(USER, user), call, headers, next);
    }
    private static boolean isLogin(ServerCall<?, ?> call) {
        val method = call.getMethodDescriptor();
        return method != null && LOGIN_METHOD.equals(method.getFullMethodName());
    }
}
