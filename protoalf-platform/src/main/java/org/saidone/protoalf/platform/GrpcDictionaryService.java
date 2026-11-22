package org.saidone.protoalf.platform;

import io.grpc.stub.StreamObserver;
import lombok.val;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.saidone.protoalf.api.*;

/**
 * Exposes deployed Alfresco content models, types, aspects and property definitions through gRPC.
 *
 * <p>Model listings optionally filter by namespace prefix or model name. Individual lookups require a qualified
 * model name. Property expansion describes only properties declared on each class, leaving inherited
 * definitions on the corresponding parent. Qualified names use namespace prefixes when available.
 *
 * <p>Dictionary access runs in the authenticated caller's read-only transaction. Invalid filters and unknown
 * models are reported through the response observer rather than thrown synchronously to a gRPC caller.
 *
 * @see DictionaryService
 */
public final class GrpcDictionaryService extends DictionaryServiceGrpc.DictionaryServiceImplBase {

    /**
     * Shared caller-context, transaction, reference-resolution and error-mapping helper.
     */
    private final GrpcServiceSupport support;

    /**
     * Dictionary-to-Protobuf model description helper.
     */
    private final ModelLister models;

    /**
     * Creates the adapter and its repository helpers without starting repository work.
     *
     * @param services Alfresco service registry; repository work uses its secured services
     */
    public GrpcDictionaryService(ServiceRegistry services) {
        this.support = new GrpcServiceSupport(services);
        this.models = new ModelLister(services);
    }

    /**
     * Lists deployed content models with namespaces, types and aspects. A blank filter returns every model;
     * otherwise a namespace prefix or model name is matched. Property expansion requires a non-blank filter. A
     * filter with no match yields {@code NOT_FOUND}.
     *
     * @param request optional model/prefix filter and property-expansion flag
     * @param observer receives one sorted list of matching model descriptions, or a gRPC error
     */
    @Override public void getAllModels(ListModelsRequest request, StreamObserver<ListModelsResponse> observer) {
        support.execute(observer, () -> models.list(request.getModel(), request.getIncludeProperties()));
    }

    /**
     * Looks up one deployed model by qualified name, without scanning every model. A bare prefix is invalid.
     * Expanded properties are declared properties only; a missing model yields {@code NOT_FOUND}.
     *
     * @param request qualified model name and optional property-expansion flag
     * @param observer receives one model description, or a gRPC error
     */
    @Override public void getModel(ListModelsRequest request, StreamObserver<ModelInfo> observer) {
        support.execute(observer, () -> models.getModel(request.getModel(), request.getIncludeProperties()));
    }
}
