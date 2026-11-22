package org.saidone.protoalf.platform;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.content.MimetypeMap;
import org.alfresco.repo.content.transform.UnsupportedTransformationException;
import org.alfresco.repo.rendition2.SynchronousTransformClient;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.saidone.protoalf.api.Content;
import org.saidone.protoalf.api.ContentInfo;
import org.saidone.protoalf.api.GetContentResponse;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Map;
import java.util.Objects;

/**
 * Reads stored document bytes and optionally extracts plain text for the content adapters.
 *
 * <p>Repository access runs in the caller's existing read-only transaction. Text is returned as UTF-8: plain
 * text is reused or re-encoded, and other formats require Alfresco's synchronous transform client. Byte offsets
 * in text mode refer to the transformed UTF-8 content, not to the original binary document.
 *
 * <p>Slices are bounded by {@link #MAX_BYTES}; downloads instead return a reader that the service consumes
 * outside the transaction. Opened slice and conversion streams are always closed by this helper.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class ContentFetcher {

    /**
     * Maximum content slice size, 2 MiB, also used when no explicit limit is supplied.
     */
    static final int MAX_BYTES = 2 * 1024 * 1024;

    /**
     * Target MIME type for extracted or re-encoded document text.
     */
    private static final String TEXT_PLAIN = MimetypeMap.MIMETYPE_TEXT_PLAIN;

    /**
     * Canonical encoding label used for text responses and temporary writers.
     */
    private static final String UTF_8 = StandardCharsets.UTF_8.name();

    /**
     * Repository services used under the caller's transaction and authentication context.
     */
    private final ServiceRegistry services;

    /**
     * Synchronous text-transform client; null disables extraction for non-plain-text content.
     */
    private final SynchronousTransformClient transforms;

    /**
     * Reads a bounded document slice, optionally after conversion to UTF-8 text. A document without stored
     * content returns an empty response. Text offsets apply to transformed bytes.
     *
     * @param ref document or frozen-version reference
     * @param text true to extract text, false to return stored bytes
     * @param offset non-negative byte offset in the selected representation
     * @param maxBytes slice limit in 1..MAX_BYTES, or zero for the maximum
     * @return the slice, representation size and continuation flag
     * @throws IllegalArgumentException if pagination is invalid or the node is not a document
     * @throws ContentIOException if stored content cannot be read
     * @throws StatusRuntimeException if text extraction is unavailable, unsupported or fails
     */
    GetContentResponse read(NodeRef ref, boolean text, long offset, int maxBytes) {
        if (offset < 0)
            throw new IllegalArgumentException("offset must be >= 0");
        val limit = maxBytes == 0 ? MAX_BYTES : maxBytes;
        if (limit < 1 || limit > MAX_BYTES)
            throw new IllegalArgumentException(String.format("max_bytes must be 1..%d", MAX_BYTES));
        val nodes = services.getNodeService();
        if (!services.getDictionaryService().isSubClass(nodes.getType(ref), ContentModel.TYPE_CONTENT))
            throw new IllegalArgumentException("Only documents (cm:content) have content");
        ContentReader reader = services.getContentService().getReader(ref, ContentModel.PROP_CONTENT);
        if (reader == null || !reader.exists()) {
            val empty = Content.newBuilder();
            if (text)
                empty.setMimetype(TEXT_PLAIN).setEncoding(UTF_8);
            return GetContentResponse.newBuilder().setContent(empty).build();
        }
        if (text)
            reader = toText(ref, reader);
        return slice(reader, offset, limit, text);
    }

    /**
     * Resolves stored-content metadata and a reader inside the caller transaction without opening an input
     * stream. The caller may consume that reader after the transaction completes.
     *
     * @param ref document or frozen-version reference
     * @return download metadata and an optional reader; null reader means no stored content
     * @throws IllegalArgumentException if the node is not a document
     */
    ContentStream open(NodeRef ref) {
        val nodes = services.getNodeService();
        if (!services.getDictionaryService().isSubClass(nodes.getType(ref), ContentModel.TYPE_CONTENT))
            throw new IllegalArgumentException("Only documents (cm:content) have content");
        val reader = services.getContentService().getReader(ref, ContentModel.PROP_CONTENT);
        if (reader == null || !reader.exists())
            return new ContentStream(ContentInfo.getDefaultInstance(), null);
        val info = ContentInfo.newBuilder().setMimetype(Objects.toString(reader.getMimetype(), "")).setEncoding(Objects.toString(reader.getEncoding(), "")).setSize(reader.getSize());
        return new ContentStream(info.build(), reader);
    }

    /**
     * Carries download metadata and an optional repository reader across the transaction boundary.
     *
     * <p>A {@code null} reader represents a document without stored content. The consumer owns any input stream
     * opened from the reader; constructing this holder does not open a stream.
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class ContentStream {

        /**
         * Content metadata emitted before download data chunks.
         */
        final ContentInfo info;

        /**
         * Stored-content reader, or null when the document has no stored bytes.
         */
        final ContentReader reader;
    }

    /**
     * Obtains a UTF-8 text reader by reuse, re-encoding or synchronous transformation. Re-encoding and
     * transforms use a repository temporary writer; they do not overwrite the node content.
     *
     * @param ref source node, supplied to the transform client
     * @param source reader for the stored document bytes
     * @return a reader over the original or temporary UTF-8 text
     * @throws StatusRuntimeException with FAILED_PRECONDITION for unavailable/unsupported transforms or
     *     UNAVAILABLE for transform content failures
     * @throws ContentIOException if plain-text re-encoding fails
     */
    private ContentReader toText(NodeRef ref, ContentReader source) {
        val mimetype = Objects.toString(source.getMimetype(), "");
        val charset = charset(source.getEncoding());
        if (TEXT_PLAIN.equals(mimetype) && StandardCharsets.UTF_8.equals(charset))
            return source;
        val writer = services.getContentService().getTempWriter();
        writer.setMimetype(TEXT_PLAIN);
        writer.setEncoding(UTF_8);
        if (TEXT_PLAIN.equals(mimetype)) {
            reencode(source, charset, writer);
            return writer.getReader();
        }
        if (transforms == null)
            throw Status.FAILED_PRECONDITION.withDescription("Text extraction is not available in this repository").asRuntimeException();
        try {
            transforms.transform(source, writer, Map.of(), null, ref);
        } catch (UnsupportedTransformationException e) {
            log.debug("No text transform for {} ({})", ref, mimetype, e);
            throw Status.FAILED_PRECONDITION.withDescription(String.format("Cannot extract text from %s content: no transform available (is the transform service enabled?)", mimetype)).asRuntimeException();
        } catch (ContentIOException e) {
            log.warn("Text extraction failed for {} ({})", ref, mimetype, e);
            throw Status.UNAVAILABLE.withDescription("Text extraction failed: the transform service did not answer").asRuntimeException();
        }
        return writer.getReader();
    }

    /**
     * Copies character data into a UTF-8 writer and closes both streams, leaving node content unchanged when
     * the target is a temporary writer.
     *
     * @param source content reader to decode
     * @param charset source character set
     * @param target writer receiving UTF-8 bytes
     * @throws ContentIOException if either stream cannot be read, written or closed
     */
    private static void reencode(ContentReader source, Charset charset, ContentWriter target) {
        try (val in = new InputStreamReader(source.getContentInputStream(), charset);
            val out = new OutputStreamWriter(target.getContentOutputStream(), StandardCharsets.UTF_8)) {
            in.transferTo(out);
        } catch (IOException e) {
            throw new ContentIOException("Cannot convert the text content to UTF-8", e);
        }
    }

    /**
     * Resolves a stored encoding, falling back to UTF-8 when it is blank, unknown or syntactically invalid.
     *
     * @param encoding stored encoding name, possibly null or blank
     * @return the supported source character set, or UTF-8
     */
    private static Charset charset(String encoding) {
        if (encoding == null || encoding.isBlank())
            return StandardCharsets.UTF_8;
        try {
            return Charset.forName(encoding);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Reads at most limit bytes from an offset, peeking one extra byte to detect continuation. UTF-8 text is
     * shortened at a trailing character boundary. An offset past EOF yields empty content.
     *
     * @param reader reader for the selected stored or text representation
     * @param offset non-negative starting byte offset
     * @param limit maximum number of bytes returned
     * @param text whether to preserve a trailing UTF-8 character boundary
     * @return a content slice with total representation size and continuation flag
     * @throws ContentIOException if the content stream cannot be read
     */
    private static GetContentResponse slice(ContentReader reader, long offset, int limit, boolean text) {
        byte[] buffer;
        try (val in = reader.getContentInputStream()) {
            skipFully(in, offset);
            // One byte more than needed tells whether the content continues after the slice.
            buffer = in.readNBytes(limit + 1);
        } catch (EOFException e) {
            buffer = new byte[0];
        } catch (IOException e) {
            throw new ContentIOException("Cannot read the document content", e);
        }
        val hasMore = buffer.length > limit;
        int end = Math.min(buffer.length, limit);
        if (text && hasMore) {
            // buffer[end] is the first byte left out: while it is a UTF-8 continuation byte the slice splits a character.
            int boundary = end;
            while (boundary > 0 && (buffer[boundary] & 0xC0) == 0x80) boundary--;
            if (boundary > 0)
                end = boundary;
        }
        val content = Content.newBuilder().setData(ByteString.copyFrom(buffer, 0, end)).setMimetype(Objects.toString(reader.getMimetype(), "")).setEncoding(Objects.toString(reader.getEncoding(), ""));
        return GetContentResponse.newBuilder().setContent(content).setSize(reader.getSize()).setHasMore(hasMore).build();
    }

    /**
     * Advances an input stream by exactly the requested number of bytes without closing it. Reads one byte when
     * skip makes no progress, matching skipNBytes behavior on older JDKs.
     *
     * @param in stream to advance
     * @param n number of bytes to skip
     * @throws EOFException if the stream ends before all requested bytes are skipped
     * @throws IOException if advancing or reading the stream fails
     */
    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            val skipped = in.skip(n);
            if (skipped > 0)
                n -= skipped;
            else if (in.read() == -1)
                throw new EOFException();
            else
                n--;
        }
    }
}
