package org.saidone.protoalf.platform;

import io.grpc.stub.StreamObserver;
import lombok.val;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.version.VersionService;
import org.alfresco.service.cmr.version.Version;
import org.alfresco.service.cmr.version.VersionType;
import org.saidone.protoalf.api.*;

/**
 * Exposes Alfresco document version histories through gRPC.
 *
 * <p>Each history entry contains its version label, kind, modification metadata and frozen node snapshot. The
 * frozen node reference can be passed to {@link GrpcContentService#getReader} or {@link
 * GrpcContentService#downloadContent} to retrieve that version's content. Frozen snapshots omit a primary
 * parent because they do not belong to the live node hierarchy.
 *
 * <p>History reads run in a read-only transaction with the calling user's permissions.
 *
 * @see VersionService
 */
public final class GrpcVersionService extends VersionServiceGrpc.VersionServiceImplBase {

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
     * Creates the adapter and its repository helpers without starting repository work.
     *
     * @param services Alfresco service registry; repository work uses its secured services
     */
    public GrpcVersionService(ServiceRegistry services) {
        this.services = services;
        this.support = new GrpcServiceSupport(services);
        this.mapper = new NodeMapper(services);
    }

    /**
     * Reads version history in the order returned by Alfresco, normally newest first. Nodes without a history
     * return an empty list. Each entry includes a frozen snapshot whose reference can be used by the content
     * service to read that version.
     *
     * @param request document reference whose version history is required
     * @param observer receives one list of version descriptions and frozen snapshots, or a gRPC error
     */
    @Override public void getVersionHistory(ListVersionsRequest request, StreamObserver<ListVersionsResponse> observer) {
        support.execute(observer, () -> {
            val response = ListVersionsResponse.newBuilder();
            val history = services.getVersionService().getVersionHistory(support.ref(request.getNodeRef()));
            if (history != null)
                for (val version : history.getAllVersions()) response.addVersions(version(version));
            return response.build();
        });
    }

    /**
     * Maps one repository version to its transport description, preserving optional modification metadata and
     * omitting the live primary parent.
     *
     * @param version repository version with a frozen-state node
     * @return the version label, kind, frozen snapshot and available modification metadata
     */
    private VersionInfo version(Version version) {
        val frozen = version.getFrozenStateNodeRef();
        val info = VersionInfo.newBuilder().setLabel(version.getVersionLabel()).setVersionRef(frozen.toString()).setMajor(version.getVersionType() == VersionType.MAJOR).setNode(mapper.mapVersion(frozen));
        if (version.getDescription() != null)
            info.setDescription(version.getDescription());
        if (version.getFrozenModifiedDate() != null)
            info.setModifiedMillis(version.getFrozenModifiedDate().getTime());
        if (version.getFrozenModifier() != null)
            info.setModifier(version.getFrozenModifier());
        return info.build();
    }
}
