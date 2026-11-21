package org.saidone.protoalf.platform;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.alfresco.repo.i18n.MessageService;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.dictionary.AspectDefinition;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.dictionary.ModelDefinition;
import org.alfresco.service.cmr.dictionary.NamespaceDefinition;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.cmr.dictionary.TypeDefinition;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saidone.protoalf.api.ClassInfo;
import org.saidone.protoalf.api.ModelInfo;
import org.saidone.protoalf.api.PropertyInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelListerTest {

    private static final String CM = NamespaceService.CONTENT_MODEL_1_0_URI;
    private static final String ACME = "http://acme.example/model/1.0";
    private static final QName CM_MODEL = QName.createQName(CM, "contentmodel");
    private static final QName ACME_MODEL = QName.createQName(ACME, "acmemodel");
    private static final QName CM_CONTENT = QName.createQName(CM, "content");
    private static final QName CM_TITLED = QName.createQName(CM, "titled");
    private static final QName CM_TITLE = QName.createQName(CM, "title");
    private static final QName ACME_INVOICE = QName.createQName(ACME, "invoice");
    private static final QName ACME_NUMBER = QName.createQName(ACME, "number");

    private final DictionaryService dictionary = mock(DictionaryService.class);
    private ModelLister lister;

    @BeforeEach
    void setUp() {
        var services = mock(ServiceRegistry.class);
        var namespaces = mock(NamespaceService.class);
        when(namespaces.getPrefixes(CM)).thenReturn(List.of("cm"));
        when(namespaces.getPrefixes(ACME)).thenReturn(List.of("acme"));
        when(namespaces.getPrefixes(NamespaceService.DICTIONARY_MODEL_1_0_URI)).thenReturn(List.of("d"));
        when(services.getNamespaceService()).thenReturn(namespaces);
        when(services.getDictionaryService()).thenReturn(dictionary);
        when(services.getMessageService()).thenReturn(mock(MessageService.class));

        model(CM_MODEL, "cm", CM, "Alfresco content model");
        model(ACME_MODEL, "acme", ACME, null);
        when(dictionary.getAllModels()).thenReturn(List.of(CM_MODEL, ACME_MODEL));

        type(CM_CONTENT, "Content", null, Set.of(), Map.of());
        var titled = aspect(CM_TITLED, "Titled");
        var title = property(CM_TITLE, titled, "d:mltext", false);
        when(titled.getProperties()).thenReturn(Map.of(CM_TITLE, title));
        when(dictionary.getTypes(CM_MODEL)).thenReturn(List.of(CM_CONTENT));
        when(dictionary.getAspects(CM_MODEL)).thenReturn(List.of(CM_TITLED));

        var invoice = type(ACME_INVOICE, null, CM_CONTENT, Set.of(CM_TITLED), Map.of());
        var number = property(ACME_NUMBER, invoice, "d:int", true);
        // An inherited property (cm:title, defined by cm:titled) must not be listed under acme:invoice
        when(invoice.getProperties()).thenReturn(Map.of(ACME_NUMBER, number, CM_TITLE, title));
        when(dictionary.getTypes(ACME_MODEL)).thenReturn(List.of(ACME_INVOICE));
        when(dictionary.getAspects(ACME_MODEL)).thenReturn(List.of());

        lister = new ModelLister(services);
    }

    private void model(QName name, String prefix, String uri, String description) {
        var namespace = mock(NamespaceDefinition.class);
        when(namespace.getPrefix()).thenReturn(prefix);
        when(namespace.getUri()).thenReturn(uri);
        var model = mock(ModelDefinition.class);
        when(model.getName()).thenReturn(name);
        when(model.getNamespaces()).thenReturn(List.of(namespace));
        when(model.getDescription(any())).thenReturn(description);
        when(dictionary.getModel(name)).thenReturn(model);
    }

    private TypeDefinition type(QName name, String title, QName parent, Set<QName> aspects, Map<QName, PropertyDefinition> properties) {
        var type = mock(TypeDefinition.class);
        when(type.getName()).thenReturn(name);
        when(type.getTitle(any())).thenReturn(title);
        when(type.getParentName()).thenReturn(parent);
        when(type.getDefaultAspectNames()).thenReturn(aspects);
        when(type.getProperties()).thenReturn(properties);
        when(dictionary.getType(name)).thenReturn(type);
        return type;
    }

    private AspectDefinition aspect(QName name, String title) {
        var aspect = mock(AspectDefinition.class);
        when(aspect.getName()).thenReturn(name);
        when(aspect.getTitle(any())).thenReturn(title);
        when(aspect.getDefaultAspectNames()).thenReturn(Set.of());
        when(dictionary.getAspect(name)).thenReturn(aspect);
        return aspect;
    }

    private PropertyDefinition property(QName name, org.alfresco.service.cmr.dictionary.ClassDefinition container, String type, boolean mandatory) {
        var dataType = mock(DataTypeDefinition.class);
        when(dataType.getName()).thenReturn(QName.createQName(NamespaceService.DICTIONARY_MODEL_1_0_URI, type.substring(2)));
        var property = mock(PropertyDefinition.class);
        when(property.getName()).thenReturn(name);
        when(property.getContainerClass()).thenReturn(container);
        when(property.getDataType()).thenReturn(dataType);
        when(property.isMandatory()).thenReturn(mandatory);
        return property;
    }

    @Test
    void listsEveryModelWithTypesAndAspectsButNoProperties() {
        var models = lister.list("", false).getModelsList();

        assertEquals(List.of("acme:acmemodel", "cm:contentmodel"), models.stream().map(ModelInfo::getName).collect(java.util.stream.Collectors.toList()));
        ModelInfo cm = models.get(1);
        assertEquals("Alfresco content model", cm.getDescription());
        assertEquals("cm", cm.getNamespaces(0).getPrefix());
        assertEquals(CM, cm.getNamespaces(0).getUri());
        assertEquals(List.of("cm:content"), cm.getTypesList().stream().map(ClassInfo::getName).collect(java.util.stream.Collectors.toList()));
        assertEquals("Content", cm.getTypes(0).getTitle());
        assertEquals(List.of("cm:titled"), cm.getAspectsList().stream().map(ClassInfo::getName).collect(java.util.stream.Collectors.toList()));
        assertEquals(0, cm.getAspects(0).getPropertiesCount());
    }

    @Test
    void filterByPrefixOrModelNameAndListOwnPropertiesOnly() {
        for (String filter : List.of("acme", "acme:acmemodel", ACME_MODEL.toString())) {
            var models = lister.list(filter, true).getModelsList();
            assertEquals(1, models.size(), filter);
            ClassInfo invoice = models.get(0).getTypes(0);
            assertEquals("acme:invoice", invoice.getName());
            assertEquals("cm:content", invoice.getParent());
            assertEquals(List.of("cm:titled"), invoice.getMandatoryAspectsList());
            assertEquals(List.of("acme:number"), invoice.getPropertiesList().stream().map(PropertyInfo::getName).collect(java.util.stream.Collectors.toList()));
            assertEquals("d:int", invoice.getProperties(0).getDataType());
            assertTrue(invoice.getProperties(0).getMandatory());
        }
    }

    @Test
    void propertiesRequireAModelFilter() {
        assertThrows(IllegalArgumentException.class, () -> lister.list("", true));
        verifyNoInteractions(dictionary);
    }

    @Test
    void unknownModelIsNotFound() {
        var error = assertThrows(StatusRuntimeException.class, () -> lister.list("nope", false));
        assertEquals(Status.Code.NOT_FOUND, error.getStatus().getCode());
    }
}
