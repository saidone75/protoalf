package org.saidone.protoalf.platform;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.alfresco.repo.security.authentication.AuthenticationException;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.saidone.protoalf.api.AuthenticationServiceGrpc;
import org.saidone.protoalf.api.LoginRequest;
import org.saidone.protoalf.api.LoginResponse;

/**
 * Exchanges user and password for an Alfresco ticket. Runs without a ticket: see TicketInterceptor.
 */
@Slf4j
@RequiredArgsConstructor
public final class GrpcAuthenticationService extends AuthenticationServiceGrpc.AuthenticationServiceImplBase {

    private final AuthenticationService authentication;

    @Override public void authenticate(LoginRequest request, StreamObserver<LoginResponse> observer) {
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
        } catch (AuthenticationException e) {
            log.debug("Login refused for {}", user, e);
            observer.onError(Status.UNAUTHENTICATED.withDescription("Invalid user or password").asRuntimeException());
            return;
        } catch (RuntimeException e) {
            log.error("Login failed for {}", user, e);
            observer.onError(Status.INTERNAL.withDescription("Login failed").asRuntimeException());
            return;
        } finally {
            AuthenticationUtil.popAuthentication();
        }
        observer.onNext(LoginResponse.newBuilder().setTicket(ticket).build());
        observer.onCompleted();
    }
}
