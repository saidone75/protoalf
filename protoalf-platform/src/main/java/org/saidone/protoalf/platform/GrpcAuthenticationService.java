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
 * Authenticates Alfresco credentials and returns the resulting repository ticket.
 *
 * <p>This is the only service operation allowed without an existing {@code alfresco-ticket} header. It combines
 * Alfresco authentication and retrieval of the current ticket in one RPC. The previous thread-local
 * authentication is restored on both success and failure; authentication does not remain attached to the gRPC
 * worker thread.
 *
 * <p>Subsequent repository calls must send the returned ticket to {@link TicketInterceptor}.
 *
 * @see AuthenticationService
 */
@Slf4j
@RequiredArgsConstructor
public final class GrpcAuthenticationService extends AuthenticationServiceGrpc.AuthenticationServiceImplBase {

    /**
     * Alfresco authentication service used to validate credentials and obtain tickets.
     */
    private final AuthenticationService authentication;

    /**
     * Authenticates credentials and retrieves the current Alfresco ticket in the same call. Blank credentials
     * yield {@code INVALID_ARGUMENT}, rejected credentials yield {@code UNAUTHENTICATED}, and unexpected
     * failures yield {@code INTERNAL}.
     *
     * <p>No existing ticket is required. The previous thread authentication is restored before the successful
     * response is emitted.
     *
     * @param request required username and password
     * @param observer receives one repository ticket on success, or the corresponding gRPC error
     */
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
