package com.anvil43.youtrackmcp.tool;

import com.anvil43.youtrackmcp.YouTrackClient;
import com.anvil43.youtrackmcp.exception.YouTrackException;
import com.anvil43.youtrackmcp.model.CustomField;
import com.anvil43.youtrackmcp.model.Issue;
import com.anvil43.youtrackmcp.model.IssueComment;
import com.anvil43.youtrackmcp.model.User;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class GetIssueTool {

    private static final String ISSUE_COMMENT_USER_LINE = "%s (%s), %s:";

    private static final String INPUT_SCHEMA = """
        {
            "type": "object",
            "properties": {
                "issueId": {
                    "type": "string",
                    "description": "A YouTrack issue ID, for example \\"ABC-123\\"."
                }
            },
            "required": ["issueId"]
        }
        """;
    private static final String DESCRIPTION = """
        Returns one YouTrack issue by ID (for example "ABC-123"): its dates, every custom field, the description and all comments. Use search_issues to find IDs.""";

    private static final Logger LOGGER = LoggerFactory.getLogger(GetIssueTool.class);

    private final Tool tool = Tool.builder("get_issue", McpJsonDefaults.getMapper(), INPUT_SCHEMA)
            .description(DESCRIPTION).build();

    private final YouTrackClient youTrackClient;

    public GetIssueTool(YouTrackClient youTrackClient) {
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
            Object validationResult = ToolResults.validateIssueId(arguments);
            if (validationResult instanceof CallToolResult callToolResult) {
                return callToolResult;
            }
            String issueId = (String) validationResult;
            Issue issue = youTrackClient.getIssue(issueId);
            List<String> output = new ArrayList<>();
            output.add(ToolResults.flattenLineBreaks(issue.issueId() + ": "
                    + ((null == issue.summary()) ? "" : issue.summary())));
            handleIssueTimestamps(issue, output);
            handleCustomFields(issue, output);
            output.add("");
            handleIssueDescription(issue, output);
            output.add("");
            handleIssueComments(issue, output);
            return ToolResults.text(String.join("\n", output));
        }
        catch (YouTrackException e) {
            return ToolResults.error(e);
        }
        catch (Exception e) {
            LOGGER.error("get_issue failed", e);
            return ToolResults.internalError();
        }
    }

    private void handleIssueTimestamps(Issue issue, List<String> output) {
        output.add("Created: " + printInstantWithoutMillis(issue.created()));
        output.add("Updated: " + printInstantWithoutMillis(issue.updated()));
        if (null != issue.resolved()) {
            output.add("Resolved: " + printInstantWithoutMillis(issue.resolved()));
        }
    }

    private void handleIssueDescription(Issue issue, List<String> output) {
        if (null != issue.description() && !issue.description().isBlank()) {
            output.add("Description:");
            output.add(issue.description());
        }
        else {
            output.add("Description: none.");
        }
    }

    private void handleCustomFields(Issue issue, List<String> output) {
        List<String> unsetFields = new ArrayList<>();
        for (CustomField customField : issue.customFields()){
            if (null == customField.value() || customField.value().isBlank()) {
                unsetFields.add(customField.name());
            }
            else {
                output.add(ToolResults.flattenLineBreaks(customField.name() + ": " + customField.value()));
            }
        }
        if (!unsetFields.isEmpty()) {
            output.add(ToolResults.flattenLineBreaks("Unset: " + String.join(", ", unsetFields)));
        }
    }

    private void handleIssueComments(Issue issue, List<String> output) {
        if (!issue.issueComments().isEmpty()) {
            output.add("Comments (" + issue.issueComments().size() + "):");
            boolean isFirst = true;
            for (IssueComment comment : issue.issueComments()) {
                if (!isFirst) {
                    output.add("");
                }
                handleComment(comment, output);
                isFirst = false;
            }
        }
        else {
            output.add("Comments: none.");
        }
    }

    private void handleComment(IssueComment issueComment, List<String> output) {
        User user = issueComment.user();
        output.add(ToolResults.flattenLineBreaks(String.format(ISSUE_COMMENT_USER_LINE,
            user.fullName(), user.login(),
            printInstantWithoutMillis(issueComment.created()))));
        String text = issueComment.text();
        output.add(((null == text) || text.isEmpty()) ? "(empty)" : text);
    }

    private String printInstantWithoutMillis(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

}
