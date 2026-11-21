package org.saidone.protoalf.client;

/**
 * Supplies the Alfresco authentication ticket sent to the gRPC module in the {@code alfresco-ticket} header:
 * every call runs as the user the ticket belongs to.
 */
public interface TicketProvider {

    String ticket();

    default void invalidate(String expired) {}

    static TicketProvider of(String ticket) {
        return () -> ticket;
    }
}
