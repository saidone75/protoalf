package org.saidone.protoalf.client;

import io.grpc.*;
import lombok.val;
import org.saidone.protoalf.api.*;
import org.saidone.protoalf.api.FileFolderServiceGrpc.FileFolderServiceBlockingStub;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client of the platform's file/folder service: listing, creation, moves and copies.
 *
 * <p>Each call sends the shared provider's Alfresco ticket and a per-call deadline. Unary calls retry once
 * after UNAUTHENTICATED if the provider supplies a different ticket. The caller owns the channel and should
 * share one TicketProvider across service clients.
 */
public class FileFolderServiceClient {

    private final FileFolderServiceBlockingStub stub;
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
    public FileFolderServiceClient(Channel channel, TicketProvider tickets, Duration deadline) {
        this(FileFolderServiceGrpc.newBlockingStub(channel), tickets, deadline);
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
    public FileFolderServiceClient(FileFolderServiceBlockingStub stub, TicketProvider tickets, Duration deadline) {
        this.stub = stub;
        this.support = new ClientCallSupport(tickets, deadline);
    }

    /**
     * Lists the files and folders directly under a parent, ordered by name.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param skipCount non-negative number of entries to skip
     * @param maxItems page size in 1..1000; zero selects the server default
     * @return the result page, total count and continuation flag
     */
    public ListChildrenResponse list(String nodeRef, int skipCount, int maxItems) {
        return support.call(stub, s -> s.list(ListChildrenRequest.newBuilder().setNodeRef(nodeRef).setSkipCount(skipCount).setMaxItems(maxItems).build()));
    }

    /**
     * Creates a document or folder with optional properties, aspects and document content.
     *
     * @param parentRef reference or alias of the destination parent folder
     * @param name node name; empty preserves the name on update, move or copy
     * @param type qualified type name; empty preserves the type on update and defaults to cm:content on create
     * @param properties scalar text properties keyed by qualified name
     * @param content document bytes and metadata; optional only on creation
     * @return the resulting node snapshot
     */
    public Node create(String parentRef, String name, String type, Map<String, String> properties, Content content) {
        return create(parentRef, name, type, properties, Map.of(), List.of(), content);
    }

    /**
     * Creates a document or folder with optional properties, aspects and document content.
     *
     * @param parentRef reference or alias of the destination parent folder
     * @param name node name; empty preserves the name on update, move or copy
     * @param type qualified type name; empty preserves the type on update and defaults to cm:content on create
     * @param properties scalar text properties keyed by qualified name
     * @param propertyValues ordered property value lists; names must not also occur in properties
     * @param aspects qualified aspect names to add during creation
     * @param content document bytes and metadata; optional only on creation
     * @return the resulting node snapshot
     */
    public Node create(String parentRef, String name, String type, Map<String, String> properties, Map<String, List<String>> propertyValues, List<String> aspects, Content content) {
        val request = CreateNodeRequest.newBuilder().setParentRef(parentRef).setName(name).setType(type).putAllProperties(properties).putAllPropertyValues(ClientCallSupport.toPropertyValues(propertyValues)).addAllAspects(aspects);
        if (content != null)
            request.setContent(content);
        return support.call(stub, s -> s.create(request.build()));
    }

    /**
     * Moves a node to another parent, optionally renaming it.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param targetParentRef reference or alias of the destination parent folder
     * @param name node name; empty preserves the name on update, move or copy
     * @return the resulting node snapshot
     */
    public Node move(String nodeRef, String targetParentRef, String name) {
        return support.call(stub, s -> s.move(MoveNodeRequest.newBuilder().setNodeRef(nodeRef).setTargetParentRef(targetParentRef).setName(name).build()));
    }

    /**
     * Copies a node to another parent, optionally changing the copied name.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param targetParentRef reference or alias of the destination parent folder
     * @param name node name; empty preserves the name on update, move or copy
     * @return the resulting node snapshot
     */
    public Node copy(String nodeRef, String targetParentRef, String name) {
        return support.call(stub, s -> s.copy(CopyNodeRequest.newBuilder().setNodeRef(nodeRef).setTargetParentRef(targetParentRef).setName(name).build()));
    }
}
