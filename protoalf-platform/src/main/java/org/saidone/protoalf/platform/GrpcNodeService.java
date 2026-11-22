package org.saidone.protoalf.platform;

import io.grpc.stub.StreamObserver;
import lombok.val;
import org.alfresco.service.ServiceRegistry;
import org.saidone.protoalf.api.*;

import java.util.List;
import java.util.Map;

/** Adapts Alfresco NodeService operations; getNode and updateNode are aggregate convenience extensions. */
public final class GrpcNodeService extends NodeServiceGrpc.NodeServiceImplBase {

    private final ServiceRegistry services;
    private final GrpcServiceSupport support;
    private final NodeMapper mapper;
    private final NodeWriter writer;

    public GrpcNodeService(ServiceRegistry services) {
        this.services = services;
        this.support = new GrpcServiceSupport(services);
        this.mapper = new NodeMapper(services);
        this.writer = new NodeWriter(services);
    }

    @Override public void getProperties(NodeRefRequest request, StreamObserver<NodeProperties> observer) {
        support.execute(observer, () -> {
            val response = NodeProperties.newBuilder();
            services.getNodeService().getProperties(support.ref(request.getNodeRef())).forEach((name, value) -> response.putProperties(name.toString(), NodeMapper.property(value)));
            return response.build();
        });
    }

    @Override public void addProperties(AddPropertiesRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> {
            val ref = support.ref(request.getNodeRef());
            if (!request.getPropertiesMap().isEmpty() || !request.getPropertyValuesMap().isEmpty()) {
                writer.update(ref, "", "", request.getPropertiesMap(), GrpcServiceSupport.values(request.getPropertyValuesMap()), List.of(), List.of());
            }
            return mapper.map(ref);
        });
    }

    @Override public void getType(NodeRefRequest request, StreamObserver<NodeType> observer) {
        support.execute(observer, () -> NodeType.newBuilder().setType(services.getNodeService().getType(support.ref(request.getNodeRef())).toString()).build());
    }

    @Override public void setType(SetTypeRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> {
            if (request.getType().isBlank())
                throw new IllegalArgumentException("type is required");
            return mapper.map(writer.update(support.ref(request.getNodeRef()), "", request.getType(), Map.of()));
        });
    }

    @Override public void getAspects(NodeRefRequest request, StreamObserver<NodeAspects> observer) {
        support.execute(observer, () -> {
            val response = NodeAspects.newBuilder();
            services.getNodeService().getAspects(support.ref(request.getNodeRef())).stream().map(Object::toString).sorted().forEach(response::addAspects);
            return response.build();
        });
    }

    @Override public void addAspect(AddAspectRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.update(support.ref(request.getNodeRef()), "", "", request.getPropertiesMap(), GrpcServiceSupport.values(request.getPropertyValuesMap()), List.of(request.getAspect()), List.of())));
    }

    @Override public void removeAspect(RemoveAspectRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.update(support.ref(request.getNodeRef()), "", "", Map.of(), Map.of(), List.of(), List.of(request.getAspect()))));
    }

    @Override public void deleteNode(DeleteNodeRequest request, StreamObserver<DeleteNodeResponse> observer) {
        support.write(observer, () -> {
            writer.delete(support.ref(request.getNodeRef()));
            return DeleteNodeResponse.getDefaultInstance();
        });
    }

    /**
     * Convenience snapshot: Alfresco exposes the same data through separate property, type and aspect getters.
     */
    @Override public void getNode(GetNodeRequest request, StreamObserver<Node> observer) {
        support.execute(observer, () -> mapper.map(support.ref(request.getNodeRef())));
    }

    @Override public void updateNode(UpdateNodeRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.update(support.ref(request.getNodeRef()), request.getName(), request.getType(), request.getPropertiesMap(), GrpcServiceSupport.values(request.getPropertyValuesMap()), request.getAddAspectsList(), request.getRemoveAspectsList())));
    }
}
