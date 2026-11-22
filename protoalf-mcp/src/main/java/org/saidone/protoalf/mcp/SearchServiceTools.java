package org.saidone.protoalf.mcp;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.saidone.protoalf.api.SortField;
import org.saidone.protoalf.client.SearchServiceClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.saidone.protoalf.mcp.ToolSupport.*;

/** MCP tools backed by SearchServiceClient. */
@RequiredArgsConstructor
public class SearchServiceTools {

    private final SearchServiceClient search;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Tool(name = "alfresco_search", description = """
        Searches the Alfresco repository with an AFTS (Alfresco Full Text Search) query and returns the \
        matching documents and folders with their metadata. Examples: \
        'budget' (name, title, description and content), \
        'cm:name:"report*"' (name starting with report), \
        'TEXT:"invoice 2024"' (content), \
        'TYPE:"cm:folder" AND cm:name:"projects"', \
        'TYPE:"cm:content" AND cm:modified:[NOW-7DAYS TO NOW]', \
        'PATH:"/app:company_home/cm:Projects//*"' (everything below a folder), \
        'cm:creator:"jdoe"'. \
        Combine clauses with AND, OR, NOT and parentheses. \
        Results come from the search index, which may lag a few seconds behind recent changes. \
        'totalItems' is the number of matches; check 'hasMoreItems' and increase skipCount for the next page. \
        Results are sorted by relevance unless 'sort' is given.""")
    public String search(@ToolParam(description = "AFTS query, e.g. cm:name:\"budget*\" AND TYPE:\"cm:content\"") String query, @ToolParam(description = "Number of results to skip (paging offset), 0 for the first page", required = false) Integer skipCount, @ToolParam(description = "Page size, 1..1000 (default 25)", required = false) Integer maxItems, @ToolParam(description = """
        Sort order: comma-separated 'field [asc|desc]', applied in order (ascending by default). \
        field is a property (e.g. cm:name, cm:modified, cm:created) or score (relevance). \
        Example: 'cm:modified desc, cm:name'""", required = false) String sort) {
        try {
            return json(search.query(query, "afts", skipCount == null ? 0 : skipCount, maxItems == null ? 25 : maxItems, sort(sort)));
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

    /**
     * Parses the sort parameter of alfresco_search: "field [asc|desc], ..."; empty when not given.
     */
    static List<SortField> sort(String value) {
        if (value == null || value.isBlank())
            return List.of();
        val result = new ArrayList<SortField>();
        for (val criterion : value.split(",", -1)) {
            val parts = WHITESPACE.split(criterion.strip());
            val valid = !parts[0].isEmpty() && parts.length <= 2 && (parts.length == 1 || parts[1].equalsIgnoreCase("asc") || parts[1].equalsIgnoreCase("desc"));
            if (!valid)
                throw new AlfrescoToolException("Invalid sort '%s': use 'field [asc|desc]', comma-separated".formatted(criterion.strip()));
            result.add(SortField.newBuilder().setField(parts[0]).setDescending(parts.length == 2 && parts[1].equalsIgnoreCase("desc")).build());
        }
        return result;
    }

}
