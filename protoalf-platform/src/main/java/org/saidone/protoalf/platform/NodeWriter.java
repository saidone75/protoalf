package org.saidone.protoalf.platform;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.alfresco.model.ContentModel;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.model.FileNotFoundException;
import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.datatype.DefaultTypeConverter;
import org.alfresco.service.cmr.repository.datatype.TypeConversionException;
import org.alfresco.service.namespace.NamespaceException;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.saidone.protoalf.api.Content;
import org.saidone.protoalf.api.Property;
import org.saidone.protoalf.api.PropertyValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates remote write inputs and applies them through Alfresco repository services.
 *
 * <p>Callers provide the authenticated user's retryable read-write transaction. This helper does not commit or
 * start transactions. Alfresco remains responsible for permissions, locks, duplicate names and content model
 * constraints.
 *
 * <p>Writes support document and folder types, known non-system aspects and writable dictionary properties.
 * Names and content use dedicated request fields. Typed property values must match dictionary cardinality
 * and datatypes; protected, reserved and system properties are rejected before mutation where possible.
 *
 * <p>Uploads backed by a file reopen the input stream for each transaction attempt. This is essential when
 * Alfresco retries creation after the bytes have already been received.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class NodeWriter {

    /**
     * Properties supplied through dedicated name/content fields instead of metadata maps.
     */
    private static final Set<QName> RESERVED = Set.of(ContentModel.PROP_NAME, ContentModel.PROP_CONTENT);

    /**
     * Maximum node-name length accepted by the remote write helpers.
     */
    static final int MAX_NAME_LENGTH = 255;

    /**
     * Repository services used under the caller's transaction and authentication context.
     */
    private final ServiceRegistry services;

    /**
     * Creates a document or folder and applies validated metadata in the current write transaction. Blank type
     * defaults to cm:content; folders cannot receive content. Aspects are added before properties.
     *
     * @param parent destination parent folder
     * @param name required non-blank node name, at most MAX_NAME_LENGTH characters
     * @param type qualified document/folder type; blank defaults to cm:content
     * @param properties typed scalar or multi-valued properties keyed by qualified name
     * @param content document bytes and metadata; null is allowed only for creation without content
     * @return the created node reference
     * @throws IllegalArgumentException if input validation fails or the requested operation is unsupported
     */
    NodeRef create(NodeRef parent, String name, String type, Map<String, Property> properties, Content content) {
        return create(parent, name, type, properties, List.of(), content);
    }

    /**
     * Creates a document or folder and applies validated metadata in the current write transaction. Blank type
     * defaults to cm:content; folders cannot receive content. Aspects are added before properties.
     *
     * @param parent destination parent folder
     * @param name required non-blank node name, at most MAX_NAME_LENGTH characters
     * @param type qualified document/folder type; blank defaults to cm:content
     * @param properties typed scalar or multi-valued properties keyed by qualified name
     * @param aspects known non-system aspects to add before setting metadata
     * @param content document bytes and metadata; null is allowed only for creation without content
     * @return the created node reference
     * @throws IllegalArgumentException if input validation fails or the requested operation is unsupported
     */
    NodeRef create(NodeRef parent, String name, String type, Map<String, Property> properties, List<String> aspects, Content content) {
        val typeName = type.isBlank() ? ContentModel.TYPE_CONTENT : fileType(type);
        val folder = isFolder(typeName);
        if (folder && content != null)
            throw new IllegalArgumentException("A folder has no content");
        // Validate everything before touching the repository.
        val values = properties(properties);
        val aspectNames = aspects(aspects);
        val ref = services.getFileFolderService().create(parent, name(name), typeName).getNodeRef();
        addAspects(ref, aspectNames);
        if (!values.isEmpty())
            services.getNodeService().addProperties(ref, values);
        if (content != null)
            write(ref, name, content);
        return ref;
    }

    /**
     * Applies a composite metadata change in the current transaction. Renames and type changes precede aspect
     * changes and property merging. Blank name/type fields preserve existing values; at least one change is
     * required and document/folder categories cannot be crossed.
     *
     * @param ref existing node to mutate in the caller transaction
     * @param name new node name, or the empty string to preserve the existing name
     * @param type new qualified type, or the empty string to preserve the existing type
     * @param properties typed scalar or multi-valued properties keyed by qualified name
     * @return the updated node reference
     * @throws IllegalArgumentException if input validation fails or the requested operation is unsupported
     */
    NodeRef update(NodeRef ref, String name, String type, Map<String, Property> properties) {
        return update(ref, name, type, properties, List.of(), List.of());
    }

    /**
     * Applies a composite metadata change in the current transaction. Renames and type changes precede aspect
     * changes and property merging. Blank name/type fields preserve existing values; at least one change is
     * required and document/folder categories cannot be crossed.
     *
     * @param ref existing node to mutate in the caller transaction
     * @param name new node name, or the empty string to preserve the existing name
     * @param type new qualified type, or the empty string to preserve the existing type
     * @param properties typed scalar or multi-valued properties keyed by qualified name
     * @param addAspects known non-system aspects to add if absent
     * @param removeAspects known non-system aspects to remove if present
     * @return the updated node reference
     * @throws IllegalArgumentException if input validation fails or the requested operation is unsupported
     */
    NodeRef update(NodeRef ref, String name, String type, Map<String, Property> properties, List<String> addAspects, List<String> removeAspects) {
        if (name.isEmpty() && type.isEmpty() && properties.isEmpty() && addAspects.isEmpty() && removeAspects.isEmpty())
            throw new IllegalArgumentException("Nothing to update: set name, type, properties, add_aspects and/or remove_aspects");
        val typeName = type.isEmpty() ? null : fileType(type);
        if (typeName != null && isFolder(typeName) != isFolder(services.getNodeService().getType(ref)))
            throw new IllegalArgumentException("A document cannot become a folder, nor a folder a document");
        val values = properties(properties);
        val toAdd = aspects(addAspects);
        val toRemove = aspects(removeAspects);
        if (!name.isEmpty())
            fileOperation(() -> services.getFileFolderService().rename(ref, name(name)));
        if (typeName != null)
            services.getNodeService().setType(ref, typeName);
        addAspects(ref, toAdd);
        val nodes = services.getNodeService();
        for (val aspect : toRemove) if (nodes.hasAspect(ref, aspect))
            nodes.removeAspect(ref, aspect);
        if (!values.isEmpty())
            nodes.addProperties(ref, values);
        return ref;
    }

    /**
     * Moves a node through Alfresco file/folder services; an empty name preserves its current name.
     *
     * @param ref existing node to mutate in the caller transaction
     * @param targetParent destination parent folder reference
     * @param name new node name, or the empty string to preserve the existing name
     * @return the moved node reference
     * @throws StatusRuntimeException with NOT_FOUND if the file/folder service reports a missing source or
     *     destination
     */
    NodeRef move(NodeRef ref, NodeRef targetParent, String name) {
        return fileOperation(() -> services.getFileFolderService().move(ref, targetParent, optionalName(name))).getNodeRef();
    }

    /**
     * Copies a node through Alfresco file/folder services, delegating folder recursion and association behavior
     * to Alfresco.
     *
     * @param ref existing node to mutate in the caller transaction
     * @param targetParent destination parent folder reference
     * @param name new node name, or the empty string to preserve the existing name
     * @return the new copy reference
     * @throws StatusRuntimeException with NOT_FOUND if the file/folder service reports a missing source or
     *     destination
     */
    NodeRef copy(NodeRef ref, NodeRef targetParent, String name) {
        return fileOperation(() -> services.getFileFolderService().copy(ref, targetParent, optionalName(name))).getNodeRef();
    }

    /**
     * Deletes a node through Alfresco after refusing Company Home, Shared Files and the calling user's home
     * folder. The surrounding transaction determines commit or rollback.
     *
     * @param ref existing node to mutate in the caller transaction
     * @throws IllegalArgumentException if input validation fails or the requested operation is unsupported
     */
    void delete(NodeRef ref) {
        if (protectedFolders().contains(ref))
            throw new IllegalArgumentException("Company Home, Shared Files and the user home folder cannot be deleted");
        services.getNodeService().deleteNode(ref);
    }

    /**
     * Resolves the protected root, shared and current-user home aliases under the calling identity, omitting
     * aliases with no node.
     *
     * @return the node references that delete must refuse
     */
    private Set<NodeRef> protectedFolders() {
        val locator = services.getNodeLocatorService();
        return GrpcServiceSupport.ALIASES.values().stream().map(name -> locator.getNode(name, null, null)).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /**
     * Creates a document from a spool file, reopening its stream on every transaction attempt. Validates
     * metadata and aspects before creation; a folder type is rejected.
     *
     * @param parent destination parent folder
     * @param name required non-blank node name, at most MAX_NAME_LENGTH characters
     * @param type qualified document subtype; blank defaults to cm:content
     * @param properties typed scalar or multi-valued properties keyed by qualified name
     * @param aspects known non-system aspects to add before setting metadata
     * @param mimetype MIME type, or blank to infer it from the filename
     * @param encoding character encoding, or blank for UTF-8
     * @param file repeatably readable spool file containing the new document bytes
     * @return the created document reference
     * @throws IllegalArgumentException if input validation fails or the requested operation is unsupported
     * @throws ContentIOException if the source content cannot be opened or read
     */
    NodeRef createDocument(NodeRef parent, String name, String type, Map<String, Property> properties, List<String> aspects, String mimetype, String encoding, Path file) {
        val typeName = type.isBlank() ? ContentModel.TYPE_CONTENT : fileType(type);
        if (isFolder(typeName))
            throw new IllegalArgumentException("type must be cm:content or one of its subtypes");
        val values = properties(properties);
        val aspectNames = aspects(aspects);
        val ref = services.getFileFolderService().create(parent, name(name), typeName).getNodeRef();
        addAspects(ref, aspectNames);
        if (!values.isEmpty())
            services.getNodeService().addProperties(ref, values);
        write(ref, name, mimetype, encoding, () -> Files.newInputStream(file));
        return ref;
    }

    /**
     * Replaces cm:content on a document using its existing name for MIME-type inference. Null content and
     * non-document nodes are rejected.
     *
     * @param ref existing node to mutate in the caller transaction
     * @param content required content bytes and metadata; an empty payload is allowed
     * @return the updated document reference
     * @throws IllegalArgumentException if input validation fails or the requested operation is unsupported
     * @throws ContentIOException if the source content cannot be opened or read
     */
    NodeRef updateContent(NodeRef ref, Content content) {
        if (content == null)
            throw new IllegalArgumentException("content is required");
        val nodes = services.getNodeService();
        if (!services.getDictionaryService().isSubClass(nodes.getType(ref), ContentModel.TYPE_CONTENT))
            throw new IllegalArgumentException("Only documents (cm:content) have content");
        write(ref, Objects.toString(nodes.getProperty(ref, ContentModel.PROP_NAME), ""), content);
        return ref;
    }

    /**
     * Writes document content using a fresh input stream and closes that stream. A blank MIME type is inferred
     * from the node name and blank encoding defaults to UTF-8.
     *
     * @param ref existing node to mutate in the caller transaction
     * @param name filename used to infer the MIME type when none is supplied
     * @param content required document bytes with optional MIME type and encoding
     * @throws ContentIOException if the source content cannot be opened or read
     */
    private void write(NodeRef ref, String name, Content content) {
        write(ref, name, content.getMimetype(), content.getEncoding(), () -> content.getData().newInput());
    }

    /**
     * Writes document content using a fresh input stream and closes that stream. A blank MIME type is inferred
     * from the node name and blank encoding defaults to UTF-8.
     *
     * @param ref existing node to mutate in the caller transaction
     * @param name filename used to infer the MIME type when none is supplied
     * @param mimetype MIME type, or blank to infer it from the filename
     * @param encoding character encoding, or blank for UTF-8
     * @param data repeatable supplier of a fresh content stream
     * @throws ContentIOException if the source content cannot be opened or read
     */
    private void write(NodeRef ref, String name, String mimetype, String encoding, InputSource data) {
        val writer = services.getContentService().getWriter(ref, ContentModel.PROP_CONTENT, true);
        writer.setMimetype(mimetype.isBlank() ? services.getMimetypeService().guessMimetype(name) : mimetype);
        writer.setEncoding(encoding.isBlank() ? "UTF-8" : encoding);
        try (val in = data.open()) {
            writer.putContent(in);
        } catch (IOException e) {
            throw new ContentIOException("Cannot read the uploaded content", e);
        }
    }

    /**
     * Opens a fresh content stream for each write attempt.
     *
     * <p>Implementations must be repeatable because the surrounding repository transaction may retry. The
     * writer closes every stream returned by {@link #open}.
     */
    @FunctionalInterface
    private interface InputSource {

        /**
         * Opens a fresh stream positioned at the start of the supplied content.
         *
         * @return a stream that the receiving writer must close
         * @throws IOException if the content source cannot be opened
         */
        InputStream open() throws IOException;
    }

    /**
     * Adds each known aspect that the node does not already have, initially with an empty property map.
     *
     * @param ref existing node to mutate in the caller transaction
     * @param aspects known non-system aspects to add before setting metadata
     */
    private void addAspects(NodeRef ref, List<QName> aspects) {
        val nodes = services.getNodeService();
        for (val aspect : aspects) if (!nodes.hasAspect(ref, aspect))
            nodes.addAspect(ref, aspect, new HashMap<>());
    }

    /**
     * Resolves and validates aspect names against the dictionary. System namespace aspects and unknown aspect
     * definitions are rejected.
     *
     * @param names aspect names in prefix or expanded notation
     * @return validated aspect qualified names in input order
     * @throws IllegalArgumentException if a name is invalid, unknown or in the system namespace
     */
    private List<QName> aspects(List<String> names) {
        val dictionary = services.getDictionaryService();
        val result = new ArrayList<QName>();
        for (val value : names) {
            val aspect = qname(value);
            if (NamespaceService.SYSTEM_MODEL_1_0_URI.equals(aspect.getNamespaceURI()))
                throw new IllegalArgumentException(String.format("Aspect %s is a system aspect", value));
            if (dictionary.getAspect(aspect) == null)
                throw new IllegalArgumentException(String.format("Unknown aspect %s", value));
            result.add(aspect);
        }
        return result;
    }

    /** Validates explicit cardinality and converts typed values using the repository dictionary. */
    Map<QName, Serializable> properties(Map<String, Property> input) {
        val result = new HashMap<QName, Serializable>();
        input.forEach((key, property) -> {
            val name = qname(key);
            val definition = writable(key, name);
            if (result.containsKey(name))
                throw new IllegalArgumentException("Duplicate qualified property " + key);
            if (property.getMultiValued() != definition.isMultiValued())
                throw new IllegalArgumentException(key + " cardinality does not match the dictionary");
            if (property.getMultiValued()) {
                val values = new ArrayList<Serializable>();
                for (val value : property.getValuesList()) {
                    if (value.getValueCase() == PropertyValue.ValueCase.NULL_VALUE)
                        throw new IllegalArgumentException(key + " lists cannot contain null; use an empty list to clear");
                    values.add(convert(key, definition, value));
                }
                result.put(name, values);
            } else {
                if (property.getValuesCount() != 1)
                    throw new IllegalArgumentException(key + " requires exactly one scalar value or explicit null");
                result.put(name, convert(key, definition, property.getValues(0)));
            }
        });
        return result;
    }

    /**
     * Finds a dictionary property and rejects dedicated name/content fields, system properties and protected
     * definitions.
     *
     * @param key original input name for diagnostic messages
     * @param name resolved qualified property name
     * @return the writable dictionary definition
     * @throws IllegalArgumentException if the property is reserved, unknown, protected or a system property
     */
    private PropertyDefinition writable(String key, QName name) {
        if (RESERVED.contains(name))
            throw new IllegalArgumentException(String.format("%s cannot be set as a property: use the name or content field", key));
        val definition = services.getDictionaryService().getProperty(name);
        if (definition == null)
            throw new IllegalArgumentException(String.format("Unknown property %s", key));
        if (definition.isProtected() || NamespaceService.SYSTEM_MODEL_1_0_URI.equals(name.getNamespaceURI()))
            throw new IllegalArgumentException(String.format("Property %s is read-only", key));
        return definition;
    }

    /** Decodes one explicit value, rejecting implicit string/numeric/boolean/date conversions. */
    private Serializable convert(String key, PropertyDefinition definition, PropertyValue value) {
        val javaType = definition.getDataType().getJavaClassName();
        Object decoded;
        boolean compatible;
        switch (value.getValueCase()) {
            case STRING_VALUE:
                decoded = value.getStringValue();
                compatible = javaType.equals("java.lang.String") || javaType.equals("org.alfresco.service.cmr.repository.MLText");
                break;
            case INTEGER_VALUE:
                val number = value.getIntegerValue();
                decoded = number;
                compatible = javaType.equals("java.lang.Long") || (javaType.equals("java.lang.Integer") && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE);
                break;
            case DOUBLE_VALUE:
                val numberDouble = value.getDoubleValue();
                decoded = numberDouble;
                compatible = Double.isFinite(numberDouble) && (javaType.equals("java.lang.Double") || (javaType.equals("java.lang.Float") && Math.abs(numberDouble) <= Float.MAX_VALUE));
                break;
            case BOOLEAN_VALUE:
                decoded = value.getBooleanValue();
                compatible = javaType.equals("java.lang.Boolean");
                break;
            case TIMESTAMP_MILLIS:
                decoded = new Date(value.getTimestampMillis());
                compatible = javaType.equals("java.util.Date");
                break;
            case NODE_REF_VALUE:
                decoded = new NodeRef(value.getNodeRefValue());
                compatible = javaType.equals(NodeRef.class.getName());
                break;
            case QNAME_VALUE:
                decoded = qname(value.getQnameValue());
                compatible = javaType.equals(QName.class.getName());
                break;
            case NULL_VALUE:
                if (!value.getNullValue())
                    throw new IllegalArgumentException(key + " null_value must be true");
                return null;
            default:
                throw new IllegalArgumentException("Unsupported or missing value for " + key);
        }
        if (!compatible)
            throw new IllegalArgumentException("Invalid value kind for " + key + " (expected " + javaType + ")");
        try {
            return (Serializable) DefaultTypeConverter.INSTANCE.convert(definition.getDataType(), decoded);
        } catch (TypeConversionException e) {
            throw new IllegalArgumentException("Invalid value for " + key + " (expected " + javaType + ")", e);
        }
    }

    /**
     * Resolves a known document or folder type, including subtypes, and rejects unrelated repository types.
     *
     * @param type qualified type name in prefix or expanded notation
     * @return the validated type qualified name
     * @throws IllegalArgumentException if the name is invalid, unknown or outside the document/folder
     *     hierarchies
     */
    private QName fileType(String type) {
        val dictionary = services.getDictionaryService();
        val typeName = qname(type);
        if (dictionary.getType(typeName) == null)
            throw new IllegalArgumentException(String.format("Unknown type %s", type));
        if (!isFolder(typeName) && !dictionary.isSubClass(typeName, ContentModel.TYPE_CONTENT))
            throw new IllegalArgumentException("type must be cm:content, cm:folder or one of their subtypes");
        return typeName;
    }

    /**
     * Tests whether the dictionary type belongs to the cm:folder hierarchy.
     *
     * @param type qualified dictionary type name
     * @return true for cm:folder or one of its subtypes
     */
    private boolean isFolder(QName type) {
        return services.getDictionaryService().isSubClass(type, ContentModel.TYPE_FOLDER);
    }

    /**
     * Resolves a prefix-qualified or expanded name using the repository namespace service.
     *
     * @param value input property, aspect or type name
     * @return the resolved qualified name
     * @throws IllegalArgumentException if the name or namespace prefix is invalid
     */
    private QName qname(String value) {
        try {
            return QName.resolveToQName(services.getNamespaceService(), value);
        } catch (NamespaceException | IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("Invalid name %s: use prefix:name (e.g. cm:title) or {uri}name", value));
        }
    }

    /**
     * Validates the non-blank node name length before passing it to Alfresco for repository-specific
     * validation.
     *
     * @param name candidate node name
     * @return the unchanged validated name
     * @throws IllegalArgumentException if the name is blank or longer than MAX_NAME_LENGTH
     */
    private static String name(String name) {
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH)
            throw new IllegalArgumentException(String.format("name must be 1..%d characters", MAX_NAME_LENGTH));
        return name;
    }

    /**
     * Converts the empty optional-name marker to null for Alfresco move/copy calls, otherwise validates the
     * supplied name.
     *
     * @param name new name, or the empty string to keep the current name
     * @return null for an omitted name, otherwise the validated name
     * @throws IllegalArgumentException if a supplied name is blank or too long
     */
    private static String optionalName(String name) {
        return name.isEmpty() ? null : name(name);
    }

    /**
     * Executes a file/folder operation and translates its checked missing-file exception without intercepting
     * other repository failures.
     *
     * @param operation repository operation to invoke
     * @return the file information returned by Alfresco
     * @throws StatusRuntimeException with NOT_FOUND if the operation reports a missing file or folder
     */
    private static FileInfo fileOperation(FileOperation operation) {
        try {
            return operation.run();
        } catch (FileNotFoundException e) {
            throw Status.NOT_FOUND.withDescription("Node not found").asRuntimeException();
        }
    }

    /**
     * Represents an Alfresco file/folder call that declares a checked missing-file exception.
     *
     * <p>{@link NodeWriter#fileOperation} translates that exception to the gRPC {@code NOT_FOUND} status while
     * allowing other repository failures to reach the common transaction error mapper.
     */
    @FunctionalInterface
    private interface FileOperation {

        /**
         * Executes the wrapped Alfresco file/folder operation.
         *
         * @return the operation's resulting file information
         * @throws FileNotFoundException if a source or parent folder required by the operation is missing
         */
        FileInfo run() throws FileNotFoundException;
    }
}
