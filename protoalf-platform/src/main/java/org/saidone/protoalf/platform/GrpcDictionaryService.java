package org.saidone.protoalf.platform;

import io.grpc.stub.StreamObserver;
import lombok.val;
import org.alfresco.service.ServiceRegistry;
import org.saidone.protoalf.api.*;

/** Adapts Alfresco DictionaryService model lookups and listings with optional property expansion. */
public final class GrpcDictionaryService extends DictionaryServiceGrpc.DictionaryServiceImplBase {

    private final GrpcServiceSupport support;
    private final ModelLister models;

    public GrpcDictionaryService(ServiceRegistry services) {
        this.support = new GrpcServiceSupport(services);
        this.models = new ModelLister(services);
    }

    @Override public void getAllModels(ListModelsRequest request, StreamObserver<ListModelsResponse> observer) {
        support.execute(observer, () -> models.list(request.getModel(), request.getIncludeProperties()));
    }

    @Override public void getModel(ListModelsRequest request, StreamObserver<ModelInfo> observer) {
        support.execute(observer, () -> models.getModel(request.getModel(), request.getIncludeProperties()));
    }
}
