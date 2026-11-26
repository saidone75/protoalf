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
 *
 * <p>Writes take one typed property map, built with {@link Properties}. Omitted properties remain unchanged;
 * scalar/list cardinality and value kinds must match the repository dictionary.
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
     * Merges typed scalar or multi-valued properties.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param properties typed properties keyed by qualified name
     * @return the resulting node snapshot
     */
    public Node addProperties(String nodeRef, Map<String, Property> properties) {
        val request = AddPropertiesRequest.newBuilder().setNodeRef(nodeRef).putAllProperties(properties).build();
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
     * Adds an aspect and merges typed properties atomically.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param aspect qualified name of the aspect to add or remove
     * @param properties typed properties keyed by qualified name
     * @return the resulting node snapshot
     */
    public Node addAspect(String nodeRef, String aspect, Map<String, Property> properties) {
        val request = AddAspectRequest.newBuilder().setNodeRef(nodeRef).setAspect(aspect).putAllProperties(properties).build();
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
     * @param properties typed properties keyed by qualified name
     * @return the resulting node snapshot
     */
    public Node updateNode(String nodeRef, String name, Map<String, Property> properties) {
        return updateNode(nodeRef, name, "", properties);
    }

    /**
     * Atomically updates the supplied name, type, properties and aspect changes.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param name node name; empty preserves the name on update, move or copy
     * @param type qualified type name; empty preserves the type on update and defaults to cm:content on create
     * @param properties typed properties keyed by qualified name
     * @return the resulting node snapshot
     */
    public Node updateNode(String nodeRef, String name, String type, Map<String, Property> properties) {
        return updateNode(nodeRef, name, type, properties, List.of(), List.of());
    }

    /**
     * Atomically updates the supplied name, type, properties and aspect changes.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param name node name; empty preserves the name on update, move or copy
     * @param type qualified type name; empty preserves the type on update and defaults to cm:content on create
     * @param properties typed properties keyed by qualified name
     * @param addAspects qualified aspect names to add if absent
     * @param removeAspects qualified aspect names to remove if present
     * @return the resulting node snapshot
     */
    public Node updateNode(String nodeRef, String name, String type, Map<String, Property> properties, List<String> addAspects, List<String> removeAspects) {
        return support.call(stub, s -> s.updateNode(UpdateNodeRequest.newBuilder().setNodeRef(nodeRef).setName(name).setType(type).putAllProperties(properties).addAllAddAspects(addAspects).addAllRemoveAspects(removeAspects).build()));
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
