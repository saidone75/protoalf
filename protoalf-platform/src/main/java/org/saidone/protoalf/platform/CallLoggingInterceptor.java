package org.saidone.protoalf.platform;

import io.grpc.*;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Logs every gRPC call once it closes: method, authenticated user, status and duration.
 *
 * <p>Registered outside {@link TicketInterceptor}, so calls rejected for a missing or invalid ticket are logged
 * too. The user is read when the call closes: handlers close it within the context carrying {@link
 * TicketInterceptor#USER}, while rejected and credential-authentication calls have no user yet.
 *
 * <p>Levels: successful calls at debug, client errors (invalid argument, not found, unauthenticated, ...) at
 * info, server failures at warn; their stack traces are logged where the failure is mapped to a status.
 */
@Slf4j
final class CallLoggingInterceptor implements ServerInterceptor {

    /**
     * Wraps the call to log its outcome on close; passes it through untouched when warn is disabled.
     *
     * @param <ReqT> request message type
     * @param <RespT> response message type
     * @param call incoming RPC
     * @param headers request metadata
     * @param next downstream handler
     * @return the downstream listener
     */
    @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (!log.isWarnEnabled())
            return next.startCall(call, headers);
        val start = System.nanoTime();
        val descriptor = call.getMethodDescriptor();
        val method = descriptor == null ? "unknown method" : descriptor.getFullMethodName();
        return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override public void close(Status status, Metadata trailers) {
                report(method, TicketInterceptor.USER.get(), status, (System.nanoTime() - start) / 1_000_000);
                super.close(status, trailers);
            }
        }, headers);
    }

    /**
     * Writes one line per call at the level matching its outcome.
     *
     * @param method fully qualified RPC method name
     * @param user authenticated username, or null before ticket validation
     * @param status final call status
     * @param millis call duration in milliseconds
     */
    private static void report(String method, String user, Status status, long millis) {
        val code = status.getCode();
        val who = user == null ? "-" : user;
        if (code == Status.Code.OK)
            log.debug("{} user={} status=OK time={}ms", method, who, millis);
        else if (code == Status.Code.INTERNAL || code == Status.Code.UNKNOWN || code == Status.Code.DATA_LOSS)
            log.warn("{} user={} status={} time={}ms: {}", method, who, code, millis, status.getDescription());
        else
            log.info("{} user={} status={} time={}ms: {}", method, who, code, millis, status.getDescription());
    }
}
