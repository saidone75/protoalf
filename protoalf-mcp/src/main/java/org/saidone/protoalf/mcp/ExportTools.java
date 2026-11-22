package org.saidone.protoalf.mcp;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.saidone.protoalf.client.BulkExporter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Exports of repository folders in the Bulk Filesystem Import layout, as MCP tools. An export can take long, more
 * than an MCP call may wait: it runs in the background, one at a time, and the model follows it by id.
 */
@Slf4j
public class ExportTools implements AutoCloseable {

    private static final Pattern DIRECTORY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final BulkExporter exporter;
    private final Path directory;
    private final Map<String, Export> exports = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        val thread = new Thread(task, "alfresco-export");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param directory where the exports are written, one subdirectory each
     */
    public ExportTools(BulkExporter exporter, Path directory) {
        this.exporter = exporter;
        this.directory = directory.toAbsolutePath().normalize();
    }

    @Tool(name = "alfresco_export_bulk", description = """
        Exports the documents of a folder, with their metadata and all their versions, into a new \
        directory on the MCP server, in the layout of the Alfresco Bulk Filesystem Import Tool: \
        <name>.metadata.properties.xml shadow files with type, aspects and properties, previous \
        versions as <name>.v1, <name>.v2, ... oldest first. Runs in the background and returns an export id: call \
        alfresco_export_status with it until the status is completed or failed.""")
    public String export(@ToolParam(description = """
        Folder to export: a nodeRef (workspace://SpacesStore/<id>), a bare node id, \
        or one of the aliases -root- (Company Home), -my- (user home folder), -shared- (Shared Files)""") String folderRef, @ToolParam(description = """
        Name of the directory to create for the export, e.g. invoices-2024: letters, digits, \
        '.', '-' and '_'. It must not exist yet, or be empty""") String name, @ToolParam(description = "true (default) to export subfolders and their content too, false for the documents of the folder only", required = false) Boolean recursive) {
        if (name == null || !DIRECTORY_NAME.matcher(name).matches())
            throw new AlfrescoTools.AlfrescoToolException("Invalid directory name '%s': use letters, digits, '.', '-' and '_'".formatted(name), null);
        val target = directory.resolve(name);
        if (exports.values().stream().anyMatch(export -> export.target.equals(target) && !export.progress.finished()))
            throw new AlfrescoTools.AlfrescoToolException("An export into %s is already running".formatted(name), null);
        if (!isEmpty(target))
            throw new AlfrescoTools.AlfrescoToolException("Directory %s already exists and is not empty".formatted(name), null);
        val export = new Export(UUID.randomUUID().toString(), folderRef, target, Instant.now(), new BulkExporter.Progress());
        exports.put(export.id, export);
        executor.submit(() -> run(export, !Boolean.FALSE.equals(recursive)));
        return status(export);
    }

    @Tool(name = "alfresco_export_status", description = """
        Status of an export started with alfresco_export_bulk: running, completed or failed, with the \
        number of folders, documents and versions written, the bytes and the first errors.""")
    public String status(@ToolParam(description = "Export id returned by alfresco_export_bulk") String exportId) {
        val export = exports.get(exportId);
        if (export == null)
            throw new AlfrescoTools.AlfrescoToolException("No export with id %s".formatted(exportId), null);
        return status(export);
    }

    private void run(Export export, boolean recursive) {
        try {
            exporter.export(export.folderRef, export.target, recursive, export.progress);
        } catch (IOException | RuntimeException e) {
            // Already recorded in the progress as the failure.
            log.warn("Export {} of {} failed", export.id, export.folderRef, e);
        }
    }

    private String status(Export export) {
        val progress = export.progress;
        val state = !progress.finished() ? "running" : progress.failure() != null ? "failed" : "completed";
        return JSON.writeValueAsString(new Status(export.id, state, export.folderRef, export.target.toString(), export.started.toString(), progress.folders(), progress.documents(), progress.versions(), progress.bytes(), progress.errorCount(), progress.errors(), progress.failure()));
    }

    private static boolean isEmpty(Path target) {
        if (!Files.exists(target))
            return true;
        try (val entries = Files.list(target)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    @Override public void close() {
        executor.shutdownNow();
    }

    private record Export(String id, String folderRef, Path target, Instant started, BulkExporter.Progress progress) {
    }

    /**
     * Status of an export, as returned to the model.
     */
    record Status(String exportId, String status, String folder, String directory, String started, long folders, long documents, long versions, long bytes, long errorCount, List<String> errors, String failure) {
    }
}
