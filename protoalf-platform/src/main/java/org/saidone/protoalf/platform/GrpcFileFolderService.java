package org.saidone.protoalf.platform;

import io.grpc.stub.StreamObserver;
import lombok.val;
import org.alfresco.model.ContentModel;
import org.alfresco.query.PagingRequest;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.util.Pair;
import org.saidone.protoalf.api.*;

import java.util.List;

/** Adapts Alfresco FileFolderService list, create, move and copy operations. */
public final class GrpcFileFolderService extends FileFolderServiceGrpc.FileFolderServiceImplBase {

    private final ServiceRegistry services;
    private final GrpcServiceSupport support;
    private final NodeMapper mapper;
    private final NodeWriter writer;

    public GrpcFileFolderService(ServiceRegistry services) {
        this.services = services;
        this.support = new GrpcServiceSupport(services);
        this.mapper = new NodeMapper(services);
        this.writer = new NodeWriter(services);
    }

    @Override public void list(ListChildrenRequest request, StreamObserver<ListChildrenResponse> observer) {
        support.execute(observer, () -> {
            val size = GrpcServiceSupport.pageSize(request.getSkipCount(), request.getMaxItems());
            val paging = new PagingRequest(request.getSkipCount(), size);
            // Same as the Alfresco REST API: ask for the exact (permission filtered) number of children.
            paging.setRequestTotalCountMax(Integer.MAX_VALUE);
            val page = services.getFileFolderService().list(support.ref(request.getNodeRef()), true, true, null, null, List.of(new Pair<>(ContentModel.PROP_NAME, true)), paging);
            val total = page.getTotalResultCount();
            val response = ListChildrenResponse.newBuilder().setHasMoreItems(page.hasMoreItems()).setTotalItems(total == null || total.getFirst() == null ? -1 : total.getFirst());
            for (val file : page.getPage()) response.addEntries(mapper.map(file.getNodeRef()));
            return response.build();
        });
    }

    @Override public void create(CreateNodeRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.create(support.ref(request.getParentRef()), request.getName(), request.getType(), request.getPropertiesMap(), GrpcServiceSupport.values(request.getPropertyValuesMap()), request.getAspectsList(), request.hasContent() ? request.getContent() : null)));
    }

    @Override public void move(MoveNodeRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.move(support.ref(request.getNodeRef()), support.ref(request.getTargetParentRef()), request.getName())));
    }

    @Override public void copy(CopyNodeRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.copy(support.ref(request.getNodeRef()), support.ref(request.getTargetParentRef()), request.getName())));
    }
}
