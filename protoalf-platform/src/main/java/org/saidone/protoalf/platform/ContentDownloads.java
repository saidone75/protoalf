package org.saidone.protoalf.platform;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.saidone.protoalf.api.DownloadContentResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded, readiness-driven downloads. Repository lookup has already finished before a transfer starts. */
@Slf4j
final class ContentDownloads implements AutoCloseable {

    private final Semaphore slots;
    private final ThreadPoolExecutor executor;
    private final Set<Download> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    ContentDownloads(int concurrency) {
        if (concurrency < 1) throw new IllegalArgumentException("Download concurrency must be positive");
        slots = new Semaphore(concurrency);
        executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency), task -> {
                    var thread = new Thread(task, "alfresco-download");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    void start(ContentFetcher.ContentStream content, StreamObserver<DownloadContentResponse> response) {
        if (closed) {
            response.onError(Status.UNAVAILABLE.withDescription("Content service is stopping").asRuntimeException());
            return;
        }
        if (!(response instanceof ServerCallStreamObserver<?>)) {
            response.onError(Status.INTERNAL.withDescription("Download requires a server call observer").asRuntimeException());
            return;
        }
        if (!slots.tryAcquire()) {
            response.onError(Status.RESOURCE_EXHAUSTED.withDescription("Too many active content downloads").asRuntimeException());
            return;
        }
        @SuppressWarnings("unchecked")
        var observer = (ServerCallStreamObserver<DownloadContentResponse>) response;
        var download = new Download(content, observer);
        active.add(download);
        try {
            observer.setOnCancelHandler(download::cancel);
            observer.setOnReadyHandler(download::schedule);
            if (closed || observer.isCancelled()) download.cancel();
            else download.schedule();
        } catch (RuntimeException e) {
            download.fail(e);
        }
    }

    @Override public void close() {
        closed = true;
        active.forEach(Download::cancel);
        executor.shutdownNow();
    }

    private final class Download {
        private final ContentFetcher.ContentStream content;
        private final ServerCallStreamObserver<DownloadContentResponse> observer;
        private final Context context = Context.current();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicReference<InputStream> input = new AtomicReference<>();
        // The pump alone owns these fields. At most one task runs for a transfer at a time.
        private boolean headerSent;
        private byte[] buffer;
        private ByteString pending;

        Download(ContentFetcher.ContentStream content, ServerCallStreamObserver<DownloadContentResponse> observer) {
            this.content = content;
            this.observer = observer;
        }

        void schedule() {
            if (finished.get() || !observer.isReady() || !scheduled.compareAndSet(false, true)) return;
            try {
                executor.execute(context.wrap(this::pump));
            } catch (RejectedExecutionException e) {
                scheduled.set(false);
                fail(e);
            }
        }

        private void pump() {
            try {
                while (!finished.get() && observer.isReady()) {
                    if (observer.isCancelled()) {
                        cancel();
                        return;
                    }
                    if (!headerSent) {
                        observer.onNext(DownloadContentResponse.newBuilder().setInfo(content.info).build());
                        headerSent = true;
                        if (content.reader == null) {
                            complete();
                            return;
                        }
                        continue;
                    }
                    if (pending == null) {
                        var stream = input.get();
                        if (stream == null) {
                            stream = content.reader.getContentInputStream();
                            input.set(stream);
                            // Cancellation may have happened while the reader was opening.
                            if (finished.get()) {
                                closeInput();
                                return;
                            }
                        }
                        if (buffer == null) buffer = new byte[GrpcContentService.DOWNLOAD_CHUNK_SIZE];
                        int count = stream.read(buffer);
                        if (finished.get()) return;
                        if (count == -1) {
                            complete();
                            return;
                        }
                        if (count == 0) continue;
                        pending = ByteString.copyFrom(buffer, 0, count);
                    }
                    // Readiness may change while disk I/O is in progress. Retain at most one unsent chunk.
                    if (!finished.get() && observer.isReady()) {
                        observer.onNext(DownloadContentResponse.newBuilder().setData(pending).build());
                        pending = null;
                    }
                }
            } catch (IOException | RuntimeException e) {
                fail(e);
            } finally {
                scheduled.set(false);
                // Covers a readiness notification delivered while this pump was still scheduled.
                if (!finished.get() && observer.isReady()) schedule();
            }
        }

        void cancel() {
            if (finish()) log.debug("Download cancelled");
        }

        private void complete() {
            if (finish() && !observer.isCancelled()) observer.onCompleted();
        }

        void fail(Throwable failure) {
            if (!finish() || observer.isCancelled()) return;
            log.warn("Content download failed", failure);
            var status = failure instanceof RejectedExecutionException
                    ? Status.UNAVAILABLE.withDescription("Content download executor unavailable")
                    : Status.INTERNAL.withDescription("Content download failed");
            observer.onError(status.asRuntimeException());
        }

        private boolean finish() {
            if (!finished.compareAndSet(false, true)) return false;
            closeInput();
            active.remove(this);
            slots.release();
            return true;
        }

        private void closeInput() {
            var stream = input.getAndSet(null);
            if (stream == null) return;
            try {
                stream.close();
            } catch (IOException e) {
                log.debug("Cannot close content download stream", e);
            }
        }
    }
}
