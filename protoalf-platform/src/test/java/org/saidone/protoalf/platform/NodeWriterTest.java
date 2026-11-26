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
import org.saidone.protoalf.api.Property;
import org.saidone.protoalf.api.PropertyValue;

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
        val ref = writer.create(PARENT, "notes.txt", "", Map.of("cm:description", text("Meeting notes")), Content.newBuilder().setData(ByteString.copyFromUtf8("hello")).build());
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
        val invalidProperties = Map.of("cm:name is set through the name field", Map.of("cm:name", text("x")), "protected property", Map.of("cm:created", text("2026-01-01T00:00:00Z")), "system property", Map.of("sys:node-uuid", text("x")), "unknown property", Map.of("cm:doesNotExist", text("x")), "unknown prefix", Map.of("zz:title", text("x")), "value of the wrong type", Map.of("cm:from", text("not a date")));
        invalidProperties.forEach((reason, properties) -> assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "notes.txt", "", properties, null), reason));
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, " ", "", Map.of(), null), "blank name");
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "x".repeat(NodeWriter.MAX_NAME_LENGTH + 1), "", Map.of(), null), "long name");
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "Projects", "cm:folder", Map.of(), Content.newBuilder().setData(ByteString.copyFromUtf8("x")).build()), "folder with content");
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "x", "cm:person", Map.of(), null), "not a file or folder");
        verifyNoInteractions(files, contents);
        verify(nodes, never()).addProperties(any(), anyMap());
    }

    @Test
    void datesUseExplicitTimestamps() {
        val values = writer.properties(Map.of("cm:from", Property.newBuilder().addValues(PropertyValue.newBuilder().setTimestampMillis(1793613600000L)).build()));
        assertEquals(new Date(1793613600000L), values.get(FROM));
    }

    @Test
    void updateRenamesThenSetsProperties() throws Exception {
        writer.update(CREATED, "renamed.txt", "", Map.of("cm:description", text("d")));
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
        writer.update(CREATED, "", "cm:invoice", Map.of("cm:description", text("d")));
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
    void scalarIsNotImplicitlyWrappedAsAList() {
        when(dictionary.getProperty(DESCRIPTION).isMultiValued()).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> writer.properties(Map.of("cm:description", text("a"))));
    }

    private static Property text(String value) {
        return Property.newBuilder().addValues(PropertyValue.newBuilder().setStringValue(value)).build();
    }

    private static Property texts(String... values) {
        val result = Property.newBuilder().setMultiValued(true);
        for (val value : values) result.addValues(PropertyValue.newBuilder().setStringValue(value));
        return result.build();
    }

    @Test
    void typedValuesAreValidatedAgainstDictionaryTypes() {
        val cases = Map.of(
                "java.lang.Integer", PropertyValue.newBuilder().setIntegerValue(12).build(),
                "java.lang.Long", PropertyValue.newBuilder().setIntegerValue(Long.MAX_VALUE).build(),
                "java.lang.Float", PropertyValue.newBuilder().setDoubleValue(1.5).build(),
                "java.lang.Double", PropertyValue.newBuilder().setDoubleValue(2.5).build(),
                "java.lang.Boolean", PropertyValue.newBuilder().setBooleanValue(false).build(),
                "org.alfresco.service.cmr.repository.NodeRef", PropertyValue.newBuilder().setNodeRefValue(CREATED.toString()).build(),
                "org.alfresco.service.namespace.QName", PropertyValue.newBuilder().setQnameValue("cm:content").build());
        for (val entry : cases.entrySet()) {
            property(DESCRIPTION, entry.getKey(), false);
            val input = Property.newBuilder().addValues(entry.getValue()).build();
            val converted = writer.properties(Map.of("cm:description", input)).get(DESCRIPTION);
            assertEquals(entry.getKey(), converted.getClass().getName());
            assertThrows(IllegalArgumentException.class, () -> writer.properties(Map.of("cm:description", text("12"))));
        }
    }

    @Test
    void integerOverflowAndNonFiniteNumbersAreRejected() {
        property(DESCRIPTION, "java.lang.Integer", false);
        val overflow = Property.newBuilder().addValues(PropertyValue.newBuilder().setIntegerValue(Long.MAX_VALUE)).build();
        assertThrows(IllegalArgumentException.class, () -> writer.properties(Map.of("cm:description", overflow)));
        property(DESCRIPTION, "java.lang.Double", false);
        val nan = Property.newBuilder().addValues(PropertyValue.newBuilder().setDoubleValue(Double.NaN)).build();
        assertThrows(IllegalArgumentException.class, () -> writer.properties(Map.of("cm:description", nan)));
    }

    @Test
    void malformedValuesAndNullListElementsAreRejected() {
        val invalidValues = List.of(PropertyValue.getDefaultInstance(),
                PropertyValue.newBuilder().setNullValue(false).build(),
                PropertyValue.newBuilder().setContentValue(org.saidone.protoalf.api.ContentMetadata.getDefaultInstance()).build());
        for (val value : invalidValues) {
            val input = Property.newBuilder().addValues(value).build();
            assertThrows(IllegalArgumentException.class, () -> writer.properties(Map.of("cm:description", input)));
        }
        when(dictionary.getProperty(DESCRIPTION).isMultiValued()).thenReturn(true);
        val nullElement = Property.newBuilder().setMultiValued(true).addValues(PropertyValue.newBuilder().setNullValue(true)).build();
        assertThrows(IllegalArgumentException.class, () -> writer.properties(Map.of("cm:description", nullElement)));
        val mixed = texts("a").toBuilder().addValues(PropertyValue.newBuilder().setIntegerValue(1)).build();
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "notes.txt", "", Map.of("cm:description", mixed), null));
        verifyNoInteractions(files);
    }

    private static final QName TITLED = ContentModel.ASPECT_TITLED;
    private static final QName SYS_ASPECT = QName.createQName(NamespaceService.SYSTEM_MODEL_1_0_URI, "referenceable");

    private void aspect(QName name) {
        when(dictionary.getAspect(name)).thenReturn(mock(AspectDefinition.class));
    }

    @Test
    void listIsNotImplicitlyUnwrappedAsAScalar() {
        for (val input : List.of(texts(), texts("a"), texts("a", "b")))
            assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "notes.txt", "", Map.of("cm:description", input), null));
        verifyNoInteractions(files);
    }

    @Test
    void scalarRequiresExactlyOneExplicitValue() {
        for (val input : List.of(Property.getDefaultInstance(), Property.newBuilder().addAllValues(texts("a", "b").getValuesList()).build()))
            assertThrows(IllegalArgumentException.class, () -> writer.properties(Map.of("cm:description", input)));
    }

    @Test
    void nullClearsAScalarWhileAnOmittedPropertyStaysAbsent() {
        val nullProperty = Property.newBuilder().addValues(PropertyValue.newBuilder().setNullValue(true)).build();
        val converted = writer.properties(Map.of("cm:description", nullProperty));
        assertTrue(converted.containsKey(DESCRIPTION));
        assertNull(converted.get(DESCRIPTION));
        assertTrue(writer.properties(Map.of()).isEmpty());
    }

    @Test
    void duplicateQualifiedNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> writer.properties(Map.of("cm:description", text("a"), DESCRIPTION.toString(), text("b"))));
    }

    @Test
    void reservedProtectedAndSystemPropertiesAreRejectedInLists() {
        for (val key : new String[] { "cm:name", "cm:content", "cm:created", "sys:node-uuid", "cm:doesNotExist" })
            assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "notes.txt", "", Map.of(key, texts("x")), null), key);
        verifyNoInteractions(files);
    }

    @Test
    void multiValuedPropertyPreservesMultipleSingletonAndEmptyLists() {
        when(dictionary.getProperty(DESCRIPTION).isMultiValued()).thenReturn(true);
        val created = file(CREATED);
        when(files.create(PARENT, "notes.txt", ContentModel.TYPE_CONTENT)).thenReturn(created);
        writer.create(PARENT, "notes.txt", "", Map.of("cm:description", texts("a", "b")), List.of(), null);
        verify(nodes).addProperties(CREATED, Map.<QName, Serializable>of(DESCRIPTION, new ArrayList<>(List.of("a", "b"))));
        assertEquals(List.of("a"), writer.properties(Map.of("cm:description", texts("a"))).get(DESCRIPTION));
        writer.update(CREATED, "", "", Map.of("cm:description", texts()), List.of(), List.of());
        verify(nodes).addProperties(CREATED, Map.<QName, Serializable>of(DESCRIPTION, new ArrayList<>()));
    }

    @Test
    void unknownOrSystemAspectsAreRejectedBeforeTouchingTheRepository() {
        aspect(TITLED);
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "notes.txt", "", Map.of(), List.of("cm:doesNotExist"), null), "unknown aspect");
        assertThrows(IllegalArgumentException.class, () -> writer.create(PARENT, "notes.txt", "", Map.of(), List.of("sys:referenceable"), null), "sys aspect");
        assertThrows(IllegalArgumentException.class, () -> writer.update(CREATED, "", "", Map.of(), List.of("sys:referenceable"), List.of()), "sys aspect on update");
        assertThrows(IllegalArgumentException.class, () -> writer.update(CREATED, "", "", Map.of(), List.of(), List.of("cm:doesNotExist")), "unknown aspect on remove");
        verifyNoInteractions(files);
        verify(nodes, never()).addAspect(any(), any(), anyMap());
        verify(nodes, never()).removeAspect(any(), any());
    }

    @Test
    void aspectIsAddedBeforePropertiesAndOnlyWhenMissing() {
        aspect(TITLED);
        val created = file(CREATED);
        when(files.create(PARENT, "notes.txt", ContentModel.TYPE_CONTENT)).thenReturn(created);
        writer.create(PARENT, "notes.txt", "", Map.of("cm:description", text("d")), List.of("cm:titled"), null);
        val order = inOrder(nodes);
        order.verify(nodes).addAspect(eq(CREATED), eq(TITLED), anyMap());
        order.verify(nodes).addProperties(CREATED, Map.<QName, Serializable>of(DESCRIPTION, "d"));
        clearInvocations(nodes);
        when(nodes.hasAspect(CREATED, TITLED)).thenReturn(true);
        writer.update(CREATED, "", "", Map.of(), List.of("cm:titled"), List.of());
        verify(nodes, never()).addAspect(any(), any(), anyMap());
    }

    @Test
    void updateRemovesOnlyAspectsTheNodeHasAfterTheRename() throws Exception {
        aspect(TITLED);
        when(nodes.hasAspect(CREATED, TITLED)).thenReturn(true);
        val absent = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "absent");
        aspect(absent);
        writer.update(CREATED, "renamed.txt", "", Map.of(), List.of(), List.of("cm:titled", "cm:absent"));
        val order = inOrder(files, nodes);
        order.verify(files).rename(CREATED, "renamed.txt");
        order.verify(nodes).removeAspect(CREATED, TITLED);
        verify(nodes, never()).removeAspect(CREATED, absent);
    }
}
