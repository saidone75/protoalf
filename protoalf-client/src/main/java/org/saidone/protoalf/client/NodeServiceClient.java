package org.saidone.protoalf.client;

import io.grpc.*;
import lombok.val;
import org.saidone.protoalf.api.*;
import org.saidone.protoalf.api.NodeServiceGrpc.NodeServiceBlockingStub;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client of the platform's node service for properties, types, aspects and node snapshots. File/folder
 * operations, content, search, versions and model lookups have dedicated clients.
 *
 * <p>Each call sends the shared provider's Alfresco ticket and a per-call deadline. Unary calls retry once
 * after UNAUTHENTICATED if the provider supplies a different ticket. The caller owns the channel and should
 * share one TicketProvider across service clients.
 */
public class NodeServiceClient {

    private final NodeServiceBlockingStub stub;
    private final ClientCallSupport support;

    /**
     * Creates this service client with shared authentication and a per-call deadline.
     *
     * <p>The caller retains ownership of the channel; closing an application should close its shared channel
     * once.
     *
     * @param channel shared gRPC channel; ownership remains with the caller
     * @param tickets shared ticket provider, normally one AuthenticationServiceClient
     * @param deadline per-call deadline applied to every RPC attempt
     */
    public NodeServiceClient(Channel channel, TicketProvider tickets, Duration deadline) {
        this(NodeServiceGrpc.newBlockingStub(channel), tickets, deadline);
    }

    /**
     * Creates this service client with shared authentication and a per-call deadline.
     *
     * <p>The caller retains ownership of the channel; closing an application should close its shared channel
     * once.
     *
     * @param stub preconfigured stub for this repository service
     * @param tickets shared ticket provider, normally one AuthenticationServiceClient
     * @param deadline per-call deadline applied to every RPC attempt
     */
    public NodeServiceClient(NodeServiceBlockingStub stub, TicketProvider tickets, Duration deadline) {
        this.stub = stub;
        this.support = new ClientCallSupport(tickets, deadline);
    }

    /**
     * Reads the typed property map using qualified property names as keys.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @return the read-only typed property map, keyed by expanded qualified names
     */
    public Map<String, Property> getProperties(String nodeRef) {
        return support.call(stub, service -> service.getProperties(nodeRef(nodeRef)).getPropertiesMap());
    }

    /**
     * Merges scalar properties; unrelated metadata remains unchanged.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param properties scalar text properties keyed by qualified name
     * @return the resulting node snapshot
     */
    public Node addProperties(String nodeRef, Map<String, String> properties) {
        return addProperties(nodeRef, properties, Map.of());
    }

    /**
     * Merges scalar and explicit-list properties; names must not occur in both maps.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param properties scalar text properties keyed by qualified name
     * @param propertyValues ordered property value lists; names must not also occur in properties
     * @return the resulting node snapshot
     */
    public Node addProperties(String nodeRef, Map<String, String> properties, Map<String, List<String>> propertyValues) {
        val request = AddPropertiesRequest.newBuilder().setNodeRef(nodeRef).putAllProperties(properties).putAllPropertyValues(ClientCallSupport.toPropertyValues(propertyValues)).build();
        return support.call(stub, service -> service.addProperties(request));
    }

    /**
     * Returns the node's expanded qualified type name.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @return the expanded qualified type name
     */
    public String getType(String nodeRef) {
        return support.call(stub, service -> service.getType(nodeRef(nodeRef)).getType());
    }

    /**
     * Changes the type while preserving the document/folder category.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param type qualified type name; empty preserves the type on update and defaults to cm:content on create
     * @return the resulting node snapshot
     */
    public Node setType(String nodeRef, String type) {
        val request = SetTypeRequest.newBuilder().setNodeRef(nodeRef).setType(type).build();
        return support.call(stub, service -> service.setType(request));
    }

    /**
     * Returns the node's applied aspects as expanded qualified names.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @return the set of applied aspect names
     */
    public Set<String> getAspects(String nodeRef) {
        return support.call(stub, service -> Set.copyOf(service.getAspects(nodeRef(nodeRef)).getAspectsList()));
    }

    /**
     * Adds a known aspect and optional scalar properties.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param aspect qualified name of the aspect to add or remove
     * @param properties scalar text properties keyed by qualified name
     * @return the resulting node snapshot
     */
    public Node addAspect(String nodeRef, String aspect, Map<String, String> properties) {
        return addAspect(nodeRef, aspect, properties, Map.of());
    }

    /**
     * Adds an aspect and merges scalar/list-valued properties atomically.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param aspect qualified name of the aspect to add or remove
     * @param properties scalar text properties keyed by qualified name
     * @param propertyValues ordered property value lists; names must not also occur in properties
     * @return the resulting node snapshot
     */
    public Node addAspect(String nodeRef, String aspect, Map<String, String> properties, Map<String, List<String>> propertyValues) {
        val request = AddAspectRequest.newBuilder().setNodeRef(nodeRef).setAspect(aspect).putAllProperties(properties).putAllPropertyValues(ClientCallSupport.toPropertyValues(propertyValues)).build();
        return support.call(stub, service -> service.addAspect(request));
    }

    /**
     * Removes a known aspect if present.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param aspect qualified name of the aspect to add or remove
     * @return the resulting node snapshot
     */
    public Node removeAspect(String nodeRef, String aspect) {
        val request = RemoveAspectRequest.newBuilder().setNodeRef(nodeRef).setAspect(aspect).build();
        return support.call(stub, service -> service.removeAspect(request));
    }

    /**
     * Reads a complete node snapshot, including a readable primary parent.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @return the resulting node snapshot
     */
    public Node getNode(String nodeRef) {
        return support.call(stub, s -> s.getNode(GetNodeRequest.newBuilder().setNodeRef(nodeRef).build()));
    }

    /**
     * Atomically updates the supplied name, type, properties and aspect changes.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param name node name; empty preserves the name on update, move or copy
     * @param properties scalar text properties keyed by qualified name
     * @return the resulting node snapshot
     */
    public Node updateNode(String nodeRef, String name, Map<String, String> properties) {
        return updateNode(nodeRef, name, "", properties);
    }

    /**
     * Atomically updates the supplied name, type, properties and aspect changes.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param name node name; empty preserves the name on update, move or copy
     * @param type qualified type name; empty preserves the type on update and defaults to cm:content on create
     * @param properties scalar text properties keyed by qualified name
     * @return the resulting node snapshot
     */
    public Node updateNode(String nodeRef, String name, String type, Map<String, String> properties) {
        return updateNode(nodeRef, name, type, properties, Map.of(), List.of(), List.of());
    }

    /**
     * Atomically updates the supplied name, type, properties and aspect changes.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param name node name; empty preserves the name on update, move or copy
     * @param type qualified type name; empty preserves the type on update and defaults to cm:content on create
     * @param properties scalar text properties keyed by qualified name
     * @param propertyValues ordered property value lists; names must not also occur in properties
     * @param addAspects qualified aspect names to add if absent
     * @param removeAspects qualified aspect names to remove if present
     * @return the resulting node snapshot
     */
    public Node updateNode(String nodeRef, String name, String type, Map<String, String> properties, Map<String, List<String>> propertyValues, List<String> addAspects, List<String> removeAspects) {
        return support.call(stub, s -> s.updateNode(UpdateNodeRequest.newBuilder().setNodeRef(nodeRef).setName(name).setType(type).putAllProperties(properties).putAllPropertyValues(ClientCallSupport.toPropertyValues(propertyValues)).addAllAddAspects(addAspects).addAllRemoveAspects(removeAspects).build()));
    }

    /**
     * Deletes a node through the repository, preserving the server's protected-folder checks.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     */
    public void deleteNode(String nodeRef) {
        support.call(stub, s -> s.deleteNode(DeleteNodeRequest.newBuilder().setNodeRef(nodeRef).build()));
    }

    private static NodeRefRequest nodeRef(String nodeRef) {
        return NodeRefRequest.newBuilder().setNodeRef(nodeRef).build();
    }
}
