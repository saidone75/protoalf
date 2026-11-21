package org.saidone.protoalf.platform;

import lombok.val;
import io.grpc.Status;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.dictionary.ClassDefinition;
import org.alfresco.service.cmr.dictionary.ModelDefinition;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.namespace.NamespaceException;
import org.alfresco.service.namespace.QName;
import org.saidone.protoalf.api.ClassInfo;
import org.saidone.protoalf.api.ListModelsResponse;
import org.saidone.protoalf.api.ModelInfo;
import org.saidone.protoalf.api.NamespaceInfo;
import org.saidone.protoalf.api.PropertyInfo;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.function.Function;

/** Describes the content models of the data dictionary (DictionaryService) for ListModels. */
final class ModelLister {

    private final ServiceRegistry services;

    ModelLister(ServiceRegistry services) {
        this.services = services;
    }

    ListModelsResponse list(String filter, boolean includeProperties) {
        if (includeProperties && filter.isBlank())
            throw new IllegalArgumentException("Set model (e.g. cm) to include properties");
        val dictionary = services.getDictionaryService();
        val response = ListModelsResponse.newBuilder();
        dictionary.getAllModels().stream()
                .map(dictionary::getModel)
                .filter(model -> filter.isBlank() || matches(model, filter))
                .sorted(Comparator.comparing(model -> prefixed(model.getName())))
                .forEach(model -> response.addModels(model(model, includeProperties)));
        if (!filter.isBlank() && response.getModelsCount() == 0)
            throw Status.NOT_FOUND.withDescription(String.format("No model with prefix or name %s", filter)).asRuntimeException();
        return response.build();
    }

    private boolean matches(ModelDefinition model, String filter) {
        return prefixed(model.getName()).equals(filter) || model.getName().toString().equals(filter)
                || model.getNamespaces().stream().anyMatch(namespace -> namespace.getPrefix().equals(filter));
    }

    private ModelInfo model(ModelDefinition model, boolean includeProperties) {
        val dictionary = services.getDictionaryService();
        val info = ModelInfo.newBuilder().setName(prefixed(model.getName()));
        val description = model.getDescription(services.getMessageService());
        if (description != null) info.setDescription(description);
        model.getNamespaces().forEach(namespace -> info.addNamespaces(
                NamespaceInfo.newBuilder().setPrefix(namespace.getPrefix()).setUri(namespace.getUri())));
        classes(dictionary.getTypes(model.getName()), dictionary::getType, includeProperties).forEach(info::addTypes);
        classes(dictionary.getAspects(model.getName()), dictionary::getAspect, includeProperties).forEach(info::addAspects);
        return info.build();
    }

    private List<ClassInfo> classes(Collection<QName> names, Function<QName, ? extends ClassDefinition> lookup,
                                    boolean includeProperties) {
        return names.stream()
                .map(lookup)
                .map(definition -> describe(definition, includeProperties))
                .sorted(Comparator.comparing(ClassInfo::getName))
                .collect(Collectors.toList());
    }

    private ClassInfo describe(ClassDefinition definition, boolean includeProperties) {
        val info = ClassInfo.newBuilder().setName(prefixed(definition.getName()));
        val title = definition.getTitle(services.getMessageService());
        if (title != null) info.setTitle(title);
        if (definition.getParentName() != null) info.setParent(prefixed(definition.getParentName()));
        definition.getDefaultAspectNames().stream().map(this::prefixed).sorted().forEach(info::addMandatoryAspects);
        if (includeProperties) {
            // getProperties() includes inherited properties: keep only those this class defines itself
            definition.getProperties().values().stream()
                    .filter(property -> property.getContainerClass().getName().equals(definition.getName()))
                    .map(this::property)
                    .sorted(Comparator.comparing(PropertyInfo::getName))
                    .forEach(info::addProperties);
        }
        return info.build();
    }

    private PropertyInfo property(PropertyDefinition definition) {
        val info = PropertyInfo.newBuilder()
                .setName(prefixed(definition.getName()))
                .setDataType(prefixed(definition.getDataType().getName()))
                .setMandatory(definition.isMandatory())
                .setMultiValued(definition.isMultiValued())
                .setReadOnly(definition.isProtected());
        val title = definition.getTitle(services.getMessageService());
        if (title != null) info.setTitle(title);
        return info.build();
    }

    /** cm:content rather than {http://www.alfresco.org/model/content/1.0}content; full form if the prefix is unknown. */
    private String prefixed(QName name) {
        try {
            return name.toPrefixString(services.getNamespaceService());
        } catch (NamespaceException e) {
            return name.toString();
        }
    }
}
