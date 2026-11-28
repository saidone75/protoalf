package org.saidone.protoalf.platform;

import lombok.val;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentData;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.security.AccessStatus;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.namespace.QName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeMapperTest {

    private static final NodeRef FIRST = new NodeRef("workspace://SpacesStore/first");
    private static final NodeRef SECOND = new NodeRef("workspace://SpacesStore/second");
    private static final NodeRef PARENT = new NodeRef("workspace://SpacesStore/primary");
    private static final NodeRef OTHER = new NodeRef("workspace://SpacesStore/other-primary");
    private final ServiceRegistry services = mock(ServiceRegistry.class);
    private final NodeService nodes = mock(NodeService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private NodeMapper mapper;

    @BeforeAll
    static void initializeAuthentication() throws Exception {
        val authentication = new AuthenticationUtil();
        authentication.setDefaultAdminUserName("admin");
        authentication.setDefaultGuestUserName("guest");
        authentication.afterPropertiesSet();
    }

    @BeforeEach
    void repository() {
        when(services.getNodeService()).thenReturn(nodes);
        when(services.getPermissionService()).thenReturn(permissions);
        when(nodes.getAspects(any())).thenReturn(Set.of(ContentModel.ASPECT_TITLED, ContentModel.ASPECT_AUDITABLE));
        when(permissions.hasPermission(any(NodeRef.class), eq(PermissionService.READ_PROPERTIES))).thenReturn(AccessStatus.ALLOWED);
        AuthenticationUtil.setFullyAuthenticatedUser("reader");
        mapper = new NodeMapper(services);
    }

    @AfterEach
    void clearAuthentication() {
        AuthenticationUtil.clearCurrentSecurityContext();
    }

    @Test
    void listingReusesTypeAndPropertiesAndPreservesTheCompleteSnapshot() {
        val properties = Map.<QName, Serializable>of(ContentModel.PROP_NAME, "file.txt",
                ContentModel.PROP_TITLE, "Title", ContentModel.PROP_MODIFIED, new Date(123),
                ContentModel.PROP_CONTENT, new ContentData("store://private", "text/plain", 42, "UTF-8"),
                QName.createQName("urn:test", "numbers"), new ArrayList<>(List.of(1L, 2L)));
        val file = file(FIRST, PARENT, properties);
        when(nodes.getType(FIRST)).thenReturn(ContentModel.TYPE_CONTENT);
        when(nodes.getProperties(FIRST)).thenReturn(properties);
        val expected = mapper.map(FIRST);
        clearInvocations(nodes, permissions);
        val actual = mapper.listing().map(file);
        assertEquals(expected, actual);
        assertFalse(actual.toString().contains("store://private"));
        verify(nodes, never()).getType(any());
        verify(nodes, never()).getProperties(any());
        verify(nodes).getAspects(FIRST);
        assertEquals("reader", AuthenticationUtil.getRunAsUser());
    }

    @Test
    void sharedParentIsCheckedOnceAsTheCallerAndLookupStillRunsAsSystem() {
        val first = file(FIRST, PARENT, Map.of());
        val second = file(SECOND, PARENT, Map.of());
        when(nodes.getPrimaryParent(any())).thenAnswer(invocation -> {
            assertEquals(AuthenticationUtil.SYSTEM_USER_NAME, AuthenticationUtil.getRunAsUser());
            return new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, PARENT, ContentModel.PROP_NAME, invocation.getArgument(0));
        });
        when(permissions.hasPermission(PARENT, PermissionService.READ_PROPERTIES)).thenAnswer(invocation -> {
            assertEquals("reader", AuthenticationUtil.getRunAsUser());
            return AccessStatus.ALLOWED;
        });
        val page = mapper.listing();
        assertEquals(PARENT.toString(), page.map(first).getParentRef());
        assertEquals(PARENT.toString(), page.map(second).getParentRef());
        verify(permissions, times(1)).hasPermission(PARENT, PermissionService.READ_PROPERTIES);
        verify(nodes, times(2)).getPrimaryParent(any());
    }

    @Test
    void deniedParentsAreCachedOnlyWithinOnePageAndNeverAcrossCallers() {
        val first = file(FIRST, PARENT, Map.of());
        val second = file(SECOND, PARENT, Map.of());
        when(permissions.hasPermission(PARENT, PermissionService.READ_PROPERTIES)).thenReturn(AccessStatus.DENIED, AccessStatus.ALLOWED);
        val page = mapper.listing();
        assertEquals("", page.map(first).getParentRef());
        assertEquals("", page.map(second).getParentRef());
        verify(permissions, times(1)).hasPermission(PARENT, PermissionService.READ_PROPERTIES);
        AuthenticationUtil.setFullyAuthenticatedUser("another-reader");
        assertEquals(PARENT.toString(), mapper.listing().map(first).getParentRef());
        verify(permissions, times(2)).hasPermission(PARENT, PermissionService.READ_PROPERTIES);
    }

    @Test
    void secondaryAssociationsStillReturnTheActualPrimaryParent() {
        val page = mapper.listing();
        assertEquals(PARENT.toString(), page.map(file(FIRST, PARENT, Map.of())).getParentRef());
        when(permissions.hasPermission(OTHER, PermissionService.READ_PROPERTIES)).thenReturn(AccessStatus.DENIED);
        assertEquals("", page.map(file(SECOND, OTHER, Map.of())).getParentRef());
        verify(permissions).hasPermission(PARENT, PermissionService.READ_PROPERTIES);
        verify(permissions).hasPermission(OTHER, PermissionService.READ_PROPERTIES);
    }

    @Test
    void missingPrimaryParentsDoNotTriggerPermissionChecks() {
        val file = file(FIRST, null, Map.of());
        assertEquals("", mapper.listing().map(file).getParentRef());
        verifyNoInteractions(permissions);
    }

    private FileInfo file(NodeRef ref, NodeRef parent, Map<QName, Serializable> properties) {
        val file = mock(FileInfo.class);
        when(file.getNodeRef()).thenReturn(ref);
        when(file.getType()).thenReturn(ContentModel.TYPE_CONTENT);
        when(file.getProperties()).thenReturn(properties);
        when(nodes.getPrimaryParent(ref)).thenReturn(parent == null ? null : new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, parent, ContentModel.PROP_NAME, ref));
        return file;
    }
}
