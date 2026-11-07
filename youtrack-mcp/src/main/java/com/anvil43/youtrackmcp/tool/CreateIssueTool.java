package com.anvil43.youtrackmcp.tool;

import com.anvil43.youtrackmcp.YouTrackClient;
import com.anvil43.youtrackmcp.exception.RequestRejectedException;
import com.anvil43.youtrackmcp.exception.YouTrackException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.CreatedIssue;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class CreateIssueTool {

    private static final String CREATE_ISSUE_REJECTED_MESSAGE = "YouTrack rejected the issue: %s Check the project key with list_projects. If a field is required, tell the user; create_issue can't set fields.";
    private static final String YOUTRACK_UNAVAILABLE_MESSAGE = "YouTrack could not be reached; the issue may have been created. Search for it before trying again.";
    private static final String PROJECT_MISSING = "project is required. Pass a project key from list_projects, for example \"ABC\".";
    private static final String PROJECT_MALFORMED = "\"%s\" is not a project key. Project keys look like \"ABC\"; use list_projects to see them.";
    private static final Pattern PROJECT_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");
    private static final String SUMMARY_MISSING = "summary is required. Pass a one-line summary of the issue.";
    private static final String SUMMARY_MALFORMED = "summary must be a single line; put details in description.";

    private static final String INPUT_SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "project": {
                        "type": "string",
                        "description": "A project key from list_projects, for example \\"ABC\\"."
                    },
                    "summary": {
                        "type": "string",
                        "description": "A one-line summary of the issue."
                    },
                    "description": {
                        "type": "string",
                        "description": "Optional details, in Markdown. Line breaks are kept. Write only what the user told you or what the conversation shows; don't add steps, versions or causes they didn't give."
                    }
                },
                "required": ["project", "summary"]
            }
        """;
    private static final String DESCRIPTION = """
        Creates a YouTrack issue and returns its ID. Takes a project key from list_projects (for example "ABC"), a one-line summary and an optional description in Markdown. If the user hasn't said which project, don't pick one: call list_projects and ask them. Set fields such as state or assignee afterwards with update_issue.""";

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateIssueTool.class);

    private final Tool tool = Tool.builder("create_issue", McpJsonDefaults.getMapper(), INPUT_SCHEMA)
            .description(DESCRIPTION).build();

    private final YouTrackClient youTrackClient;

    public CreateIssueTool(YouTrackClient youTrackClient) {
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
            Object projectValue = (null == arguments) ? null : arguments.get("project");
            if (!(projectValue instanceof String project) || (project.isBlank())) {
                return ToolResults.invalidArgument(PROJECT_MISSING);
            }
            project = project.strip();
            if (!PROJECT_PATTERN.matcher(project).matches()) {
                return ToolResults.invalidArgument(String.format(PROJECT_MALFORMED, project));
            }
            Object summaryValue = arguments.get("summary");
            if (!(summaryValue instanceof String summary) || (summary.isBlank())) {
                return ToolResults.invalidArgument(SUMMARY_MISSING);
            }
            summary = summary.strip();
            if (ToolResults.hasLineBreaks(summary)) {
                return ToolResults.invalidArgument(SUMMARY_MALFORMED);
            }
            Object descriptionValue = arguments.get("description");
            CreatedIssue createdIssue;
            if (!(descriptionValue instanceof String description) || description.isBlank()) {
                createdIssue = youTrackClient.createIssue(project, summary, null);
            }
            else {
                createdIssue = youTrackClient.createIssue(project, summary, description);
            }
            List<String> output = new ArrayList<>();
            output.add("Created " + createdIssue.issueId() + ": " + createdIssue.summary());
            output.add(createdIssue.url());
            return ToolResults.text(String.join("\n", output));
        }
        catch (RequestRejectedException requestRejectedException) {
            return ToolResults.error(String.format(CREATE_ISSUE_REJECTED_MESSAGE,
                    ToolResults.appendPunctuation(requestRejectedException.detail())));
        }
        catch (YouTrackUnavailableException e) {
            return ToolResults.error(YOUTRACK_UNAVAILABLE_MESSAGE);
        }
        catch (YouTrackException e) {
            return ToolResults.error(e);
        }
        catch (Exception e) {
            LOGGER.error("create_issue failed", e);
            return ToolResults.internalError();
        }
    }

}
