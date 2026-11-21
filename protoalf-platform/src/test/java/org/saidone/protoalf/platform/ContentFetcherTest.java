package org.saidone.protoalf.platform;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.content.transform.UnsupportedTransformationException;
import org.alfresco.repo.rendition2.SynchronousTransformClient;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saidone.protoalf.api.GetContentResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ContentFetcherTest {

    private static final NodeRef DOC = new NodeRef("workspace://SpacesStore/doc");
    private static final NodeRef FOLDER = new NodeRef("workspace://SpacesStore/folder");

    private final ServiceRegistry services = mock(ServiceRegistry.class);
    private final ContentService contents = mock(ContentService.class);
    private final SynchronousTransformClient transforms = mock(SynchronousTransformClient.class);
    private ContentFetcher fetcher;

    @BeforeEach
    void setUp() {
        var nodes = mock(NodeService.class);
        var dictionary = mock(DictionaryService.class);
        when(services.getNodeService()).thenReturn(nodes);
        when(services.getDictionaryService()).thenReturn(dictionary);
        when(services.getContentService()).thenReturn(contents);
        when(nodes.getType(DOC)).thenReturn(ContentModel.TYPE_CONTENT);
        when(nodes.getType(FOLDER)).thenReturn(ContentModel.TYPE_FOLDER);
        when(dictionary.isSubClass(ContentModel.TYPE_CONTENT, ContentModel.TYPE_CONTENT)).thenReturn(true);
        fetcher = new ContentFetcher(services, transforms);
    }

    private static ContentReader reader(byte[] data, String mimetype, String encoding) {
        var reader = mock(ContentReader.class);
        when(reader.exists()).thenReturn(true);
        when(reader.getMimetype()).thenReturn(mimetype);
        when(reader.getEncoding()).thenReturn(encoding);
        when(reader.getSize()).thenReturn((long) data.length);
        when(reader.getContentInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(data));
        return reader;
    }

    private void stored(byte[] data, String mimetype, String encoding) {
        var reader = reader(data, mimetype, encoding);
        when(contents.getReader(DOC, ContentModel.PROP_CONTENT)).thenReturn(reader);
    }

    /** Temp writer whose reader returns what was written, as text/plain UTF-8. */
    private ByteArrayOutputStream tempWriter(String extracted) {
        var written = new ByteArrayOutputStream();
        var writer = mock(ContentWriter.class);
        when(contents.getTempWriter()).thenReturn(writer);
        when(writer.getContentOutputStream()).thenReturn(written);
        when(writer.getReader()).thenAnswer(invocation -> reader(
                extracted != null ? extracted.getBytes(StandardCharsets.UTF_8) : written.toByteArray(), "text/plain", "UTF-8"));
        return written;
    }

    @Test
    void binaryContentIsReturnedAsStoredInSlices() {
        stored(new byte[]{1, 2, 3, 4, 5}, "application/pdf", "UTF-8");

        GetContentResponse first = fetcher.read(DOC, false, 0, 3);
        assertArrayEquals(new byte[]{1, 2, 3}, first.getContent().getData().toByteArray());
        assertEquals("application/pdf", first.getContent().getMimetype());
        assertEquals(5, first.getSize());
        assertTrue(first.getHasMore());

        GetContentResponse last = fetcher.read(DOC, false, 3, 3);
        assertArrayEquals(new byte[]{4, 5}, last.getContent().getData().toByteArray());
        assertFalse(last.getHasMore());

        GetContentResponse beyond = fetcher.read(DOC, false, 10, 3);
        assertTrue(beyond.getContent().getData().isEmpty());
        assertFalse(beyond.getHasMore());
        verifyNoInteractions(transforms);
    }

    @Test
    void zeroMaxBytesReadsTheWholeContentUpToTheLimit() {
        stored("hello".getBytes(StandardCharsets.UTF_8), "text/plain", "UTF-8");

        GetContentResponse response = fetcher.read(DOC, true, 0, 0);

        assertEquals("hello", response.getContent().getData().toStringUtf8());
        assertFalse(response.getHasMore());
        verify(contents, never()).getTempWriter();
    }

    @Test
    void pdfTextIsExtractedWithTheSynchronousTransform() {
        stored(new byte[]{'%', 'P', 'D', 'F'}, "application/pdf", "UTF-8");
        tempWriter("Capitolato tecnico");

        GetContentResponse response = fetcher.read(DOC, true, 0, 0);

        assertEquals("Capitolato tecnico", response.getContent().getData().toStringUtf8());
        assertEquals("text/plain", response.getContent().getMimetype());
        assertEquals("UTF-8", response.getContent().getEncoding());
        verify(transforms).transform(any(ContentReader.class), any(ContentWriter.class), anyMap(), isNull(), eq(DOC));
    }

    @Test
    void textSlicesNeverSplitAUtf8Character() {
        // "aè" = 61 C3 A8: a 2 byte slice would cut "è" in half.
        stored("aèb".getBytes(StandardCharsets.UTF_8), "text/plain", "UTF-8");

        GetContentResponse first = fetcher.read(DOC, true, 0, 2);
        assertEquals("a", first.getContent().getData().toStringUtf8());
        assertTrue(first.getHasMore());

        GetContentResponse next = fetcher.read(DOC, true, first.getContent().getData().size(), 2);
        assertEquals("è", next.getContent().getData().toStringUtf8());
        assertTrue(next.getHasMore());
    }

    @Test
    void nonUtf8TextIsReencoded() {
        stored("città".getBytes(StandardCharsets.ISO_8859_1), "text/plain", "ISO-8859-1");
        tempWriter(null);

        GetContentResponse response = fetcher.read(DOC, true, 0, 0);

        assertEquals("città", response.getContent().getData().toStringUtf8());
        verifyNoInteractions(transforms);
    }

    @Test
    void unsupportedTransformIsAFailedPrecondition() {
        stored(new byte[]{0}, "application/x-unknown", "UTF-8");
        tempWriter("");
        doThrow(new UnsupportedTransformationException("no")).when(transforms)
                .transform(any(), any(), anyMap(), isNull(), eq(DOC));

        var e = assertThrows(StatusRuntimeException.class, () -> fetcher.read(DOC, true, 0, 0));
        assertEquals(Status.Code.FAILED_PRECONDITION, e.getStatus().getCode());
        assertTrue(e.getStatus().getDescription().contains("application/x-unknown"));
    }

    @Test
    void failingTransformServiceIsUnavailable() {
        stored(new byte[]{0}, "application/pdf", "UTF-8");
        tempWriter("");
        doThrow(new ContentIOException("connection refused")).when(transforms)
                .transform(any(), any(), anyMap(), isNull(), eq(DOC));

        var e = assertThrows(StatusRuntimeException.class, () -> fetcher.read(DOC, true, 0, 0));
        assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
    }

    @Test
    void withoutTransformsTextExtractionIsUnavailable() {
        stored(new byte[]{0}, "application/pdf", "UTF-8");
        tempWriter("");

        var e = assertThrows(StatusRuntimeException.class,
                () -> new ContentFetcher(services, null).read(DOC, true, 0, 0));
        assertEquals(Status.Code.FAILED_PRECONDITION, e.getStatus().getCode());
    }

    @Test
    void documentWithoutContentIsEmpty() {
        when(contents.getReader(DOC, ContentModel.PROP_CONTENT)).thenReturn(null);

        GetContentResponse response = fetcher.read(DOC, true, 0, 0);

        assertTrue(response.getContent().getData().isEmpty());
        assertEquals("text/plain", response.getContent().getMimetype());
        assertEquals(0, response.getSize());
        assertFalse(response.getHasMore());
    }

    @Test
    void invalidRequestsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> fetcher.read(FOLDER, false, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> fetcher.read(DOC, false, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> fetcher.read(DOC, false, 0, ContentFetcher.MAX_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> fetcher.read(DOC, false, 0, -5));
        verifyNoInteractions(contents);
    }
}
