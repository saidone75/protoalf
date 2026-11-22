package org.saidone.protoalf.client;

import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.saidone.protoalf.api.PropertyValues;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Shared ticket attachment, deadline handling and single-renewal retry for repository service clients. */
@RequiredArgsConstructor
final class ClientCallSupport {

    static final Metadata.Key<String> TICKET_HEADER = Metadata.Key.of("alfresco-ticket", Metadata.ASCII_STRING_MARSHALLER);
    private final TicketProvider tickets;
    private final Duration deadline;

    /** Executes a unary RPC, renewing an expired ticket once when the provider can replace it. */
    <S extends AbstractStub<S>, T> T call(S service, Function<S, T> rpc) {
        val ticket = ticket();
        try {
            return rpc.apply(authenticated(service, ticket));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != Status.Code.UNAUTHENTICATED)
                throw e;
            invalidate(ticket);
            val renewed = ticket();
            if (renewed.equals(ticket))
                throw e;
            return rpc.apply(authenticated(service, renewed));
        }
    }

    /** Adds authentication and a fresh per-call deadline while retaining the supplied stub's other options. */
    <S extends AbstractStub<S>> S authenticated(S service, String ticket) {
        return service.withInterceptors(ticketHeader(ticket)).withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    String ticket() {
        return tickets.ticket();
    }

    void invalidate(String expired) {
        tickets.invalidate(expired);
    }

    /** Converts explicit text lists without losing empty lists used to clear a property. */
    static Map<String, PropertyValues> toPropertyValues(Map<String, List<String>> values) {
        return values.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> PropertyValues.newBuilder().addAllValues(entry.getValue()).build()));
    }

    private static ClientInterceptor ticketHeader(String ticket) {
        val headers = new Metadata();
        headers.put(TICKET_HEADER, ticket);
        return MetadataUtils.newAttachHeadersInterceptor(headers);
    }
}
