package org.saidone.protoalf.platform;

import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import lombok.val;
import org.alfresco.repo.node.archive.NodeArchiveService;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class GrpcServiceContractTest {

    @Test
    void serviceBoundariesAndMethodNamesFollowAlfresco() {
        val services = mock(ServiceRegistry.class);
        val expected = Map.of("NodeService", Set.of("GetProperties", "AddProperties", "GetType", "SetType", "GetAspects", "AddAspect", "RemoveAspect", "DeleteNode", "GetNode", "UpdateNode"), "FileFolderService", Set.of("List", "Create", "Move", "Copy"), "ContentService", Set.of("GetReader", "GetWriter", "DownloadContent", "UploadContent"), "VersionService", Set.of("GetVersionHistory"), "SearchService", Set.of("Query"), "DictionaryService", Set.of("GetAllModels", "GetModel"), "NodeArchiveService", Set.of("ListArchivedNodes", "PurgeArchivedNode", "PurgeAllArchivedNodes"), "AuthenticationService", Set.of("Authenticate"));
        for (val service : List.<BindableService>of(new GrpcNodeService(services), new GrpcNodeArchiveService(services, mock(NodeArchiveService.class)),new GrpcFileFolderService(services), new GrpcContentService(services), new GrpcVersionService(services), new GrpcSearchService(services), new GrpcDictionaryService(services), new GrpcAuthenticationService(mock(AuthenticationService.class)))) {
            val descriptor = service.bindService().getServiceDescriptor();
            val name = descriptor.getName().substring("protoalf.v1.".length());
            val methods = descriptor.getMethods().stream().map(MethodDescriptor::getBareMethodName).collect(Collectors.toSet());
            assertEquals(expected.get(name), methods, name);
        }
    }
}
