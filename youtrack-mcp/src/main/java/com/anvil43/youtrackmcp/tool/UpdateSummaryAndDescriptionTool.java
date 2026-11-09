package com.anvil43.youtrackmcp.tool;

import com.anvil43.youtrackmcp.YouTrackClient;
import com.anvil43.youtrackmcp.exception.RequestRejectedException;
import com.anvil43.youtrackmcp.exception.YouTrackException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.UpdatedText;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class UpdateSummaryAndDescriptionTool {

    private static final String UPDATE_SUMMARY_AND_DESCRIPTION_REJECTED_MESSAGE = "YouTrack rejected the change: %s";
    private static final String UPDATE_SUMMARY_AND_DESCRIPTION_YOUTRACK_UNAVAILABLE_MESSAGE = "YouTrack could not be reached; the issue may have been updated. Check it with get_issue before trying again.";

    private static final String INPUT_SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "issueId": {
                        "type": "string",
                        "description": "A YouTrack issue ID, for example \\"ABC-123\\"."
                    },
                    "summary": {
                        "type": "string",
                        "description": "The new one-line summary. Leave it out to keep the current one."
                    },
                    "description": {
                        "type": "string",
                        "description": "The new description, in Markdown; it replaces the whole old one. Line breaks are kept. Leave it out to keep the current one. Write only what the user told you or what the conversation shows; don't add details they didn't give."
                    }
                },
                "required": ["issueId"]
            }
        """;
    private static final String DESCRIPTION = """
        Changes a YouTrack issue's summary, description or both. Pass only what should change; anything left out stays as it is. The description replaces the old one completely, so to edit part of it, call get_issue first and send the full new text. Takes an issue ID (for example "ABC-123"). For fields such as state or assignee, use update_issue.""";

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateSummaryAndDescriptionTool.class);
    private static final String SUMMARY_BLANK = "summary can't be blank. Leave it out to keep the current summary.";
    private static final String SUMMARY_MULTI_LINE = "summary must be a single line; put details in description.";
    private static final String DESCRIPTION_BLANK = "description can't be blank. Leave it out to keep the current description.";
    private static final String NOTHING_TO_CHANGE = "Pass summary, description or both.";

    private final Tool tool = Tool.builder("update_summary_and_description", McpJsonDefaults.getMapper(), INPUT_SCHEMA)
            .description(DESCRIPTION).build();

    private final YouTrackClient youTrackClient;

    public UpdateSummaryAndDescriptionTool(YouTrackClient youTrackClient) {
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
            Object summaryValue = arguments.get("summary");
            String summary = null;
            if (null != summaryValue) {
                if (!(summaryValue instanceof String given) || given.isBlank()) {
                    return ToolResults.invalidArgument(SUMMARY_BLANK);
                }
                summary = given.strip();
                if (ToolResults.hasLineBreaks(summary)) {
                    return ToolResults.invalidArgument(SUMMARY_MULTI_LINE);
                }
            }
            Object descriptionValue = arguments.get("description");
            String description = null;
            if (null != descriptionValue) {
                if (!(descriptionValue instanceof String given) || given.isBlank()) {
                    return ToolResults.invalidArgument(DESCRIPTION_BLANK);
                }
                description = given;
            }
            if ((null == summary) && (null == description)) {
                return ToolResults.invalidArgument(NOTHING_TO_CHANGE);
            }
            UpdatedText updatedText = youTrackClient.updateSummaryAndDescription(issueId, summary, description);
            List<String> output = new ArrayList<>();
            output.add("Updated the " + changedParts(summary, description) + " of " + updatedText.issueId() + ".");
            output.add(updatedText.url());
            return ToolResults.text(String.join("\n", output));
        }
        catch (RequestRejectedException requestRejectedException) {
            return ToolResults.error(String.format(UPDATE_SUMMARY_AND_DESCRIPTION_REJECTED_MESSAGE,
                    ToolResults.appendPunctuation(requestRejectedException.detail())));
        }
        catch (YouTrackUnavailableException e) {
            return ToolResults.error(UPDATE_SUMMARY_AND_DESCRIPTION_YOUTRACK_UNAVAILABLE_MESSAGE);
        }
        catch (YouTrackException e) {
            return ToolResults.error(e);
        }
        catch (Exception e) {
            LOGGER.error("update_summary_and_description failed", e);
            return ToolResults.internalError();
        }
    }

    private static String changedParts(String summary, String description) {
        if ((null != summary) && (null != description)) {
            return "summary and description";
        }
        else if (null != summary) {
            return "summary";
        }
        else {
            return "description";
        }
    }

}
