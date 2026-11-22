package org.saidone.protoalf.platform;

import io.grpc.stub.StreamObserver;
import lombok.val;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.version.Version;
import org.alfresco.service.cmr.version.VersionType;
import org.saidone.protoalf.api.*;

/** Adapts Alfresco VersionService.getVersionHistory, including frozen node metadata. */
public final class GrpcVersionService extends VersionServiceGrpc.VersionServiceImplBase {

    private final ServiceRegistry services;
    private final GrpcServiceSupport support;
    private final NodeMapper mapper;

    public GrpcVersionService(ServiceRegistry services) {
        this.services = services;
        this.support = new GrpcServiceSupport(services);
        this.mapper = new NodeMapper(services);
    }

    /**
     * Version history of a document, newest first as in Alfresco; empty when it is not versionable. version_ref is the
     * frozen node of each version: GetReader and DownloadContent accept it to read that version.
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
