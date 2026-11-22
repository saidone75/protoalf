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
import org.saidone.protoalf.api.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Shared authentication context, transactions, node reference resolution and error translation for repository adapters. */
@RequiredArgsConstructor
@Slf4j
final class GrpcServiceSupport {

    private final ServiceRegistry services;

    /**
     * The property_values of a request as name -> list of text values.
     */
    static Map<String, List<String>> values(Map<String, PropertyValues> input) {
        val result = new HashMap<String, List<String>>();
        input.forEach((name, values) -> result.put(name, values.getValuesList()));
        return result;
    }

    /**
     * Node aliases accepted in place of a nodeRef, as in the Alfresco REST API. Resolved as the calling user.
     */
    static final Map<String, String> ALIASES = Map.of("-root-", CompanyHomeNodeLocator.NAME, "-my-", UserHomeNodeLocator.NAME, "-shared-", SharedHomeNodeLocator.NAME);
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");

    private static final String USAGE = "node_ref must be a nodeRef (workspace://SpacesStore/<id>), a node id or one of the aliases -root-, -my-, -shared-";

    /**
     * Accepts a full nodeRef, a bare node id (workspace://SpacesStore) or an alias. Must run inside the transaction.
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

    static int pageSize(int skipCount, int maxItems) {
        val size = maxItems == 0 ? 100 : maxItems;
        if (size < 1 || size > 1000 || skipCount < 0 || skipCount > Integer.MAX_VALUE - size)
            throw new IllegalArgumentException("Invalid pagination: max_items must be 1..1000, skip_count >= 0");
        return size;
    }

    <T> void execute(StreamObserver<T> observer, Supplier<T> work) {
        run(observer, true, work);
    }

    <T> void write(StreamObserver<T> observer, Supplier<T> work) {
        run(observer, false, work);
    }

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
     * Runs the work in a transaction as the user. Failures are thrown as the gRPC status to report.
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

    static String rootMessage(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
