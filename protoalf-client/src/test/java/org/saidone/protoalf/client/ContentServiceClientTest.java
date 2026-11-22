package org.saidone.protoalf.client;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import lombok.val;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saidone.protoalf.api.ContentInfo;
import org.saidone.protoalf.api.ContentServiceGrpc;
import org.saidone.protoalf.api.CreateDocumentHeader;
import org.saidone.protoalf.api.DownloadContentRequest;
import org.saidone.protoalf.api.DownloadContentResponse;
import org.saidone.protoalf.api.Node;
import org.saidone.protoalf.api.UploadContentRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentServiceClientTest {

    private final AtomicInteger downloads = new AtomicInteger();
    private final AtomicInteger invalidations = new AtomicInteger();
    private final List<UploadContentRequest> uploaded = new ArrayList<>();
    private boolean failBeforePayload;
    private boolean failAfterPayload;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        val name = InProcessServerBuilder.generateName();
        val service = new ContentServiceGrpc.ContentServiceImplBase() {
            @Override public void downloadContent(DownloadContentRequest request, StreamObserver<DownloadContentResponse> observer) {
                val attempt = downloads.incrementAndGet();
                observer.onNext(DownloadContentResponse.newBuilder().setInfo(ContentInfo.newBuilder().setSize(4)).build());
                if (failBeforePayload && attempt == 1) {
                    observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
                    return;
                }
                observer.onNext(DownloadContentResponse.newBuilder().setData(ByteString.copyFromUtf8("text")).build());
                if (failAfterPayload && attempt == 1) {
                    observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
                    return;
                }
                observer.onCompleted();
            }

            @Override public StreamObserver<UploadContentRequest> uploadContent(StreamObserver<Node> observer) {
                return new StreamObserver<>() {
                    @Override public void onNext(UploadContentRequest request) {
                        uploaded.add(request);
                    }

                    @Override public void onError(Throwable error) {
                    }

                    @Override public void onCompleted() {
                        observer.onNext(Node.newBuilder().setName(uploaded.get(0).getHeader().getName()).build());
                        observer.onCompleted();
                    }
                };
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private TicketProvider renewableTickets() {
        return new TicketProvider() {
            @Override public String ticket() {
                return invalidations.get() == 0 ? "EXPIRED" : "RENEWED";
            }

            @Override public void invalidate(String expired) {
                invalidations.incrementAndGet();
            }
        };
    }

    @Test
    void downloadCanRetryBeforeAnyPayloadHasBeenWritten() {
        failBeforePayload = true;
        val client = new ContentServiceClient(channel, renewableTickets(), Duration.ofSeconds(5));
        val out = new ByteArrayOutputStream();
        assertThat(client.downloadContent("doc", out).getSize()).isEqualTo(4);
        assertThat(out.toString()).isEqualTo("text");
        assertThat(downloads).hasValue(2);
        assertThat(invalidations).hasValue(1);
    }

    @Test
    void downloadNeverReplaysBytesAfterAPartialResponse() {
        failAfterPayload = true;
        val client = new ContentServiceClient(channel, renewableTickets(), Duration.ofSeconds(5));
        val out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> client.downloadContent("doc", out)).isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("UNAUTHENTICATED");
        assertThat(downloads).hasValue(1);
        assertThat(out.toString()).isEqualTo("text");
    }

    @Test
    void fixedTicketDownloadIsNotRetried() {
        failBeforePayload = true;
        val client = new ContentServiceClient(channel, TicketProvider.of("FIXED"), Duration.ofSeconds(5));
        assertThatThrownBy(() -> client.downloadContent("doc", new ByteArrayOutputStream()))
                .isInstanceOf(StatusRuntimeException.class).hasMessageContaining("UNAUTHENTICATED");
        assertThat(downloads).hasValue(1);
    }

    @Test
    void uploadSendsOneHeaderAndBoundedChunksWithoutClosingTheInput() {
        val client = new ContentServiceClient(channel, TicketProvider.of("FIXED"), Duration.ofSeconds(5));
        val data = new byte[2 * 1024 * 1024 + 3];
        val input = new ByteArrayInputStream(data) {
            boolean closed;

            @Override public void close() {
                closed = true;
            }
        };
        val header = CreateDocumentHeader.newBuilder().setParentRef("folder").setName("upload.bin").build();
        assertThat(client.uploadContent(header, input).getName()).isEqualTo("upload.bin");
        assertThat(input.closed).isFalse();
        assertThat(uploaded.get(0).getHeader()).isEqualTo(header);
        assertThat(uploaded.subList(1, uploaded.size())).allSatisfy(request -> {
            assertThat(request.getPartCase()).isEqualTo(UploadContentRequest.PartCase.DATA);
            assertThat(request.getData().size()).isBetween(1, 1024 * 1024);
        });
        assertThat(uploaded.stream().mapToInt(request -> request.getData().size()).sum()).isEqualTo(data.length);
    }
}
