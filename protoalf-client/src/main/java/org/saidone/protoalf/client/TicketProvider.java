package org.saidone.protoalf.client;

/**
 * Supplies the Alfresco authentication ticket sent to the gRPC module in the {@code alfresco-ticket} header:
 * every call runs as the user the ticket belongs to. Share one provider between service clients so that
 * authentication and ticket renewal are coordinated.
 */
@FunctionalInterface
public interface TicketProvider {

    /**
     * Returns the current ticket, authenticating lazily when necessary.
     *
     * @return the repository ticket to attach to the next RPC
     */
    String ticket();

    /**
     * Invalidates a rejected ticket without discarding a newer ticket obtained by another call. The default
     * implementation retains the fixed ticket; clients do not retry with an unchanged value.
     *
     * @param expired ticket rejected by the repository
     */
    default void invalidate(String expired) {
    }

    /**
     * Creates a provider that always returns the supplied ticket and cannot renew it.
     *
     * @param ticket existing Alfresco repository ticket
     * @return a fixed-ticket provider reusable by all service clients
     */
    static TicketProvider of(String ticket) {
        return () -> ticket;
    }
}
