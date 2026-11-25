package org.saidone.protoalf.platform;

import io.grpc.stub.StreamObserver;
import lombok.val;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeService;
import org.saidone.protoalf.api.*;

import java.util.List;
import java.util.Map;

/**
 * Exposes node metadata and aspect operations through gRPC.
 *
 * <p>Primitive operations follow Alfresco's node service. {@link #getNode} returns a complete snapshot and
 * {@link #updateNode} applies a composite update in one transaction; these two methods are remote convenience
 * extensions. Property and aspect names accept prefix notation or expanded qualified names.
 *
 * <p>Reads run in read-only transactions; mutations run in retryable read-write transactions as the user
 * established by {@link TicketInterceptor}. Successful unary calls emit one result after the transaction
 * finishes. Failures are reported through the observer using the status mappings described in the package
 * documentation.
 *
 * @see NodeService
 */
public final class GrpcNodeService extends NodeServiceGrpc.NodeServiceImplBase {

    /**
     * Repository services used under the caller's transaction and authentication context.
     */
    private final ServiceRegistry services;

    /**
     * Shared caller-context, transaction, reference-resolution and error-mapping helper.
     */
    private final GrpcServiceSupport support;

    /**
     * Converter from repository state to transport-safe node snapshots.
     */
    private final NodeMapper mapper;

    /**
     * Validated repository mutation helper used inside write transactions.
     */
    private final NodeWriter writer;

    /**
     * Creates the adapter and its repository helpers without starting repository work.
     *
     * @param services Alfresco service registry; repository work uses its secured services
     */
    public GrpcNodeService(ServiceRegistry services) {
        this.services = services;
        this.support = new GrpcServiceSupport(services);
        this.mapper = new NodeMapper(services);
        this.writer = new NodeWriter(services);
    }

    /**
     * Reads the node property map, preserving scalar types, nulls and collection cardinality. Content values
     * contain metadata only.
     *
     * @param request node reference to inspect
     * @param observer receives one property map keyed by expanded qualified names, or a gRPC error
     */
    @Override public void getProperties(NodeRefRequest request, StreamObserver<NodeProperties> observer) {
        support.execute(observer, () -> {
            val response = NodeProperties.newBuilder();
            services.getNodeService().getProperties(support.ref(request.getNodeRef())).forEach((name, value) -> response.putProperties(name.toString(), NodeMapper.property(value)));
            return response.build();
        });
    }

    /**
     * Merges writable metadata into a node without replacing unrelated properties. Empty input is a no-op.
     * Property value kinds and explicit cardinality must match the repository dictionary.
     *
     * @param request node reference and scalar or list-valued properties to merge
     * @param observer receives one updated node snapshot, or a gRPC error
     */
    @Override public void addProperties(AddPropertiesRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> {
            val ref = support.ref(request.getNodeRef());
            if (!request.getPropertiesMap().isEmpty()) {
                writer.update(ref, "", "", request.getPropertiesMap(), List.of(), List.of());
            }
            return mapper.map(ref);
        });
    }

    /**
     * Reads the node type as an expanded qualified name.
     *
     * @param request node reference to inspect
     * @param observer receives one qualified type name, or a gRPC error
     */
    @Override public void getType(NodeRefRequest request, StreamObserver<NodeType> observer) {
        support.execute(observer, () -> NodeType.newBuilder().setType(services.getNodeService().getType(support.ref(request.getNodeRef())).toString()).build());
    }

    /**
     * Changes a node to a known document or folder type. The new type must remain in the same document/folder
     * category; blank type names are invalid.
     *
     * @param request node reference and required type name in prefix or expanded form
     * @param observer receives one updated node snapshot, or a gRPC error
     */
    @Override public void setType(SetTypeRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> {
            if (request.getType().isBlank())
                throw new IllegalArgumentException("type is required");
            return mapper.map(writer.update(support.ref(request.getNodeRef()), "", request.getType(), Map.of()));
        });
    }

    /**
     * Lists the aspects currently applied to the node, sorted by expanded qualified name.
     *
     * @param request node reference to inspect
     * @param observer receives one ordered list of aspect names, or a gRPC error
     */
    @Override public void getAspects(NodeRefRequest request, StreamObserver<NodeAspects> observer) {
        support.execute(observer, () -> {
            val response = NodeAspects.newBuilder();
            services.getNodeService().getAspects(support.ref(request.getNodeRef())).stream().map(Object::toString).sorted().forEach(response::addAspects);
            return response.build();
        });
    }

    /**
     * Adds a known non-system aspect and merges any supplied properties in one transaction. An already present
     * aspect is left in place and supplied properties are still applied.
     *
     * @param request node reference, aspect name and optional scalar or list-valued properties
     * @param observer receives one updated node snapshot, or a gRPC error
     */
    @Override public void addAspect(AddAspectRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.update(support.ref(request.getNodeRef()), "", "", request.getPropertiesMap(), List.of(request.getAspect()), List.of())));
    }

    /**
     * Removes a known non-system aspect if present. An absent aspect is ignored; Alfresco enforces model
     * constraints and aspect removal effects.
     *
     * @param request node reference and aspect name to remove
     * @param observer receives one updated node snapshot, or a gRPC error
     */
    @Override public void removeAspect(RemoveAspectRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.update(support.ref(request.getNodeRef()), "", "", Map.of(), List.of(), List.of(request.getAspect()))));
    }

    /**
     * Deletes a node through Alfresco, normally moving it to the archive store. Company Home, Shared Files and
     * the calling user's home folder are explicitly protected and rejected as invalid arguments.
     *
     * @param request node reference to delete
     * @param observer receives one empty acknowledgement after the write succeeds, or a gRPC error
     */
    @Override public void deleteNode(DeleteNodeRequest request, StreamObserver<DeleteNodeResponse> observer) {
        support.write(observer, () -> {
            writer.delete(support.ref(request.getNodeRef()));
            return DeleteNodeResponse.getDefaultInstance();
        });
    }

    /**
     * Returns one complete node snapshot, combining name, type, aspects, properties and a readable primary
     * parent. This convenience extension is not a single native Alfresco node-service operation.
     *
     * @param request node reference to inspect
     * @param observer receives one snapshot; an unreadable or absent parent is omitted, or a gRPC error
     */
    @Override public void getNode(GetNodeRequest request, StreamObserver<Node> observer) {
        support.execute(observer, () -> mapper.map(support.ref(request.getNodeRef())));
    }

    /**
     * Atomically renames a node, changes its type, adds/removes aspects and merges metadata. Empty name/type
     * fields preserve their current values. At least one change is required; document/folder category changes
     * are rejected.
     *
     * @param request node reference and requested name, type, property and aspect changes
     * @param observer receives one snapshot of the updated node, or a gRPC error
     */
    @Override public void updateNode(UpdateNodeRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.update(support.ref(request.getNodeRef()), request.getName(), request.getType(), request.getPropertiesMap(), request.getAddAspectsList(), request.getRemoveAspectsList())));
    }
}
