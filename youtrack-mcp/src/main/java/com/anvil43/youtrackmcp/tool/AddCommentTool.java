package com.anvil43.youtrackmcp.tool;

import com.anvil43.youtrackmcp.YouTrackClient;
import com.anvil43.youtrackmcp.exception.RequestRejectedException;
import com.anvil43.youtrackmcp.exception.YouTrackException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.AddedComment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AddCommentTool {

    private static final String COMMENT_MISSING = "comment is required. Pass the comment text.";
    private static final String YOUTRACK_UNAVAILABLE_MESSAGE = "YouTrack could not be reached; the comment may have been added. Check the issue with get_issue before trying again.";
    private static final String ADD_COMMENT_REJECTED_MESSAGE = "YouTrack rejected the comment: %s";

    private static final String INPUT_SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "issueId": {
                        "type": "string",
                        "description": "A YouTrack issue ID, for example \\"ABC-123\\"."
                    },
                    "comment": {
                        "type": "string",
                        "description": "The comment, in Markdown. Line breaks are kept. Write only what the user told you or what the conversation shows; don't add details they didn't give."
                    }
                },
                "required": ["issueId", "comment"]
            }
        """;
    private static final String DESCRIPTION = """
        Adds a comment to a YouTrack issue. Takes an issue ID (for example "ABC-123") and the comment in Markdown. Use search_issues to find IDs.""";

    private static final Logger LOGGER = LoggerFactory.getLogger(AddCommentTool.class);

    private final Tool tool = Tool.builder("add_comment", McpJsonDefaults.getMapper(), INPUT_SCHEMA)
            .description(DESCRIPTION).build();

    private final YouTrackClient youTrackClient;

    public AddCommentTool(YouTrackClient youTrackClient) {
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
            Object commentValue = arguments.get("comment");
            if (!(commentValue instanceof String comment) || (comment.isBlank())) {
                return ToolResults.invalidArgument(COMMENT_MISSING);
            }
            AddedComment addedComment = youTrackClient.addComment(issueId, comment);
            List<String> output = new ArrayList<>();
            output.add("Added a comment to " + addedComment.issueId() + ".");
            output.add(addedComment.url());
            return ToolResults.text(String.join("\n", output));
        }
        catch (RequestRejectedException requestRejectedException) {
            return ToolResults.error(String.format(ADD_COMMENT_REJECTED_MESSAGE,
                    ToolResults.appendPunctuation(requestRejectedException.detail())));
        }
        catch (YouTrackUnavailableException e) {
            return ToolResults.error(YOUTRACK_UNAVAILABLE_MESSAGE);
        }
        catch (YouTrackException e) {
            return ToolResults.error(e);
        }
        catch (Exception e) {
            LOGGER.error("add_comment failed", e);
            return ToolResults.internalError();
        }
    }

}
