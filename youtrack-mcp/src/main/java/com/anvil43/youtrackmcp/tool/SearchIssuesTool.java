package com.anvil43.youtrackmcp.tool;

import com.anvil43.youtrackmcp.YouTrackClient;
import com.anvil43.youtrackmcp.exception.YouTrackException;
import com.anvil43.youtrackmcp.model.CustomField;
import com.anvil43.youtrackmcp.model.IssueSummary;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SearchIssuesTool {

    private static final int MAX_RESULTS = 50;
    private static final String QUERY_REQUIRED = "query is required. Pass a YouTrack search query, for example \"project: <KEY> #Unresolved\", using a project key from list_projects.";

    private static final String INPUT_SCHEMA = """
        {
            "type" : "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "A YouTrack search query, for example \\"project: <KEY> #Unresolved\\", using a project key from list_projects."
                }
            },
            "required": ["query"]
        }
        """;

    private static final String DESCRIPTION = """
        Searches YouTrack issues with a YouTrack search query, for example "project: <KEY> #Unresolved" or "project: <KEY> assignee: me". Queries usually start with project: <KEY>. If the user hasn't named a project and context doesn't say, ask them rather than guessing; use list_projects to show the options. Custom field names and values differ between projects. If you don't know a project's fields yet, run a broad query such as "project: <KEY>" first and take names and values from the results rather than guessing. Returns up to 50 issues, one per line, in YouTrack's default order; add a sort by: clause to choose another, for example "project: <KEY> sort by: created asc". Use get_issue for an issue's description and comments.""";

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchIssuesTool.class);

    private final Tool tool = Tool.builder("search_issues", McpJsonDefaults.getMapper(), INPUT_SCHEMA)
            .description(DESCRIPTION).build();

    private final YouTrackClient youTrackClient;

    public SearchIssuesTool(YouTrackClient youTrackClient) {
        this.youTrackClient = youTrackClient;
    }

    public SyncToolSpecification specification() {
        return SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> handle(request.arguments()))
            .build();
    }

    CallToolResult handle(Map<String, Object> arguments) {
        try {
            Object value = (null == arguments) ? null : arguments.get("query");
            if (!(value instanceof String query) || (query.isBlank())) {
                return ToolResults.invalidArgument(QUERY_REQUIRED);
            }
            List<IssueSummary> issueSummaries = youTrackClient.searchIssues(query.strip(), MAX_RESULTS + 1);
            boolean overflow = issueSummaries.size() > MAX_RESULTS;
            List<String> output = new ArrayList<>();
            output.add(generateHeaderLine(issueSummaries, overflow));
            appendIssueSummaryRows(overflow ? issueSummaries.subList(0, MAX_RESULTS) : issueSummaries, output);
            appendListTruncatedLine(overflow, output);
            return ToolResults.text(String.join("\n", output));
        }
        catch (YouTrackException e) {
            return ToolResults.error(e);
        }
        catch (Exception e) {
            LOGGER.error("search_issues failed", e);
            return ToolResults.internalError();
        }
    }

    private String generateHeaderLine(List<IssueSummary> issueSummaries, boolean overflow) {
        if (issueSummaries.isEmpty()) {
            return "0 issues matched. If the query names fields or values, check their spelling.";
        }
        else if (1 == issueSummaries.size()) {
            return "1 issue (fields without a value are left out):";
        }
        else if (overflow) {
            return MAX_RESULTS + " issues (fields without a value are left out):";
        }
        else {
            return issueSummaries.size() + " issues (fields without a value are left out):";
        }
    }

    private void appendIssueSummaryRows(List<IssueSummary> issueSummaries, List<String> output) {
        issueSummaries.forEach(issueSummary -> output.add(generateIssueListLine(issueSummary)));
    }

    private void appendListTruncatedLine(boolean overflow, List<String> output) {
        if (overflow) {
            output.add("Showing the first " + MAX_RESULTS + " matching issues. Refine the query to narrow the results.");
        }
    }

    private String generateIssueListLine(IssueSummary issueSummary) {
        List<String> issueFields = new ArrayList<>();
        issueFields.add(ToolResults.flattenLineBreaks(null == issueSummary.summary() ? "" : issueSummary.summary()));
        issueFields.addAll(assembleFieldList(issueSummary.customFields()));
        return issueSummary.issueId() + ": " +  String.join(" | ", issueFields);
    }

    private List<String> assembleFieldList(List<CustomField> customFields) {
        List<String> fieldList = new ArrayList<>();
        for (CustomField customField : customFields) {
            if ((null != customField.value()) && (!customField.value().isBlank())) {
                fieldList.add(ToolResults.flattenLineBreaks(customField.name() + ": " + customField.value()));
            }
        }
        return fieldList;
    }

}
