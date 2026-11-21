package org.saidone.protoalf.platform;

import lombok.val;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.saidone.protoalf.api.AuthenticationServiceGrpc;
import org.saidone.protoalf.api.LoginRequest;
import org.saidone.protoalf.api.LoginResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exchanges user and password for an Alfresco ticket. Runs without a ticket: see TicketInterceptor. */
public final class GrpcAuthenticationService extends AuthenticationServiceGrpc.AuthenticationServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(GrpcAuthenticationService.class);
    private final AuthenticationService authentication;
    public GrpcAuthenticationService(AuthenticationService authentication) { this.authentication = authentication; }

    @Override public void login(LoginRequest request, StreamObserver<LoginResponse> observer) {
        val user = request.getUser();
        val password = request.getPassword();
        if (user.isBlank() || password.isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("user and password are required").asRuntimeException());
            return;
        }
        String ticket;
        AuthenticationUtil.pushAuthentication();
        try {
            // authenticate() returns nothing: the ticket of the authenticated user is read from the current authentication.
            authentication.authenticate(user, password.toCharArray());
            ticket = authentication.getCurrentTicket();
        } catch (org.alfresco.repo.security.authentication.AuthenticationException e) {
            LOG.debug("Login refused for {}", user, e);
            observer.onError(Status.UNAUTHENTICATED.withDescription("Invalid user or password").asRuntimeException());
            return;
        } catch (RuntimeException e) {
            LOG.error("Login failed for {}", user, e);
            observer.onError(Status.INTERNAL.withDescription("Login failed").asRuntimeException());
            return;
        } finally { AuthenticationUtil.popAuthentication(); }
        observer.onNext(LoginResponse.newBuilder().setTicket(ticket).build());
        observer.onCompleted();
    }
}
