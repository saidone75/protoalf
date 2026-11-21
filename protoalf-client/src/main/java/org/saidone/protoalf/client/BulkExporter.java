package org.saidone.protoalf.client;

import lombok.val;
import org.saidone.protoalf.api.Node;
import org.saidone.protoalf.api.Property;
import org.saidone.protoalf.api.PropertyValue;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Exports the content of a repository folder in the layout read by the Alfresco Bulk Filesystem Import Tool:
 * <ul>
 *   <li>one file or directory per document or folder, named as in Alfresco;</li>
 *   <li>next to each one a {@code <name>.metadata.properties.xml} shadow file (Java XML properties) with
 *       {@code type}, {@code aspects} and the properties, names in prefix:local form;</li>
 *   <li>the previous versions of a document as {@code <name>.v1}, {@code <name>.v2}, ... oldest first, with their
 *       shadow files {@code <name>.metadata.properties.xml.v1}, ...: the file without suffix is the current
 *       version. Numbers are integers, as the importer of Alfresco requires: it assigns new version labels.</li>
 * </ul>
 * System properties, content, locks and working copy data are left out, and so are node references (ids would not
 * exist in the target repository). Permissions and associations are not part of the format.
 * <p>
 * Failures of single nodes are recorded in the {@link Progress} and do not stop the export.
 */
public class BulkExporter {

    public static final String METADATA_SUFFIX = ".metadata.properties.xml";
    /** Separator of the values of a multi-valued property, the default of the Bulk Import Tool. */
    public static final String MULTI_VALUE_SEPARATOR = ",";

    private static final String CM = "http://www.alfresco.org/model/content/1.0";
    private static final String SYS = "http://www.alfresco.org/model/system/1.0";
    private static final String VERSIONABLE = qname(CM, "versionable");
    private static final String VERSION_LABEL = qname(CM, "versionLabel");
    private static final Set<String> EXCLUDED_PROPERTIES = Set.of(
            qname(CM, "name"), qname(CM, "content"), VERSION_LABEL, qname(CM, "versionType"),
            qname(CM, "initialVersion"), qname(CM, "lockOwner"), qname(CM, "lockType"), qname(CM, "lockLifetime"),
            qname(CM, "expiryDate"), qname(CM, "lockAdditionalInfo"), qname(CM, "workingCopyOwner"),
            qname(CM, "workingCopyMode"), qname(CM, "workingCopyLabel"), qname(CM, "lastThumbnailModification"));
    private static final Set<String> EXCLUDED_ASPECTS = Set.of(
            qname(CM, "lockable"), qname(CM, "workingcopy"), qname(CM, "checkedOut"),
            qname(CM, "thumbnailModification"), qname("http://www.alfresco.org/model/rendition/1.0", "renditioned"));
    private static final int PAGE_SIZE = 100;
    private static final int MAX_TYPE_DEPTH = 100;

    private final NodeServiceClient client;

    public BulkExporter(NodeServiceClient client) {
        this.client = client;
    }

    /**
     * Exports the children of {@code folderRef} into {@code target}, which is created if missing and must be empty.
     *
     * @param recursive true to export subfolders and their content too, false for the documents of the folder only
     * @param progress  updated while the export runs: another thread can read it
     * @return {@code progress}, completed
     * @throws IllegalArgumentException if {@code folderRef} is not a folder or {@code target} is not empty
     */
    public Progress export(String folderRef, Path target, boolean recursive, Progress progress) throws IOException {
        try {
            val dictionary = Dictionary.load(client);
            val folder = client.getNode(folderRef);
            if (!dictionary.isFolder(folder.getType()))
                throw new IllegalArgumentException("%s is not a folder".formatted(folderRef));
            Files.createDirectories(target);
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent())
                    throw new IllegalArgumentException("%s is not empty: export into a new or empty directory".formatted(target));
            }
            new Run(dictionary, recursive, progress).children(folder, target);
            return progress;
        } catch (IOException | RuntimeException e) {
            progress.fail(e);
            throw e;
        } finally {
            progress.finish();
        }
    }

    private final class Run {
        private final Dictionary dictionary;
        private final boolean recursive;
        private final Progress progress;

        Run(Dictionary dictionary, boolean recursive, Progress progress) {
            this.dictionary = dictionary;
            this.recursive = recursive;
            this.progress = progress;
        }

        void children(Node folder, Path directory) {
            var skip = 0;
            while (true) {
                val page = client.listChildren(folder.getNodeRef(), skip, PAGE_SIZE);
                for (val child : page.getEntriesList()) node(child, directory);
                skip += page.getEntriesCount();
                if (!page.getHasMoreItems() || page.getEntriesCount() == 0) return;
            }
        }

        private void node(Node node, Path directory) {
            try {
                val path = child(directory, node.getName());
                if (dictionary.isFolder(node.getType())) {
                    if (!recursive) return;
                    Files.createDirectory(path);
                    writeMetadata(node, shadow(path));
                    progress.folders.incrementAndGet();
                    children(node, path);
                } else if (dictionary.isContent(node.getType())) {
                    document(node, path);
                } else {
                    progress.error(node, "skipped: %s is neither a folder nor a document".formatted(dictionary.prefixed(node.getType())));
                }
            } catch (IOException | RuntimeException e) {
                progress.error(node, describe(e));
            }
        }

        private void document(Node node, Path path) throws IOException {
            if (node.getAspectsList().contains(VERSIONABLE)) {
                val current = text(node.getPropertiesMap().get(VERSION_LABEL));
                // Newest first from Alfresco: the importer orders versions by number, so they are numbered oldest first.
                val previous = new ArrayList<>(client.listVersions(node.getNodeRef()).getVersionsList());
                Collections.reverse(previous);
                // The file without suffix is the current version: the Bulk Import Tool imports it last.
                previous.removeIf(version -> version.getLabel().equals(current));
                for (var number = 1; number <= previous.size(); number++) {
                    val version = previous.get(number - 1);
                    download(version.getVersionRef(), versioned(path, number));
                    writeMetadata(version.getNode(), versioned(shadow(path), number));
                    progress.versions.incrementAndGet();
                }
            }
            download(node.getNodeRef(), path);
            writeMetadata(node, shadow(path));
            progress.documents.incrementAndGet();
        }

        private void download(String nodeRef, Path file) throws IOException {
            try (var out = Files.newOutputStream(file)) {
                client.downloadContent(nodeRef, out);
            }
            progress.bytes.addAndGet(Files.size(file));
        }

        private void writeMetadata(Node node, Path file) throws IOException {
            val entries = new TreeMap<String, String>();
            entries.put("type", dictionary.prefixed(node.getType()));
            val aspects = node.getAspectsList().stream()
                    .filter(aspect -> !aspect.startsWith(qname(SYS, "")) && !EXCLUDED_ASPECTS.contains(aspect))
                    .map(dictionary::prefixed).sorted().collect(Collectors.joining(MULTI_VALUE_SEPARATOR));
            if (!aspects.isEmpty()) entries.put("aspects", aspects);
            node.getPropertiesMap().forEach((name, property) -> {
                if (name.startsWith(qname(SYS, "")) || EXCLUDED_PROPERTIES.contains(name)) return;
                val value = value(property);
                if (value != null) entries.put(dictionary.prefixed(name), value);
            });
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
                        <properties>
                        """);
                for (val entry : entries.entrySet())
                    out.write("  <entry key=\"%s\">%s</entry>\n".formatted(escape(entry.getKey()), escape(entry.getValue())));
                out.write("</properties>\n");
            }
        }

        /** The values of a property as text, null when none can be exported (node references, content, nulls). */
        private String value(Property property) {
            val values = new ArrayList<String>();
            for (val value : property.getValuesList()) {
                val text = text(value);
                if (text != null) values.add(text);
            }
            return values.isEmpty() ? null : String.join(MULTI_VALUE_SEPARATOR, values);
        }

        private String text(PropertyValue value) {
            return switch (value.getValueCase()) {
                case STRING_VALUE -> value.getStringValue();
                case INTEGER_VALUE -> Long.toString(value.getIntegerValue());
                case DOUBLE_VALUE -> Double.toString(value.getDoubleValue());
                case BOOLEAN_VALUE -> Boolean.toString(value.getBooleanValue());
                case TIMESTAMP_MILLIS -> Instant.ofEpochMilli(value.getTimestampMillis()).toString();
                case QNAME_VALUE -> dictionary.prefixed(value.getQnameValue());
                default -> null;
            };
        }

        private String text(Property property) {
            return property == null || property.getValuesCount() == 0 ? null : text(property.getValues(0));
        }
    }

    /** A child path named as the node: names that would leave the directory are refused. */
    private static Path child(Path directory, String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\"))
            throw new IllegalArgumentException("unsupported name '%s'".formatted(name));
        return directory.resolve(name);
    }

    /** The message of a failure; for an I/O failure also its kind, since its message is often just the path. */
    static String describe(Exception e) {
        val message = Objects.toString(e.getMessage(), "");
        if (e instanceof IOException || message.isEmpty()) return "%s: %s".formatted(e.getClass().getSimpleName(), message);
        return message;
    }

    private static Path shadow(Path path) {
        return path.resolveSibling("%s%s".formatted(path.getFileName(), METADATA_SUFFIX));
    }

    /**
     * A version of a content or shadow file: {@code .v<number>} last, an integer, as the Alfresco importer expects
     * ({@code \.v([0-9]+)\z}): {@code a.pdf.v1} and {@code a.pdf.metadata.properties.xml.v1}.
     */
    private static Path versioned(Path path, int number) {
        return path.resolveSibling("%s.v%d".formatted(path.getFileName(), number));
    }

    private static String qname(String uri, String local) {
        return "{%s}%s".formatted(uri, local);
    }

    private static String escape(String value) {
        val result = new StringBuilder(value.length());
        for (val c : value.toCharArray()) {
            switch (c) {
                case '&' -> result.append("&amp;");
                case '<' -> result.append("&lt;");
                case '>' -> result.append("&gt;");
                case '"' -> result.append("&quot;");
                default -> result.append(c);
            }
        }
        return result.toString();
    }

    /** Namespace prefixes and type hierarchy of the repository, from ListModels. */
    private static final class Dictionary {
        private final Map<String, String> prefixes = new HashMap<>();
        private final Map<String, String> parents = new HashMap<>();

        static Dictionary load(NodeServiceClient client) {
            val dictionary = new Dictionary();
            for (val model : client.listModels("", false).getModelsList()) {
                for (val namespace : model.getNamespacesList()) dictionary.prefixes.put(namespace.getUri(), namespace.getPrefix());
                for (val type : model.getTypesList()) dictionary.parents.put(type.getName(), type.getParent());
            }
            return dictionary;
        }

        /** {uri}local as prefix:local; unchanged when the namespace has no prefix. */
        String prefixed(String qname) {
            val end = qname.indexOf('}');
            if (!qname.startsWith("{") || end < 0) return qname;
            val prefix = prefixes.get(qname.substring(1, end));
            return prefix == null ? qname : "%s:%s".formatted(prefix, qname.substring(end + 1));
        }

        boolean isFolder(String type) {
            return isA(type, "cm:folder");
        }

        boolean isContent(String type) {
            return isA(type, "cm:content");
        }

        private boolean isA(String type, String ancestor) {
            var current = prefixed(type);
            for (var depth = 0; current != null && !current.isEmpty() && depth < MAX_TYPE_DEPTH; depth++) {
                if (current.equals(ancestor)) return true;
                current = parents.get(current);
            }
            return false;
        }
    }

    /** Counters and errors of an export. Thread-safe: an export runs in one thread, others can read it. */
    public static final class Progress {
        private static final int MAX_ERRORS = 100;
        private final AtomicLong folders = new AtomicLong();
        private final AtomicLong documents = new AtomicLong();
        private final AtomicLong versions = new AtomicLong();
        private final AtomicLong bytes = new AtomicLong();
        private final AtomicLong errorCount = new AtomicLong();
        private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean finished;
        private volatile String failure;

        public long folders() { return folders.get(); }
        public long documents() { return documents.get(); }
        public long versions() { return versions.get(); }
        public long bytes() { return bytes.get(); }
        public long errorCount() { return errorCount.get(); }
        /** The first errors, at most 100: "name (nodeRef): message". */
        public List<String> errors() { synchronized (errors) { return List.copyOf(errors); } }
        public boolean finished() { return finished; }
        /** Why the whole export failed, null if it did not. */
        public String failure() { return failure; }

        void error(Node node, String message) {
            errorCount.incrementAndGet();
            synchronized (errors) {
                if (errors.size() < MAX_ERRORS) errors.add("%s (%s): %s".formatted(node.getName(), node.getNodeRef(), message));
            }
        }

        void fail(Exception e) { failure = describe(e); }

        void finish() { finished = true; }
    }
}
