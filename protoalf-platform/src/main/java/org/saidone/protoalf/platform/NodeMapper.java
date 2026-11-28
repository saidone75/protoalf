package org.saidone.protoalf.platform;

import lombok.val;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.*;
import org.alfresco.service.cmr.security.AccessStatus;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.namespace.QName;
import org.saidone.protoalf.api.*;

import java.io.Serializable;
import java.util.*;

/**
 * Converts Alfresco nodes and repository property values into transport-safe Protobuf snapshots.
 *
 * <p>Repository-backed mapping requires an existing transaction and caller authentication. The primary-parent
 * lookup uses system identity only to avoid a denied lookup marking that transaction for rollback; the parent
 * reference is returned only after an explicit permission check as the original caller.
 *
 * <p>Property conversion preserves supported scalar types and collection cardinality. Content properties expose
 * metadata only, never the underlying content-store URL.
 */
final class NodeMapper {

    /**
     * Repository node service used to read node metadata and associations.
     */
    private final NodeService nodes;

    /**
     * Repository services used under the caller's transaction and authentication context.
     */
    private final ServiceRegistry services;

    /**
     * Creates the mapper using the registry's secured repository services.
     *
     * @param services Alfresco service registry; repository work uses its secured services
     */
    NodeMapper(ServiceRegistry services) {
        this.services = services;
        this.nodes = services.getNodeService();
    }

    /**
     * Builds a node snapshot with type, name, sorted aspects and typed properties. Parent inclusion is
     * permission checked and can be disabled for frozen version nodes.
     *
     * @param ref existing node to read inside the caller transaction
     * @return a transport snapshot without content-store locations
     */
    Node map(NodeRef ref) {
        return map(ref, true);
    }

    /**
     * Maps a frozen version node without attempting to resolve a primary parent in the live hierarchy.
     *
     * @param frozen frozen-state reference of a repository version
     * @return the frozen metadata snapshot without a parent reference
     */
    Node mapVersion(NodeRef frozen) {
        return map(frozen, false);
    }

    /**
     * Creates a mapper for one listing transaction attempt. Parent permission decisions are shared only
     * within this scope, never between requests, users or transaction retries.
     *
     * @return a listing-local mapper using metadata already fetched by the secured file/folder service
     */
    Listing listing() {
        return new Listing();
    }

    /** Reuses file information and caches grants and denials for actual primary parents within one page. */
    final class Listing {

        private final Map<NodeRef, Boolean> readableParents = new HashMap<>();

        /**
         * Maps the same full snapshot as {@link NodeMapper#map(NodeRef)} without rereading type/properties.
         * The listing folder is not assumed to be the node's primary parent.
         *
         * @param file information returned by the secured Alfresco file/folder service in this transaction
         * @return a full node snapshot with a permission-filtered primary parent
         */
        Node map(FileInfo file) {
            val ref = file.getNodeRef();
            return snapshot(ref, file.getType(), file.getProperties(), parent(ref, readableParents));
        }
    }

    /**
     * Builds a node snapshot with type, name, sorted aspects and typed properties. Parent inclusion is
     * permission checked and can be disabled for frozen version nodes.
     *
     * @param ref existing node to read inside the caller transaction
     * @param withParent whether to resolve and include a readable primary parent
     * @return a transport snapshot without content-store locations
     */
    private Node map(NodeRef ref, boolean withParent) {
        val type = nodes.getType(ref);
        val parent = withParent ? parent(ref) : null;
        return snapshot(ref, type, nodes.getProperties(ref), parent);
    }

    /** Builds the complete transport snapshot from metadata already read in the caller's transaction. */
    private Node snapshot(NodeRef ref, QName type, Map<QName, Serializable> properties, NodeRef parent) {
        val result = Node.newBuilder().setNodeRef(ref.toString()).setType(type.toString());
        if (parent != null)
            result.setParentRef(parent.toString());
        val name = properties.get(ContentModel.PROP_NAME);
        if (name != null)
            result.setName(name.toString());
        nodes.getAspects(ref).stream().map(QName::toString).sorted().forEach(result::addAspects);
        properties.forEach((key, value) -> result.putProperties(key.toString(), property(value)));
        return result.build();
    }

    /**
     * Looks up the primary association as system, then checks READ_PROPERTIES on its parent as the restored
     * caller. This avoids a denied lookup poisoning the caller transaction without disclosing an unreadable
     * parent.
     *
     * @param ref node whose primary parent is required
     * @return the readable primary parent, or null if absent or unreadable
     */
    private NodeRef parent(NodeRef ref) {
        return parent(ref, null);
    }

    /** Resolves the real primary parent and optionally reuses its permission decision for this page only. */
    private NodeRef parent(NodeRef ref, Map<NodeRef, Boolean> readableParents) {
        val association = AuthenticationUtil.runAsSystem(() -> nodes.getPrimaryParent(ref));
        val parent = association == null ? null : association.getParentRef();
        if (parent == null)
            return null;
        val readable = readableParents == null ? readable(parent) : readableParents.computeIfAbsent(parent, this::readable);
        return readable ? parent : null;
    }

    /** Evaluates visibility as the restored caller, never under the system identity used for lookup. */
    private boolean readable(NodeRef ref) {
        return services.getPermissionService().hasPermission(ref, PermissionService.READ_PROPERTIES) == AccessStatus.ALLOWED;
    }

    /**
     * Converts a scalar or collection property, preserving collection cardinality and element order. Null
     * scalar values use the explicit Protobuf null marker.
     *
     * @param value repository property value, collection or null
     * @return a typed property with its multi-valued flag
     */
    static Property property(Object value) {
        val property = Property.newBuilder();
        if (value instanceof Collection<?>) {
            property.setMultiValued(true);
            ((Collection<?>) value).forEach(item -> property.addValues(value(item)));
        } else {
            property.addValues(value(value));
        }
        return property.build();
    }

    /**
     * Converts one property element to its Protobuf scalar representation. Integral/floating types, dates,
     * booleans, references and qualified names retain their kinds; unknown objects use their string form.
     * ContentData exposes MIME type, encoding, size and locale only.
     *
     * @param value scalar repository value, or null
     * @return a typed transport value that never contains a content-store URL
     */
    static PropertyValue value(Object value) {
        val b = PropertyValue.newBuilder();
        if (value == null)
            b.setNullValue(true);
        else if (value instanceof Boolean)
            b.setBooleanValue((Boolean) value);
        else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            b.setIntegerValue(((Number) value).longValue());
        else if (value instanceof Float || value instanceof Double)
            b.setDoubleValue(((Number) value).doubleValue());
        else if (value instanceof Date)
            b.setTimestampMillis(((Date) value).getTime());
        else if (value instanceof NodeRef)
            b.setNodeRefValue(value.toString());
        else if (value instanceof QName)
            b.setQnameValue(value.toString());
        else if (value instanceof ContentData) {
            val v = (ContentData) value;
            val c = ContentMetadata.newBuilder().setSize(v.getSize());
            if (v.getMimetype() != null)
                c.setMimetype(v.getMimetype());
            if (v.getEncoding() != null)
                c.setEncoding(v.getEncoding());
            if (v.getLocale() != null)
                c.setLocale(v.getLocale().toLanguageTag());
            b.setContentValue(c);
        } else
            b.setStringValue(value.toString());
        return b.build();
    }
}
