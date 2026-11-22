package org.saidone.protoalf.platform;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.val;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds transport descriptions from Alfresco's deployed data dictionary.
 *
 * <p>Model, type and aspect names are rendered with prefixes where known. Model and class collections are
 * sorted for stable output. Expanded class descriptions include only directly declared properties; inherited
 * definitions remain associated with their declaring class.
 *
 * <p>Instances use the supplied service registry and require the caller to provide transaction and
 * authentication context for repository-backed lookups.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class ModelLister {

    /**
     * Repository services used under the caller's transaction and authentication context.
     */
    private final ServiceRegistry services;

    /**
     * Lists models matching a prefix or qualified model name, sorted by rendered model name. A blank filter
     * selects all models but cannot request property expansion.
     *
     * @param filter namespace prefix, prefixed/expanded model name, or blank for every model
     * @param includeProperties whether to include properties declared by each type/aspect
     * @return matching model descriptions in name order
     * @throws IllegalArgumentException if property expansion is requested without a filter
     * @throws StatusRuntimeException with NOT_FOUND when a non-blank filter matches no model
     */
    ListModelsResponse list(String filter, boolean includeProperties) {
        if (includeProperties && filter.isBlank())
            throw new IllegalArgumentException("Set model (e.g. cm) to include properties");
        val dictionary = services.getDictionaryService();
        val response = ListModelsResponse.newBuilder();
        dictionary.getAllModels().stream().map(dictionary::getModel).filter(model -> filter.isBlank() || matches(model, filter)).sorted(Comparator.comparing(model -> prefixed(model.getName()))).forEach(model -> response.addModels(model(model, includeProperties)));
        if (!filter.isBlank() && response.getModelsCount() == 0)
            throw Status.NOT_FOUND.withDescription(String.format("No model with prefix or name %s", filter)).asRuntimeException();
        return response.build();
    }

    /**
     * Looks up a qualified model name directly in the dictionary and describes it without scanning every model.
     *
     * @param name prefixed or expanded qualified model name
     * @param includeProperties whether to expand declared type/aspect properties
     * @return the deployed model description
     * @throws IllegalArgumentException if the name is unqualified or its namespace cannot be resolved
     * @throws StatusRuntimeException with NOT_FOUND when the qualified model does not exist
     */
    ModelInfo getModel(String name, boolean includeProperties) {
        QName modelName;
        try {
            if (name.isBlank() || !(name.contains(":") || name.startsWith("{")))
                throw new IllegalArgumentException("model must be a qualified model name (e.g. cm:contentmodel)");
            modelName = QName.resolveToQName(services.getNamespaceService(), name);
            if (modelName == null)
                throw new IllegalArgumentException("Unknown model namespace");
        } catch (NamespaceException e) {
            throw new IllegalArgumentException("Unknown model namespace", e);
        }
        val definition = services.getDictionaryService().getModel(modelName);
        if (definition == null)
            throw Status.NOT_FOUND.withDescription(String.format("No model %s", name)).asRuntimeException();
        return model(definition, includeProperties);
    }

    /**
     * Matches a model by prefixed name, expanded name or one of its namespace prefixes.
     *
     * @param model deployed dictionary definition
     * @param filter non-blank model name or namespace prefix
     * @return true if any supported form matches exactly
     */
    private boolean matches(ModelDefinition model, String filter) {
        return prefixed(model.getName()).equals(filter) || model.getName().toString().equals(filter) || model.getNamespaces().stream().anyMatch(namespace -> namespace.getPrefix().equals(filter));
    }

    /**
     * Describes one deployed model with namespaces and sorted types/aspects.
     *
     * @param model dictionary model definition
     * @param includeProperties whether to expand class property definitions
     * @return a model description using readable qualified names
     */
    private ModelInfo model(ModelDefinition model, boolean includeProperties) {
        val dictionary = services.getDictionaryService();
        val info = ModelInfo.newBuilder().setName(prefixed(model.getName()));
        val description = model.getDescription(services.getMessageService());
        if (description != null)
            info.setDescription(description);
        model.getNamespaces().forEach(namespace -> info.addNamespaces(NamespaceInfo.newBuilder().setPrefix(namespace.getPrefix()).setUri(namespace.getUri())));
        classes(dictionary.getTypes(model.getName()), dictionary::getType, includeProperties).forEach(info::addTypes);
        classes(dictionary.getAspects(model.getName()), dictionary::getAspect, includeProperties).forEach(info::addAspects);
        return info.build();
    }

    /**
     * Resolves class names and returns sorted transport descriptions.
     *
     * @param names dictionary type or aspect names
     * @param lookup resolver for the appropriate kind of dictionary class
     * @param includeProperties whether to include directly declared properties
     * @return class descriptions sorted by rendered name
     */
    private List<ClassInfo> classes(Collection<QName> names, Function<QName, ? extends ClassDefinition> lookup, boolean includeProperties) {
        return names.stream().map(lookup).map(definition -> describe(definition, includeProperties)).sorted(Comparator.comparing(ClassInfo::getName)).collect(Collectors.toList());
    }

    /**
     * Describes a type or aspect, its parent and default aspects. Expanded properties exclude inherited
     * declarations so they remain associated with the declaring parent.
     *
     * @param definition dictionary type/aspect definition
     * @param includeProperties whether to expand directly declared properties
     * @return the class description with optional properties
     */
    private ClassInfo describe(ClassDefinition definition, boolean includeProperties) {
        val info = ClassInfo.newBuilder().setName(prefixed(definition.getName()));
        val title = definition.getTitle(services.getMessageService());
        if (title != null)
            info.setTitle(title);
        if (definition.getParentName() != null)
            info.setParent(prefixed(definition.getParentName()));
        definition.getDefaultAspectNames().stream().map(this::prefixed).sorted().forEach(info::addMandatoryAspects);
        if (includeProperties) {
            // getProperties() includes inherited properties: keep only those this class defines itself
            definition.getProperties().values().stream().filter(property -> property.getContainerClass().getName().equals(definition.getName())).map(this::property).sorted(Comparator.comparing(PropertyInfo::getName)).forEach(info::addProperties);
        }
        return info.build();
    }

    /**
     * Describes one dictionary property and its type, mandatory, cardinality and protection flags.
     *
     * @param definition property definition to render
     * @return the property description and optional localized title
     */
    private PropertyInfo property(PropertyDefinition definition) {
        val info = PropertyInfo.newBuilder().setName(prefixed(definition.getName())).setDataType(prefixed(definition.getDataType().getName())).setMandatory(definition.isMandatory()).setMultiValued(definition.isMultiValued()).setReadOnly(definition.isProtected());
        val title = definition.getTitle(services.getMessageService());
        if (title != null)
            info.setTitle(title);
        return info.build();
    }

    /**
     * Renders a qualified name with its namespace prefix when known, otherwise preserves the expanded form.
     *
     * @param name qualified dictionary name
     * @return prefix:localName or the expanded qualified name
     */
    private String prefixed(QName name) {
        try {
            return name.toPrefixString(services.getNamespaceService());
        } catch (NamespaceException e) {
            return name.toString();
        }
    }
}
