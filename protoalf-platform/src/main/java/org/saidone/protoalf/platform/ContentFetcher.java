package org.saidone.protoalf.platform;

import com.google.protobuf.ByteString;
import io.grpc.Status;
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
 * Reads document content behind the GetContent RPC, as stored or as plain text extracted by the Alfresco
 * synchronous transforms. Runs inside the caller's read-only transaction, as the caller.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class ContentFetcher {

    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final String TEXT_PLAIN = MimetypeMap.MIMETYPE_TEXT_PLAIN;
    private static final String UTF_8 = StandardCharsets.UTF_8.name();
    private final ServiceRegistry services;

    /**
     * Null when the repository offers no synchronous transforms: text extraction is then unavailable.
     */
    private final SynchronousTransformClient transforms;

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
     * The stored bytes of a document for the DownloadContent RPC: resolved here, inside the caller's read-only
     * transaction, and streamed by the caller after the transaction ends.
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
     * Info message and reader of a document to download; the reader is null for a document without content.
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class ContentStream {

        final ContentInfo info;

        final ContentReader reader;
    }

    /**
     * Plain text UTF-8 reader of the document: as is, re-encoded, or extracted by a transform.
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

    private static void reencode(ContentReader source, Charset charset, ContentWriter target) {
        try (val in = new InputStreamReader(source.getContentInputStream(), charset);
            val out = new OutputStreamWriter(target.getContentOutputStream(), StandardCharsets.UTF_8)) {
            in.transferTo(out);
        } catch (IOException e) {
            throw new ContentIOException("Cannot convert the text content to UTF-8", e);
        }
    }

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
     * Reads [offset, offset + limit) of the content; a text slice is shortened to end on a character boundary.
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
     * InputStream.skipNBytes (Java 12): skips exactly {@code n} bytes, EOFException if the stream ends first.
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
