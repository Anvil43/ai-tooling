package com.anvil43.youtrackmcp.tool;

import com.anvil43.youtrackmcp.YouTrackClient;
import com.anvil43.youtrackmcp.exception.RequestRejectedException;
import com.anvil43.youtrackmcp.exception.YouTrackException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.UpdatedIssue;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class UpdateIssueTool {

    private static final String UPDATE_ISSUE_REJECTED_MESSAGE = "YouTrack rejected the command: %s Nothing was changed. Check the issue ID, and the field names and values with get_issue.";
    private static final String YOUTRACK_UNAVAILABLE_MESSAGE = "YouTrack could not be reached; the issue may have been updated. Check it with get_issue before trying again.";
    private static final String COMMAND_MISSING = "command is required. Pass a YouTrack command, for example \"State In Progress\".";

    private static final String INPUT_SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "issueId": {
                        "type": "string",
                        "description": "A YouTrack issue ID, for example \\"ABC-123\\"."
                    },
                    "command": {
                        "type": "string",
                        "description": "A YouTrack command, for example \\"State In Progress Assignee me\\". Use field names and values from get_issue."
                    }
                },
                "required": ["issueId", "command"]
            }
        """;
    private static final String DESCRIPTION = """
        Changes a YouTrack issue by applying a command, the same text as in YouTrack's command dialog: for example "State In Progress", "Assignee me", "Priority Major", "tag Release" or "subtask of ABC-1". One command can set several fields. Use the field names and values that get_issue shows; if you're not sure a value exists, ask the user rather than guessing. Use search_issues to find IDs. To change the summary or description, use update_summary_and_description.""";

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateIssueTool.class);

    private final Tool tool = Tool.builder("update_issue", McpJsonDefaults.getMapper(), INPUT_SCHEMA)
            .description(DESCRIPTION).build();

    private final YouTrackClient youTrackClient;

    public UpdateIssueTool(YouTrackClient youTrackClient) {
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
            Object commandValue = arguments.get("command");
            if (!(commandValue instanceof String command) || (command.isBlank())) {
                return ToolResults.invalidArgument(COMMAND_MISSING);
            }
            command = command.strip();
            UpdatedIssue updatedIssue = youTrackClient.updateIssue(issueId, command);
            List<String> output = new ArrayList<>();
            if (updatedIssue.changes().isEmpty()) {
                output.add("No changes were made to " + updatedIssue.issueId() + ".");
            }
            else {
                output.add("Updated " + updatedIssue.issueId() + ":");
                updatedIssue.changes().forEach(change -> output.add("- " + change));
            }
            output.add(updatedIssue.url());
            return ToolResults.text(String.join("\n", output));
        }
        catch (RequestRejectedException requestRejectedException) {
            return ToolResults.error(String.format(UPDATE_ISSUE_REJECTED_MESSAGE,
                    ToolResults.appendPunctuation(requestRejectedException.detail())));
        }
        catch (YouTrackUnavailableException e) {
            return ToolResults.error(YOUTRACK_UNAVAILABLE_MESSAGE);
        }
        catch (YouTrackException e) {
            return ToolResults.error(e);
        }
        catch (Exception e) {
            LOGGER.error("update_issue failed", e);
            return ToolResults.internalError();
        }
    }

}
