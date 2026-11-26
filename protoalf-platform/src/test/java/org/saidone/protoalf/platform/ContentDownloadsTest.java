package org.saidone.protoalf.platform;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import org.alfresco.service.cmr.repository.ContentReader;
import org.junit.jupiter.api.Test;
import org.saidone.protoalf.api.ContentInfo;
import org.saidone.protoalf.api.DownloadContentResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentDownloadsTest {

    private static ContentFetcher.ContentStream content(InputStream input) {
        var reader = mock(ContentReader.class);
        when(reader.getContentInputStream()).thenReturn(input);
        return new ContentFetcher.ContentStream(ContentInfo.getDefaultInstance(), reader);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Download callback timed out");
    }

    private static final class Response extends ServerCallStreamObserver<DownloadContentResponse> {
        final AtomicBoolean ready = new AtomicBoolean();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final List<DownloadContentResponse> messages = new CopyOnWriteArrayList<>();
        final CountDownLatch firstData = new CountDownLatch(1);
        final CountDownLatch terminal = new CountDownLatch(1);
        volatile Runnable onReady;
        volatile Runnable onCancel;
        volatile Throwable error;
        volatile boolean completed;
        volatile boolean pauseAfterData;

        void resume() { ready.set(true); onReady.run(); }
        void cancel() { cancelled.set(true); onCancel.run(); }
        @Override public boolean isReady() { return ready.get(); }
        @Override public boolean isCancelled() { return cancelled.get(); }
        @Override public void setOnReadyHandler(Runnable handler) { onReady = handler; }
        @Override public void setOnCancelHandler(Runnable handler) { onCancel = handler; }
        @Override public void onNext(DownloadContentResponse value) {
            assertTrue(ready.get(), "Sent while transport was not ready");
            messages.add(value);
            if (value.hasData()) {
                if (pauseAfterData) ready.set(false);
                firstData.countDown();
            }
        }
        @Override public void onCompleted() { completed = true; terminal.countDown(); }
        @Override public void onError(Throwable failure) { error = failure; terminal.countDown(); }
        @Override public void setCompression(String compression) {}
        @Override public void disableAutoInboundFlowControl() {}
        @Override public void request(int count) {}
        @Override public void setMessageCompression(boolean enabled) {}
    }

    @Test
    void pausesWithoutReadingAheadAndResumesInOrder() throws Exception {
        var bytes = new byte[3 * GrpcContentService.DOWNLOAD_CHUNK_SIZE + 7];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        var reads = new AtomicInteger();
        var closed = new AtomicInteger();
        var input = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                reads.incrementAndGet();
                return super.read(buffer, offset, length);
            }
            @Override public void close() { closed.incrementAndGet(); }
        };
        var content = content(input);
        var response = new Response();
        response.pauseAfterData = true;
        try (var downloads = new ContentDownloads(1)) {
            downloads.start(content, response);
            verify(content.reader, never()).getContentInputStream();
            response.resume();
            await(response.firstData);
            assertEquals(1, reads.get());
            assertEquals(2, response.messages.size()); // One header and one data chunk.
            assertEquals(0, closed.get());
            response.pauseAfterData = false;
            response.resume();
            await(response.terminal);
            assertTrue(response.completed);
            assertNull(response.error);
            assertEquals(1, response.messages.stream().filter(DownloadContentResponse::hasInfo).count());
            var output = new java.io.ByteArrayOutputStream();
            for (var message : response.messages) message.getData().writeTo(output);
            assertArrayEquals(bytes, output.toByteArray());
            assertEquals(1, closed.get());
        }
    }

    @Test
    void cancellingAPausedTransferClosesItsStreamAndReleasesCapacity() throws Exception {
        var closed = new AtomicInteger();
        var input = new ByteArrayInputStream(new byte[2 * GrpcContentService.DOWNLOAD_CHUNK_SIZE]) {
            @Override public void close() { closed.incrementAndGet(); }
        };
        var response = new Response();
        response.pauseAfterData = true;
        try (var downloads = new ContentDownloads(1)) {
            downloads.start(content(input), response);
            response.resume();
            await(response.firstData);
            response.cancel();
            assertEquals(1, closed.get());
            assertFalse(response.completed);
            assertNull(response.error);
            var next = new Response();
            next.ready.set(true);
            downloads.start(new ContentFetcher.ContentStream(ContentInfo.getDefaultInstance(), null), next);
            await(next.terminal);
            assertTrue(next.completed);
        }
    }

    @Test
    void cancellationClosesAnInputBlockedInRead() throws Exception {
        var reading = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var input = new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                try {
                    if (!closed.await(5, TimeUnit.SECONDS)) throw new IOException("Read was not cancelled");
                    return -1;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                } finally { exited.countDown(); }
            }
            @Override public void close() { closed.countDown(); }
        };
        var response = new Response();
        response.ready.set(true);
        try (var downloads = new ContentDownloads(1)) {
            downloads.start(content(input), response);
            await(reading);
            response.cancel();
            await(closed);
            await(exited);
            assertFalse(response.completed);
            assertNull(response.error);
        }
    }

    @Test
    void boundsActiveTransfersAndCancelsPausedTransfersOnShutdown() throws Exception {
        var downloads = new ContentDownloads(2);
        try {
            var content = content(new ByteArrayInputStream(new byte[0]));
            var first = new Response();
            var second = new Response();
            downloads.start(content, first);
            downloads.start(content, second);
            var rejected = new Response();
            downloads.start(content, rejected);
            await(rejected.terminal);
            assertEquals(Status.Code.RESOURCE_EXHAUSTED, Status.fromThrowable(rejected.error).getCode());
            verify(content.reader, never()).getContentInputStream();
            downloads.close();
            first.resume();
            second.resume();
            verify(content.reader, never()).getContentInputStream();
            var stopped = new Response();
            downloads.start(content, stopped);
            await(stopped.terminal);
            assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(stopped.error).getCode());
        } finally { downloads.close(); }
    }

    @Test
    void readinessLostDuringReadRetainsOnlyOneChunkUntilResume() throws Exception {
        var response = new Response();
        var read = new CountDownLatch(1);
        var input = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                if (count > 0) {
                    response.ready.set(false);
                    read.countDown();
                }
                return count;
            }
        };
        response.ready.set(true);
        try (var downloads = new ContentDownloads(1)) {
            downloads.start(content(input), response);
            await(read);
            assertEquals(1, response.messages.size()); // Header only: the read chunk must wait.
            response.resume();
            await(response.terminal);
            assertTrue(response.completed);
            assertEquals(2, response.messages.size());
            assertArrayEquals(new byte[] {1, 2, 3}, response.messages.get(1).getData().toByteArray());
        }
    }

    @Test
    void cancellationWhileOpeningClosesTheStreamAsSoonAsItArrives() throws Exception {
        var opening = new CountDownLatch(1);
        var allowOpen = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var input = new ByteArrayInputStream(new byte[] {1}) {
            @Override public void close() { closed.countDown(); }
        };
        var reader = mock(ContentReader.class);
        when(reader.getContentInputStream()).thenAnswer(invocation -> {
            opening.countDown();
            await(allowOpen);
            return input;
        });
        var response = new Response();
        response.ready.set(true);
        try (var downloads = new ContentDownloads(1)) {
            downloads.start(new ContentFetcher.ContentStream(ContentInfo.getDefaultInstance(), reader), response);
            await(opening);
            response.cancel();
            allowOpen.countDown();
            await(closed);
            assertEquals(1, response.messages.size());
            assertFalse(response.completed);
            assertNull(response.error);
        } finally { allowOpen.countDown(); }
    }

    @Test
    void readFailureClosesTheStreamAndReleasesCapacity() throws Exception {
        var closed = new AtomicInteger();
        var input = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("broken content"); }
            @Override public void close() { closed.incrementAndGet(); }
        };
        var response = new Response();
        response.ready.set(true);
        try (var downloads = new ContentDownloads(1)) {
            downloads.start(content(input), response);
            await(response.terminal);
            assertEquals(Status.Code.INTERNAL, Status.fromThrowable(response.error).getCode());
            assertEquals(1, closed.get());
            var next = new Response();
            next.ready.set(true);
            downloads.start(new ContentFetcher.ContentStream(ContentInfo.getDefaultInstance(), null), next);
            await(next.terminal);
            assertTrue(next.completed);
        }
    }
}
