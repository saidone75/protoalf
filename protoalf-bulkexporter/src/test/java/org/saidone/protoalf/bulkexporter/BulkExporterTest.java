package org.saidone.protoalf.bulkexporter;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import lombok.val;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.saidone.protoalf.api.*;
import org.saidone.protoalf.client.ContentServiceClient;
import org.saidone.protoalf.client.DictionaryServiceClient;
import org.saidone.protoalf.client.FileFolderServiceClient;
import org.saidone.protoalf.client.NodeServiceClient;
import org.saidone.protoalf.client.TicketProvider;
import org.saidone.protoalf.client.VersionServiceClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BulkExporterTest {

    private static final String CM = "http://www.alfresco.org/model/content/1.0";
    private static final String DOCUMENT = "workspace://SpacesStore/document";
    private static final String PREVIOUS = "versionStore://version2Store/previous";
    @TempDir
    Path temporary;
    private boolean versioned;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        val name = InProcessServerBuilder.generateName();
        val nodes = new NodeServiceGrpc.NodeServiceImplBase() {
            @Override public void getNode(GetNodeRequest request, StreamObserver<Node> observer) {
                observer.onNext(Node.newBuilder().setNodeRef("workspace://SpacesStore/folder").setType("{" + CM + "}folder").build());
                observer.onCompleted();
            }
        };
        val files = new FileFolderServiceGrpc.FileFolderServiceImplBase() {
            @Override public void list(ListChildrenRequest request, StreamObserver<ListChildrenResponse> observer) {
                val node = document("Budget & plan");
                if (versioned) {
                    node.addAspects("{" + CM + "}versionable");
                    node.putProperties("{" + CM + "}versionLabel", property("2.0"));
                }
                observer.onNext(ListChildrenResponse.newBuilder().addEntries(node).build());
                observer.onCompleted();
            }
        };
        val dictionary = new DictionaryServiceGrpc.DictionaryServiceImplBase() {
            @Override public void getAllModels(ListModelsRequest request, StreamObserver<ListModelsResponse> observer) {
                val model = ModelInfo.newBuilder().setName("cm:contentmodel")
                        .addNamespaces(NamespaceInfo.newBuilder().setPrefix("cm").setUri(CM))
                        .addTypes(ClassInfo.newBuilder().setName("cm:folder"))
                        .addTypes(ClassInfo.newBuilder().setName("cm:content"));
                observer.onNext(ListModelsResponse.newBuilder().addModels(model).build());
                observer.onCompleted();
            }
        };
        val versions = new VersionServiceGrpc.VersionServiceImplBase() {
            @Override public void getVersionHistory(ListVersionsRequest request, StreamObserver<ListVersionsResponse> observer) {
                observer.onNext(ListVersionsResponse.newBuilder()
                        .addVersions(VersionInfo.newBuilder().setLabel("2.0").setVersionRef(DOCUMENT).setNode(document("Current")))
                        .addVersions(VersionInfo.newBuilder().setLabel("1.0").setVersionRef(PREVIOUS).setNode(document("Old"))).build());
                observer.onCompleted();
            }
        };
        val contents = new ContentServiceGrpc.ContentServiceImplBase() {
            @Override public void downloadContent(DownloadContentRequest request, StreamObserver<DownloadContentResponse> observer) {
                val bytes = ByteString.copyFromUtf8(PREVIOUS.equals(request.getNodeRef()) ? "old" : "current");
                observer.onNext(DownloadContentResponse.newBuilder().setInfo(ContentInfo.newBuilder().setSize(bytes.size())).build());
                observer.onNext(DownloadContentResponse.newBuilder().setData(bytes).build());
                observer.onCompleted();
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor().addService(nodes).addService(files)
                .addService(dictionary).addService(versions).addService(contents).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static Property property(String value) {
        return Property.newBuilder().addValues(PropertyValue.newBuilder().setStringValue(value)).build();
    }

    private static Node.Builder document(String title) {
        return Node.newBuilder().setNodeRef(DOCUMENT).setName("document.txt").setType("{" + CM + "}content")
                .putProperties("{" + CM + "}title", property(title));
    }

    @Test
    void standaloneExporterComposesServiceClientsAndWritesContentAndMetadata() throws Exception {
        val exporter = new BulkExporter(channel, TicketProvider.of("ticket"), Duration.ofSeconds(5));
        val target = temporary.resolve("export");
        val progress = exporter.export("-root-", target, true, new BulkExporter.Progress());
        assertThat(progress.failure()).isNull();
        assertThat(progress.documents()).isEqualTo(1);
        assertThat(Files.readString(target.resolve("document.txt"))).isEqualTo("current");
        assertThat(Files.readString(target.resolve("document.txt.metadata.properties.xml")))
                .contains("cm:content", "cm:title", "Budget &amp; plan");
        assertThat(channel.isShutdown()).isFalse();
    }

    @Test
    void reusableExporterAcceptsSpecificClientsAndPreservesPreviousVersions() throws Exception {
        versioned = true;
        val tickets = TicketProvider.of("ticket");
        val deadline = Duration.ofSeconds(5);
        val exporter = new BulkExporter(new NodeServiceClient(channel, tickets, deadline),
                new FileFolderServiceClient(channel, tickets, deadline), new ContentServiceClient(channel, tickets, deadline),
                new VersionServiceClient(channel, tickets, deadline), new DictionaryServiceClient(channel, tickets, deadline));
        val target = temporary.resolve("versions");
        val progress = exporter.export("-root-", target, false, new BulkExporter.Progress());
        assertThat(progress.failure()).isNull();
        assertThat(progress.versions()).isEqualTo(1);
        assertThat(Files.readString(target.resolve("document.txt"))).isEqualTo("current");
        assertThat(Files.readString(target.resolve("document.txt.v1"))).isEqualTo("old");
        assertThat(Files.readString(target.resolve("document.txt.metadata.properties.xml.v1"))).contains("Old");
    }
}
