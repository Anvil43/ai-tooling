package com.anvil43.youtrackmcp.tool;

import com.anvil43.youtrackmcp.exception.InvalidQueryException;
import com.anvil43.youtrackmcp.exception.IssueNotFoundException;
import com.anvil43.youtrackmcp.exception.RequestRejectedException;
import com.anvil43.youtrackmcp.exception.YouTrackAuthException;
import com.anvil43.youtrackmcp.exception.YouTrackException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class ToolResults {

    private static final String ISSUE_ID_MISSING = "issueId is required. Pass an issue ID, for example \"ABC-123\".";
    private static final String ISSUE_ID_MALFORMED = "\"%s\" is not an issue ID. Issue IDs look like \"ABC-123\"; use search_issues to find one.";
    private static final Pattern ISSUE_ID_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*-\\d+$");

    public static final String INTERNAL_ERROR = "Internal error in youtrack-mcp; see the server log.";

    private ToolResults() {
    }

    static CallToolResult text(String text) {
        return CallToolResult.builder().textContent(List.of(text)).build();
    }

    static CallToolResult error(YouTrackException e) {
        String text = switch (e) {
            case IssueNotFoundException n -> "Issue " + n.issueId() + " was not found. Check the ID, or use search_issues to find it.";
            case InvalidQueryException n -> "YouTrack rejected the query: " + appendPunctuation(n.detail()) + " Fix the query and try again.";
            case YouTrackAuthException n -> "YouTrack refused the request: the token is invalid or lacks access. Tell the user; retrying won't help.";
            case YouTrackUnavailableException n -> "YouTrack could not be reached. Tell the user; try again later.";
            case RequestRejectedException n -> "YouTrack rejected the request: " + appendPunctuation(n.detail());
        };
        return errorText(text);
    }

    static CallToolResult error(String errorText) {
        return errorText(errorText);
    }

    static CallToolResult internalError() {
        return errorText(INTERNAL_ERROR);
    }

    static CallToolResult invalidArgument(String errorMessage) {
        return errorText(errorMessage);
    }

    private static CallToolResult errorText(String text) {
        return CallToolResult.builder().textContent(List.of(text)).isError(true).build();
    }

    static boolean hasLineBreaks(String line) {
        return line.contains("\n") || line.contains("\r");
    }

    static String flattenLineBreaks(String line) {
        return  line.replace("\r\n", " ")
            .replace("\n", " ")
            .replace("\r", " ");
    }

    static String appendPunctuation(String text) {
        if (text.endsWith(".") || text.endsWith("!") || text.endsWith("?")) {
            return text;
        }
        return text.concat(".");
    }

    static Object validateIssueId(Map<String, Object> arguments) {
        Object issueIdValue = (null == arguments) ? null : arguments.get("issueId");
        if (!(issueIdValue instanceof String issueId) || (issueId.isBlank())) {
            return ToolResults.invalidArgument(ISSUE_ID_MISSING);
        }
        issueId = issueId.strip();
        if (!ISSUE_ID_PATTERN.matcher(issueId).matches()) {
            return ToolResults.invalidArgument(String.format(ISSUE_ID_MALFORMED, issueId));
        }
        return issueId;
    }

}
