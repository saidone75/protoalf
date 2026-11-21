package org.saidone.protoalf.platform;

import lombok.val;
import io.grpc.Status;
import org.alfresco.model.ContentModel;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.model.FileNotFoundException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.datatype.DefaultTypeConverter;
import org.alfresco.service.cmr.repository.datatype.TypeConversionException;
import org.alfresco.service.namespace.NamespaceException;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.saidone.protoalf.api.Content;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Repository write operations behind the gRPC write RPCs.
 * Runs inside the caller's read-write transaction, as the caller: Alfresco enforces permissions, locks,
 * unique names and model constraints. Input errors are reported as {@link IllegalArgumentException}.
 */
final class NodeWriter {

    /** Set through dedicated request fields, never through the properties map. */
    private static final Set<QName> RESERVED = Set.of(ContentModel.PROP_NAME, ContentModel.PROP_CONTENT);
    static final int MAX_NAME_LENGTH = 255;

    private final ServiceRegistry services;

    NodeWriter(ServiceRegistry services) {
        this.services = services;
    }

    NodeRef create(NodeRef parent, String name, String type, Map<String, String> properties, Content content) {
        return create(parent, name, type, properties, Map.of(), List.of(), content);
    }

    /**
     * @param propertyValues multi-valued (or single-valued) properties, see {@link #propertyValues}
     * @param aspects        added before the properties are set, so that aspect properties can be written
     */
    NodeRef create(NodeRef parent, String name, String type, Map<String, String> properties,
                   Map<String, List<String>> propertyValues, List<String> aspects, Content content) {
        val typeName = type.isBlank() ? ContentModel.TYPE_CONTENT : fileType(type);
        val folder = isFolder(typeName);
        if (folder && content != null) throw new IllegalArgumentException("A folder has no content");
        // Validate everything before touching the repository.
        val values = values(properties, propertyValues);
        val aspectNames = aspects(aspects);
        val ref = services.getFileFolderService().create(parent, name(name), typeName).getNodeRef();
        addAspects(ref, aspectNames);
        if (!values.isEmpty()) services.getNodeService().addProperties(ref, values);
        if (content != null) write(ref, name, content);
        return ref;
    }

    /**
     * Creates a document (never a folder) whose content is read from a file, for the UploadContent RPC.
     * A fresh stream is opened on every call: the transaction (and so this method) may be retried.
     */
    NodeRef createDocument(NodeRef parent, String name, String type, Map<String, String> properties,
                           Map<String, List<String>> propertyValues, List<String> aspects,
                           String mimetype, String encoding, Path file) {
        val typeName = type.isBlank() ? ContentModel.TYPE_CONTENT : fileType(type);
        if (isFolder(typeName)) throw new IllegalArgumentException("type must be cm:content or one of its subtypes");
        val values = values(properties, propertyValues);
        val aspectNames = aspects(aspects);
        val ref = services.getFileFolderService().create(parent, name(name), typeName).getNodeRef();
        addAspects(ref, aspectNames);
        if (!values.isEmpty()) services.getNodeService().addProperties(ref, values);
        write(ref, name, mimetype, encoding, () -> Files.newInputStream(file));
        return ref;
    }

    NodeRef update(NodeRef ref, String name, String type, Map<String, String> properties) {
        return update(ref, name, type, properties, Map.of(), List.of(), List.of());
    }

    /**
     * Renames the node, changes its type, adds and removes aspects, then sets the properties (which may belong to
     * the new type and to the added aspects). A document can only become another document type and a folder another
     * folder type. Aspects the node already has (or does not have, for removal) are ignored.
     */
    NodeRef update(NodeRef ref, String name, String type, Map<String, String> properties,
                   Map<String, List<String>> propertyValues, List<String> addAspects, List<String> removeAspects) {
        if (name.isEmpty() && type.isEmpty() && properties.isEmpty() && propertyValues.isEmpty()
                && addAspects.isEmpty() && removeAspects.isEmpty())
            throw new IllegalArgumentException("Nothing to update: set name, type, properties, property_values, add_aspects and/or remove_aspects");
        val typeName = type.isEmpty() ? null : fileType(type);
        if (typeName != null && isFolder(typeName) != isFolder(services.getNodeService().getType(ref)))
            throw new IllegalArgumentException("A document cannot become a folder, nor a folder a document");
        val values = values(properties, propertyValues);
        val toAdd = aspects(addAspects);
        val toRemove = aspects(removeAspects);
        if (!name.isEmpty()) fileOperation(() -> services.getFileFolderService().rename(ref, name(name)));
        if (typeName != null) services.getNodeService().setType(ref, typeName);
        addAspects(ref, toAdd);
        val nodes = services.getNodeService();
        for (val aspect : toRemove) if (nodes.hasAspect(ref, aspect)) nodes.removeAspect(ref, aspect);
        if (!values.isEmpty()) nodes.addProperties(ref, values);
        return ref;
    }

    /** Adds the aspects the node does not have yet. */
    private void addAspects(NodeRef ref, List<QName> aspects) {
        val nodes = services.getNodeService();
        for (val aspect : aspects) if (!nodes.hasAspect(ref, aspect)) nodes.addAspect(ref, aspect, new HashMap<>());
    }

    /** Resolves aspect names: each must be a known aspect outside the sys namespace. */
    private List<QName> aspects(List<String> names) {
        val dictionary = services.getDictionaryService();
        val result = new ArrayList<QName>();
        for (val value : names) {
            val aspect = qname(value);
            if (NamespaceService.SYSTEM_MODEL_1_0_URI.equals(aspect.getNamespaceURI()))
                throw new IllegalArgumentException(String.format("Aspect %s is a system aspect", value));
            if (dictionary.getAspect(aspect) == null) throw new IllegalArgumentException(String.format("Unknown aspect %s", value));
            result.add(aspect);
        }
        return result;
    }

    /** Resolves a type that must exist and be cm:content, cm:folder or one of their subtypes. */
    private QName fileType(String type) {
        val dictionary = services.getDictionaryService();
        val typeName = qname(type);
        if (dictionary.getType(typeName) == null) throw new IllegalArgumentException(String.format("Unknown type %s", type));
        if (!isFolder(typeName) && !dictionary.isSubClass(typeName, ContentModel.TYPE_CONTENT))
            throw new IllegalArgumentException("type must be cm:content, cm:folder or one of their subtypes");
        return typeName;
    }

    private boolean isFolder(QName type) {
        return services.getDictionaryService().isSubClass(type, ContentModel.TYPE_FOLDER);
    }

    NodeRef updateContent(NodeRef ref, Content content) {
        if (content == null) throw new IllegalArgumentException("content is required");
        val nodes = services.getNodeService();
        if (!services.getDictionaryService().isSubClass(nodes.getType(ref), ContentModel.TYPE_CONTENT))
            throw new IllegalArgumentException("Only documents (cm:content) have content");
        write(ref, Objects.toString(nodes.getProperty(ref, ContentModel.PROP_NAME), ""), content);
        return ref;
    }

    NodeRef move(NodeRef ref, NodeRef targetParent, String name) {
        return fileOperation(() -> services.getFileFolderService().move(ref, targetParent, optionalName(name))).getNodeRef();
    }

    NodeRef copy(NodeRef ref, NodeRef targetParent, String name) {
        return fileOperation(() -> services.getFileFolderService().copy(ref, targetParent, optionalName(name))).getNodeRef();
    }

    /** Deletes to the trashcan (archive store). Well-known folders are refused even when permissions would allow it. */
    void delete(NodeRef ref) {
        if (protectedFolders().contains(ref))
            throw new IllegalArgumentException("Company Home, Shared Files and the user home folder cannot be deleted");
        services.getNodeService().deleteNode(ref);
    }

    private Set<NodeRef> protectedFolders() {
        val locator = services.getNodeLocatorService();
        return GrpcNodeService.ALIASES.values().stream()
                .map(name -> locator.getNode(name, null, null))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private void write(NodeRef ref, String name, Content content) {
        write(ref, name, content.getMimetype(), content.getEncoding(), () -> content.getData().newInput());
    }

    /** Writes the content of the node: mimetype guessed from the name and encoding UTF-8 when blank. */
    private void write(NodeRef ref, String name, String mimetype, String encoding, InputSource data) {
        val writer = services.getContentService().getWriter(ref, ContentModel.PROP_CONTENT, true);
        writer.setMimetype(mimetype.isBlank() ? services.getMimetypeService().guessMimetype(name) : mimetype);
        writer.setEncoding(encoding.isBlank() ? "UTF-8" : encoding);
        try (InputStream in = data.open()) {
            writer.putContent(in);
        } catch (IOException e) {
            throw new org.alfresco.service.cmr.repository.ContentIOException("Cannot read the uploaded content", e);
        }
    }

    @FunctionalInterface
    private interface InputSource {
        InputStream open() throws IOException;
    }

    /** Converts "prefix:name" -> text values into typed repository values, validating each property. */
    Map<QName, Serializable> properties(Map<String, String> input) {
        val result = new HashMap<QName, Serializable>();
        input.forEach((key, value) -> {
            val name = qname(key);
            val definition = writable(key, name);
            val converted = convert(key, definition, value);
            result.put(name, definition.isMultiValued() && !(converted instanceof Collection<?>)
                    ? new ArrayList<>(List.of(converted)) : converted);
        });
        return result;
    }

    /**
     * Converts "prefix:name" -> list of text values (property_values). A multi-valued property takes any number of
     * values (empty = unset); a single-valued one takes 0 (unset) or 1 value.
     */
    Map<QName, Serializable> propertyValues(Map<String, List<String>> input) {
        val result = new HashMap<QName, Serializable>();
        input.forEach((key, values) -> {
            val name = qname(key);
            val definition = writable(key, name);
            if (definition.isMultiValued()) {
                val converted = new ArrayList<Serializable>();
                for (val value : values) converted.add(convert(key, definition, value));
                result.put(name, converted);
            } else {
                if (values.size() > 1) throw new IllegalArgumentException(String.format("%s is single-valued: give at most one value", key));
                result.put(name, values.isEmpty() ? null : convert(key, definition, values.get(0)));
            }
        });
        return result;
    }

    /** Both maps together: a property given in both is rejected. */
    private Map<QName, Serializable> values(Map<String, String> properties, Map<String, List<String>> propertyValues) {
        val result = properties(properties);
        for (val key : propertyValues.keySet())
            if (result.containsKey(qname(key)))
                throw new IllegalArgumentException(String.format("%s is set in both properties and property_values", key));
        result.putAll(propertyValues(propertyValues));
        return result;
    }

    /** The definition of a property that a request may set: not reserved, known, and neither protected nor sys. */
    private PropertyDefinition writable(String key, QName name) {
        if (RESERVED.contains(name))
            throw new IllegalArgumentException(String.format("%s cannot be set as a property: use the name or content field", key));
        val definition = services.getDictionaryService().getProperty(name);
        if (definition == null) throw new IllegalArgumentException(String.format("Unknown property %s", key));
        if (definition.isProtected() || NamespaceService.SYSTEM_MODEL_1_0_URI.equals(name.getNamespaceURI()))
            throw new IllegalArgumentException(String.format("Property %s is read-only", key));
        return definition;
    }

    private static Serializable convert(String key, PropertyDefinition definition, String value) {
        try {
            return (Serializable) DefaultTypeConverter.INSTANCE.convert(definition.getDataType(), value);
        } catch (TypeConversionException e) {
            throw new IllegalArgumentException(String.format("Invalid value for %s (type %s): %s", key, definition.getDataType().getName().getLocalName(), value));
        }
    }

    private QName qname(String value) {
        try {
            return QName.resolveToQName(services.getNamespaceService(), value);
        } catch (NamespaceException | IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("Invalid name %s: use prefix:name (e.g. cm:title) or {uri}name", value));
        }
    }

    private static String name(String name) {
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH)
            throw new IllegalArgumentException(String.format("name must be 1..%d characters", MAX_NAME_LENGTH));
        return name;
    }

    private static String optionalName(String name) {
        return name.isEmpty() ? null : name(name);
    }

    /** FileFolderService declares the checked FileNotFoundException: report it as NOT_FOUND. */
    private static FileInfo fileOperation(FileOperation operation) {
        try {
            return operation.run();
        } catch (FileNotFoundException e) {
            throw Status.NOT_FOUND.withDescription("Node not found").asRuntimeException();
        }
    }

    @FunctionalInterface
    private interface FileOperation {
        FileInfo run() throws FileNotFoundException;
    }
}
