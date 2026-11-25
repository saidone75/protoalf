package org.saidone.protoalf.platform;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.alfresco.repo.node.integrity.IntegrityException;
import org.alfresco.repo.nodelocator.CompanyHomeNodeLocator;
import org.alfresco.repo.nodelocator.SharedHomeNodeLocator;
import org.alfresco.repo.nodelocator.UserHomeNodeLocator;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.permissions.AccessDeniedException;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.lock.NodeLockedException;
import org.alfresco.service.cmr.model.FileExistsException;
import org.alfresco.service.cmr.repository.CyclicChildRelationshipException;
import org.alfresco.service.cmr.repository.DuplicateChildNodeNameException;
import org.alfresco.service.cmr.repository.InvalidNodeRefException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.StoreRef;

import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Provides shared transaction execution, reference resolution and gRPC error translation.
 *
 * <p>Service adapters use this helper rather than emitting responses from transaction callbacks. Alfresco may
 * retry a callback, so unary results are sent only after its transaction succeeds. Authentication is scoped to
 * each callback with Alfresco's run-as utility, preserving the worker thread's previous identity.
 *
 * <p>Reference resolution and repository callbacks must run inside a transaction. Static conversion and
 * pagination helpers are independent of transaction state. Status translation deliberately omits internal
 * exception details for permission failures and unexpected repository errors.
 */
@RequiredArgsConstructor
@Slf4j
final class GrpcServiceSupport {

    /**
     * Repository services used under the caller's transaction and authentication context.
     */
    private final ServiceRegistry services;

    /**
     * Remote root, current-user home and shared aliases mapped to Alfresco node locators.
     */
    static final Map<String, String> ALIASES = Map.of("-root-", CompanyHomeNodeLocator.NAME, "-my-", UserHomeNodeLocator.NAME, "-shared-", SharedHomeNodeLocator.NAME);

    /**
     * Accepted syntax for a bare workspace node ID; values are never interpreted as filesystem paths.
     */
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");

    /**
     * Validation message listing the accepted node reference and alias forms.
     */
    private static final String USAGE = "node_ref must be a nodeRef (workspace://SpacesStore/<id>), a node id or one of the aliases -root-, -my-, -shared-";

    /**
     * Resolves a node reference, bare workspace ID or supported alias and verifies that the node exists. Must
     * run inside the caller's repository transaction. Alias resolution uses the caller identity.
     *
     * @param value full node reference, bare node ID, -root-, -my- or -shared-
     * @return the resolved existing repository node reference
     * @throws IllegalArgumentException if the value is blank or malformed
     * @throws StatusRuntimeException with NOT_FOUND if the alias has no node or the resolved node does not
     *     exist
     */
    NodeRef ref(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(USAGE);
        NodeRef ref;
        val locator = ALIASES.get(value);
        if (locator != null) {
            ref = services.getNodeLocatorService().getNode(locator, null, null);
            if (ref == null)
                throw Status.NOT_FOUND.withDescription(String.format("No node for alias %s", value)).asRuntimeException();
        } else if (NodeRef.isNodeRef(value)) {
            ref = new NodeRef(value);
        } else if (NODE_ID.matcher(value).matches()) {
            ref = new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, value);
        } else {
            throw new IllegalArgumentException(USAGE);
        }
        if (!services.getNodeService().exists(ref))
            throw Status.NOT_FOUND.withDescription("Node not found").asRuntimeException();
        return ref;
    }

    /**
     * Normalizes the default page size and validates pagination without integer overflow.
     *
     * @param skipCount non-negative number of entries to skip
     * @param maxItems requested size in 1..1000, or zero to select 100
     * @return the effective page size
     * @throws IllegalArgumentException if the size, skip count or their sum is out of range
     */
    static int pageSize(int skipCount, int maxItems) {
        val size = maxItems == 0 ? 100 : maxItems;
        if (size < 1 || size > 1000 || skipCount < 0 || skipCount > Integer.MAX_VALUE - size)
            throw new IllegalArgumentException("Invalid pagination: max_items must be 1..1000, skip_count >= 0");
        return size;
    }

    /**
     * Executes one unary repository operation in a read-only transaction and emits its response after
     * successful completion.
     *
     * @param <T> response message type
     * @param observer receives one result and completion, or a translated gRPC error
     * @param work repository callback; it must not emit responses and may run more than once
     */
    <T> void execute(StreamObserver<T> observer, Supplier<T> work) {
        run(observer, true, work);
    }

    /**
     * Executes one unary repository operation in a read-write transaction and emits its response after
     * successful completion.
     *
     * @param <T> response message type
     * @param observer receives one result and completion, or a translated gRPC error
     * @param work repository callback; it must not emit responses and may run more than once
     */
    <T> void write(StreamObserver<T> observer, Supplier<T> work) {
        run(observer, false, work);
    }

    /**
     * Runs a unary callback as the context user and reports the result outside the transaction. Missing
     * authentication is rejected before touching transaction services.
     *
     * @param <T> response message type
     * @param observer response observer to complete or fail
     * @param readOnly whether the repository transaction forbids writes
     * @param work retryable callback that produces the response without sending it
     */
    private <T> void run(StreamObserver<T> observer, boolean readOnly, Supplier<T> work) {
        val user = TicketInterceptor.USER.get();
        if (user == null) {
            observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
            return;
        }
        T result;
        try {
            result = transact(user, readOnly, work);
        } catch (StatusRuntimeException e) {
            observer.onError(e);
            return;
        }
        // Never emit a response inside a transaction that Alfresco may retry.
        observer.onNext(result);
        observer.onCompleted();
    }

    /**
     * Executes work under the requested Alfresco identity and transaction mode, then translates repository
     * failures to gRPC status exceptions. The previous authentication context is restored by the run-as
     * utility.
     *
     * @param <T> callback result type
     * @param user authenticated repository username
     * @param readOnly true for read-only work, false for mutations
     * @param work retryable repository callback; external side effects must be retry-safe
     * @return the successful callback result
     * @throws StatusRuntimeException for mapped repository failures; existing status exceptions are preserved
     */
    <T> T transact(String user, boolean readOnly, Supplier<T> work) {
        try {
            return AuthenticationUtil.runAs(() -> services.getTransactionService().getRetryingTransactionHelper().doInTransaction(work::get, readOnly), user);
        } catch (AccessDeniedException e) {
            throw Status.PERMISSION_DENIED.asRuntimeException();
        } catch (InvalidNodeRefException e) {
            throw Status.NOT_FOUND.asRuntimeException();
        } catch (FileExistsException | DuplicateChildNodeNameException e) {
            throw Status.ALREADY_EXISTS.withDescription("A node with the same name already exists in the target folder").asRuntimeException();
        } catch (NodeLockedException e) {
            throw Status.FAILED_PRECONDITION.withDescription("The node is locked").asRuntimeException();
        } catch (CyclicChildRelationshipException e) {
            throw Status.INVALID_ARGUMENT.withDescription("A folder cannot be moved or copied into itself").asRuntimeException();
        } catch (IntegrityException e) {
            log.debug("Integrity violation", e);
            throw Status.FAILED_PRECONDITION.withDescription(String.format("Model constraint violated: %s", rootMessage(e))).asRuntimeException();
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException();
        } catch (StatusRuntimeException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("gRPC repository operation failed", e);
            throw Status.INTERNAL.withDescription("Repository operation failed").asRuntimeException();
        }
    }

    /**
     * Finds the deepest non-self-referencing cause and returns a useful diagnostic label.
     *
     * @param e failure whose underlying message is required
     * @return the deepest message, or the cause class simple name when its message is null
     */
    static String rootMessage(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
