package org.saidone.protoalf.platform;

import lombok.val;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.*;
import org.alfresco.service.cmr.security.AccessStatus;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.namespace.QName;
import org.saidone.protoalf.api.*;

import java.util.*;

final class NodeMapper {

    private final NodeService nodes;
    private final ServiceRegistry services;

    NodeMapper(ServiceRegistry services) {
        this.services = services;
        this.nodes = services.getNodeService();
    }

    Node map(NodeRef ref) {
        return map(ref, true);
    }

    /**
     * A frozen version node: it has no primary parent in the store of the live nodes.
     */
    Node mapVersion(NodeRef frozen) {
        return map(frozen, false);
    }

    private Node map(NodeRef ref, boolean withParent) {
        val result = Node.newBuilder().setNodeRef(ref.toString()).setType(nodes.getType(ref).toString());
        val parent = withParent ? parent(ref) : null;
        if (parent != null)
            result.setParentRef(parent.toString());
        val properties = nodes.getProperties(ref);
        val name = properties.get(ContentModel.PROP_NAME);
        if (name != null)
            result.setName(name.toString());
        nodes.getAspects(ref).stream().map(QName::toString).sorted().forEach(result::addAspects);
        properties.forEach((key, value) -> {
            val property = Property.newBuilder();
            if (value instanceof Collection<?>) {
                property.setMultiValued(true);
                ((Collection<?>) value).forEach(v -> property.addValues(value(v)));
            } else
                property.addValues(value(value));
            result.putProperties(key.toString(), property.build());
        });
        return result.build();
    }

    /**
     * Primary parent, only when the caller can read it. Looked up as system: the secured getPrimaryParent throws
     * AccessDeniedException for an unreadable parent, which would mark the surrounding transaction for rollback.
     */
    private NodeRef parent(NodeRef ref) {
        val association = AuthenticationUtil.runAsSystem(() -> nodes.getPrimaryParent(ref));
        val parent = association == null ? null : association.getParentRef();
        if (parent == null)
            return null;
        val readable = services.getPermissionService().hasPermission(parent, PermissionService.READ_PROPERTIES);
        return readable == AccessStatus.ALLOWED ? parent : null;
    }

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
