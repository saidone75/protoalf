package org.saidone.protoalf.platform;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.val;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.nodelocator.NodeLocatorService;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.dictionary.AspectDefinition;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.cmr.dictionary.TypeDefinition;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.model.FileNotFoundException;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.MimetypeService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.namespace.NamespaceException;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saidone.protoalf.api.Content;

import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class NodeWriterTest {

    private static final NodeRef PARENT = new NodeRef("workspace://SpacesStore/parent");
    private static final NodeRef CREATED = new NodeRef("workspace://SpacesStore/created");
    private static final NodeRef COMPANY_HOME = new NodeRef("workspace://SpacesStore/company-home");
    private static final QName DESCRIPTION = ContentModel.PROP_DESCRIPTION;
    private static final QName FROM = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "from");
    private final ServiceRegistry services = mock(ServiceRegistry.class);
    private final DictionaryService dictionary = mock(DictionaryService.class);
    private final FileFolderService files = mock(FileFolderService.class);
    private final NodeService nodes = mock(NodeService.class);
    private final ContentService contents = mock(ContentService.class);
    private final NodeLocatorService locator = mock(NodeLocatorService.class);
    private NodeWriter writer;

    @BeforeEach
    void setUp() {
        val namespaces = mock(NamespaceService.class);
        when(namespaces.getNamespaceURI("cm")).thenReturn(NamespaceService.CONTENT_MODEL_1_0_URI);
        when(namespaces.getNamespaceURI("sys")).thenReturn(NamespaceService.SYSTEM_MODEL_1_0_URI);
        when(namespaces.getNamespaceURI("zz")).thenThrow(new NamespaceException("Namespace prefix zz is not mapped"));
        val mimetypes = mock(MimetypeService.class);
        when(mimetypes.guessMimetype("notes.txt")).thenReturn("text/plain");
        when(services.getNamespaceService()).thenReturn(namespaces);
        when(services.getDictionaryService()).thenReturn(dictionary);
        when(services.getFileFolderService()).thenReturn(files);
        when(services.getNodeService()).thenReturn(nodes);
        when(services.getContentService()).thenReturn(contents);
        when(services.getMimetypeService()).thenReturn(mimetypes);
        when(services.getNodeLocatorService()).thenReturn(locator);
        when(dictionary.getType(any(QName.class))).thenReturn(mock(TypeDefinition.class));
        when(dictionary.isSubClass(ContentModel.TYPE_CONTENT, ContentModel.TYPE_CONTENT)).thenReturn(true);
        when(dictionary.isSubClass(ContentModel.TYPE_FOLDER, ContentModel.TYPE_FOLDER)).thenReturn(true);
        property(DESCRIPTION, "java.lang.String", false);
        property(ContentModel.PROP_CREATED, "java.util.Date", true);
        property(FROM, "java.util.Date", false);
        writer = new NodeWriter(services);
    }

    private void property(QName name, String javaClass, boolean isProtected) {
        val type = mock(DataTypeDefinition.class);
        when(type.getJavaClassName()).thenReturn(javaClass);
        when(type.getName()).thenReturn(QName.createQName(NamespaceService.DICTIONARY_MODEL_1_0_URI, javaClass));
        val definition = mock(PropertyDefinition.class);
        when(definition.getDataType()).thenReturn(type);
        when(definition.isProtected()).thenReturn(isProtected);
        when(dictionary.getProperty(name)).thenReturn(definition);
    }

    private static FileInfo file(NodeRef ref) {
        val info = mock(FileInfo.class);
        when(info.getNodeRef()).thenReturn(ref);
        return info;
    }

    @Test
    void createDocumentSetsPropertiesAndWritesContent() {
        val created = file(CREATED);
        when(files.create(PARENT, "notes.txt", ContentModel.TYPE_CONTENT)).thenReturn(created);
        val contentWriter = mock(ContentWriter.class);
        when(contents.getWriter(CREATED, ContentModel.PROP_CONTENT, true)).thenReturn(contentWriter);
        val ref = writer.create(PARENT, "notes.txt", "", Map.of("cm:description", "Meeting notes"), Content.newBuilder().setData(ByteString.copyFromUtf8("hello")).build());
        assertEquals(CREATED, ref);
        verify(nodes).addProperties(CREATED, Map.<QName, Serializable>of(DESCRIPTION, "Meeting notes"));
        verify(contentWriter).setMimetype("text/plain");
        verify(contentWriter).setEncoding("UTF-8");
        verify(contentWriter).putContent(any(InputStream.class));
    }

    @Test
    void createFolderWithoutPropertiesOnlyCreatesTheFolder() {
        val created = file(CREATED);
        when(files.create(PARENT, "Projects", ContentModel.TYPE_FOLDER)).thenReturn(created);
        assertEquals(CREATED, writer.create(PARENT, "Projects", "cm:folder", Map.of(), null));
        verify(nodes, never()).addProperties(any(), anyMap());
        verifyNoInteractions(contents);
    }

    @Test
    void invalidRequestsAreRejectedBeforeTouchingTheRepository() {
        val invalidProperties = Map.of("cm:name is set through the name field", Map.of("cm:name", "x"), "protected property", Map.of("cm:created", "2026-01-01T00:00:00Z"), "system property", Map.of("sys:node-uuid", "x"), "unknown property", Map.of("cm:doesNotExist", "x"), "unknown prefix", Map.of("zz:title", "x"), "value of the wrong type", Map.of("cm:from", "not a date"));
        invalidProperties.forEach((reason, properties) -> assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "notes.txt", "", properties, null), reason));
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, " ", "", Map.of(), null), "blank name");
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "x".repeat(NodeWriter.MAX_NAME_LENGTH + 1), "", Map.of(), null), "long name");
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "Projects", "cm:folder", Map.of(), Content.newBuilder().setData(ByteString.copyFromUtf8("x")).build()), "folder with content");
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "x", "cm:person", Map.of(), null), "not a file or folder");
        verifyNoInteractions(files, contents);
        verify(nodes, never()).addProperties(any(), anyMap());
    }

    @Test
    void datesAreParsedFromIso8601() {
        val values = writer.properties(Map.of("cm:from", "2026-11-02T10:00:00Z"));
        assertEquals(new Date(1793613600000L), values.get(FROM));
    }

    @Test
    void updateRenamesThenSetsProperties() throws Exception {
        writer.update(CREATED, "renamed.txt", "", Map.of("cm:description", "d"));
        val order = inOrder(files, nodes);
        order.verify(files).rename(CREATED, "renamed.txt");
        order.verify(nodes).addProperties(CREATED, Map.<QName, Serializable>of(DESCRIPTION, "d"));
        verify(nodes, never()).setType(any(), any());
        assertThrows(IllegalArgumentException.class, () -> writer.update(CREATED, "", "", Map.of()));
    }

    @Test
    void updateChangesTheTypeBeforeSettingProperties() {
        val invoice = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "invoice");
        when(dictionary.isSubClass(invoice, ContentModel.TYPE_CONTENT)).thenReturn(true);
        when(nodes.getType(CREATED)).thenReturn(ContentModel.TYPE_CONTENT);
        writer.update(CREATED, "", "cm:invoice", Map.of("cm:description", "d"));
        val order = inOrder(nodes);
        order.verify(nodes).setType(CREATED, invoice);
        order.verify(nodes).addProperties(CREATED, Map.<QName, Serializable>of(DESCRIPTION, "d"));
    }

    @Test
    void invalidTypeChangesAreRejectedBeforeTouchingTheRepository() {
        when(nodes.getType(CREATED)).thenReturn(ContentModel.TYPE_CONTENT);
        when(dictionary.getType(QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "doesNotExist"))).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> writer.update(CREATED, "x.txt", "cm:folder", Map.of()), "document to folder");
        assertThrows(IllegalArgumentException.class, () -> writer.update(CREATED, "x.txt", "cm:person", Map.of()), "not a file or folder");
        assertThrows(IllegalArgumentException.class, () -> writer.update(CREATED, "x.txt", "cm:doesNotExist", Map.of()), "unknown type");
        assertThrows(IllegalArgumentException.class, () -> writer.update(CREATED, "x.txt", "zz:type", Map.of()), "unknown prefix");
        verifyNoInteractions(files);
        verify(nodes, never()).setType(any(), any());
    }

    @Test
    void contentCanOnlyBeWrittenToDocuments() {
        when(nodes.getType(CREATED)).thenReturn(ContentModel.TYPE_FOLDER);
        val content = Content.newBuilder().setData(ByteString.copyFromUtf8("x")).build();
        assertThrows(IllegalArgumentException.class, () -> writer.updateContent(CREATED, content));
        verifyNoInteractions(contents);
    }

    @Test
    void moveOfAMissingNodeIsNotFound() throws Exception {
        when(files.move(CREATED, PARENT, null)).thenThrow(new FileNotFoundException(CREATED));
        val error = assertThrows(StatusRuntimeException.class, () -> writer.move(CREATED, PARENT, ""));
        assertEquals(Status.Code.NOT_FOUND, error.getStatus().getCode());
    }

    @Test
    void copyKeepsTheNameUnlessANewOneIsGiven() throws Exception {
        val copied = file(PARENT);
        when(files.copy(eq(CREATED), eq(PARENT), isNull())).thenReturn(copied);
        when(files.copy(CREATED, PARENT, "copy.txt")).thenReturn(copied);
        writer.copy(CREATED, PARENT, "");
        writer.copy(CREATED, PARENT, "copy.txt");
        verify(files).copy(CREATED, PARENT, null);
        verify(files).copy(CREATED, PARENT, "copy.txt");
    }

    @Test
    void wellKnownFoldersCannotBeDeleted() {
        when(locator.getNode("companyhome", null, null)).thenReturn(COMPANY_HOME);
        assertThrows(IllegalArgumentException.class, () -> writer.delete(COMPANY_HOME));
        verify(nodes, never()).deleteNode(any());
        writer.delete(CREATED);
        verify(nodes).deleteNode(CREATED);
    }

    @Test
    void multiValuedPropertiesReceiveAList() {
        when(dictionary.getProperty(DESCRIPTION).isMultiValued()).thenReturn(true);
        assertEquals(List.of("a"), writer.properties(Map.of("cm:description", "a")).get(DESCRIPTION));
    }

    private static final QName TITLED = ContentModel.ASPECT_TITLED;
    private static final QName SYS_ASPECT = QName.createQName(NamespaceService.SYSTEM_MODEL_1_0_URI, "referenceable");

    private void aspect(QName name) {
        when(dictionary.getAspect(name)).thenReturn(mock(AspectDefinition.class));
    }

    @Test
    void singleValuedPropertyRejectsMoreThanOneValue() {
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "notes.txt", "", Map.of(), Map.of("cm:description", List.of("a", "b")), List.of(), null));
        verifyNoInteractions(files);
        verify(nodes, never()).addProperties(any(), anyMap());
    }

    @Test
    void singleValuedPropertyAcceptsZeroOrOneValue() {
        val created = file(CREATED);
        when(files.create(PARENT, "notes.txt", ContentModel.TYPE_CONTENT)).thenReturn(created);
        writer.create(PARENT, "notes.txt", "", Map.of(), Map.of("cm:description", List.of("a")), List.of(), null);
        verify(nodes).addProperties(CREATED, Map.<QName, Serializable>of(DESCRIPTION, "a"));
    }

    @Test
    void aPropertyCannotBeGivenInBothMaps() {
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "notes.txt", "", Map.of("cm:description", "a"), Map.of("cm:description", List.of("b")), List.of(), null));
        verifyNoInteractions(files);
    }

    @Test
    void reservedProtectedAndSystemPropertiesAreRejectedInPropertyValues() {
        for (val key : new String[] { "cm:name", "cm:content", "cm:created", "sys:node-uuid", "cm:doesNotExist" }) assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "notes.txt", "", Map.of(), Map.of(key, List.of("x")), List.of(), null), key);
        verifyNoInteractions(files);
    }

    @Test
    void multiValuedPropertyIsSetAsAList() {
        when(dictionary.getProperty(DESCRIPTION).isMultiValued()).thenReturn(true);
        val created = file(CREATED);
        when(files.create(PARENT, "notes.txt", ContentModel.TYPE_CONTENT)).thenReturn(created);
        writer.create(PARENT, "notes.txt", "", Map.of(), Map.of("cm:description", List.of("a", "b")), List.of(), null);
        verify(nodes).addProperties(CREATED, Map.<QName, Serializable>of(DESCRIPTION, new ArrayList<>(List.of("a", "b"))));
        writer.update(CREATED, "", "", Map.of(), Map.of("cm:description", List.of()), List.of(), List.of());
        verify(nodes).addProperties(CREATED, Map.<QName, Serializable>of(DESCRIPTION, new ArrayList<>()));
    }

    @Test
    void unknownOrSystemAspectsAreRejectedBeforeTouchingTheRepository() {
        aspect(TITLED);
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "notes.txt", "", Map.of(), Map.of(), List.of("cm:doesNotExist"), null), "unknown aspect");
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "notes.txt", "", Map.of(), Map.of(), List.of("sys:referenceable"), null), "sys aspect");
        assertThrows(IllegalArgumentException.class, () -> writer.update(CREATED, "", "", Map.of(), Map.of(), List.of("sys:referenceable"), List.of()), "sys aspect on update");
        assertThrows(IllegalArgumentException.class, () -> writer.update(CREATED, "", "", Map.of(), Map.of(), List.of(), List.of("cm:doesNotExist")), "unknown aspect on remove");
        verifyNoInteractions(files);
        verify(nodes, never()).addAspect(any(), any(), anyMap());
        verify(nodes, never()).removeAspect(any(), any());
    }

    @Test
    void aspectIsAddedBeforePropertiesAndOnlyWhenMissing() {
        aspect(TITLED);
        val created = file(CREATED);
        when(files.create(PARENT, "notes.txt", ContentModel.TYPE_CONTENT)).thenReturn(created);
        writer.create(PARENT, "notes.txt", "", Map.of(), Map.of("cm:description", List.of("d")), List.of("cm:titled"), null);
        val order = inOrder(nodes);
        order.verify(nodes).addAspect(eq(CREATED), eq(TITLED), anyMap());
        order.verify(nodes).addProperties(CREATED, Map.<QName, Serializable>of(DESCRIPTION, "d"));
        clearInvocations(nodes);
        when(nodes.hasAspect(CREATED, TITLED)).thenReturn(true);
        writer.update(CREATED, "", "", Map.of(), Map.of(), List.of("cm:titled"), List.of());
        verify(nodes, never()).addAspect(any(), any(), anyMap());
    }

    @Test
    void updateRemovesOnlyAspectsTheNodeHasAfterTheRename() throws Exception {
        aspect(TITLED);
        when(nodes.hasAspect(CREATED, TITLED)).thenReturn(true);
        val absent = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "absent");
        aspect(absent);
        writer.update(CREATED, "renamed.txt", "", Map.of(), Map.of(), List.of(), List.of("cm:titled", "cm:absent"));
        val order = inOrder(files, nodes);
        order.verify(files).rename(CREATED, "renamed.txt");
        order.verify(nodes).removeAspect(CREATED, TITLED);
        verify(nodes, never()).removeAspect(CREATED, absent);
    }
}
