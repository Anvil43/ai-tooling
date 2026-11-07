package com.anvil43.youtrackmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.anvil43.youtrackmcp.FakeYouTrackClient;
import com.anvil43.youtrackmcp.exception.IssueNotFoundException;
import com.anvil43.youtrackmcp.exception.RequestRejectedException;
import com.anvil43.youtrackmcp.exception.YouTrackAuthException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.AddedComment;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Contract: design.md, "Write tools: add_comment contract" and "Errors". */
class AddCommentToolTest {

    private static final String ISSUE_ID_REQUIRED = "issueId is required. Pass an issue ID, for example \"ABC-123\".";
    private static final String COMMENT_REQUIRED = "comment is required. Pass the comment text.";

    private static final AddedComment ADDED =
            new AddedComment("DEMO-13", "https://youtrack.example.com/issue/DEMO-13");

    private final FakeYouTrackClient client = new FakeYouTrackClient().withAddedComment(ADDED);
    private final AddCommentTool tool = new AddCommentTool(client);

    // --- specification -------------------------------------------------------------------

    @Test
    void isNamedAddComment() {
        assertThat(tool.specification().tool().name()).isEqualTo("add_comment");
    }

    @Test
    void descriptionSaysWhatItTakesAndWhereIdsComeFrom() {
        // No visibility sentence (Aaron, 2026-11-06).
        assertThat(tool.specification().tool().description()).isEqualTo(
                "Adds a comment to a YouTrack issue. Takes an issue ID (for example \"ABC-123\") "
                        + "and the comment in Markdown. Use search_issues to find IDs.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requiresIssueIdAndCommentAsStrings() {
        Map<String, Object> schema = tool.specification().tool().inputSchema();

        assertThat(schema).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsOnlyKeys("issueId", "comment");
        for (String name : List.of("issueId", "comment")) {
            assertThat((Map<String, Object>) properties.get(name)).as(name)
                    .containsEntry("type", "string").doesNotContainKey("pattern");
        }
        assertThat((List<String>) schema.get("required")).containsExactlyInAnyOrder("issueId", "comment");
    }

    @Test
    @SuppressWarnings("unchecked")
    void propertyDescriptionsSayWhatEachValueIs() {
        Map<String, Object> properties =
                (Map<String, Object>) tool.specification().tool().inputSchema().get("properties");

        assertThat((Map<String, Object>) properties.get("issueId"))
                .containsEntry("description", "A YouTrack issue ID, for example \"ABC-123\".");
        assertThat((Map<String, Object>) properties.get("comment"))
                .containsEntry("description", "The comment, in Markdown. Line breaks are kept. "
                        + "Write only what the user told you or what the conversation shows; "
                        + "don't add details they didn't give.");
    }

    @Test
    void specificationHandlerDelegatesToHandle() {
        CallToolResult result = tool.specification().callHandler().apply(null, new CallToolRequest(
                "add_comment", Map.of("issueId", "DEMO-13", "comment", "Fixed in the 2.3 build")));

        assertThat(text(result)).startsWith("Added a comment to DEMO-13.");
    }

    // --- input: issueId ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"DEMO-12", "demo-12", "A-1", "A_B1-30", "Ab9-007"})
    void passesAValidIdThroughWithItsCaseUnchanged(String issueId) {
        CallToolResult result = tool.handle(Map.of("issueId", issueId, "comment", "Fixed in the 2.3 build"));

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(client.calls()).containsExactly("addComment(\"" + issueId + "\", \"Fixed in the 2.3 build\")");
    }

    @Test
    void stripsTheIdBeforeCheckingAndCalling() {
        tool.handle(Map.of("issueId", "  DEMO-13 \t", "comment", "Fixed in the 2.3 build"));

        assertThat(client.calls()).containsExactly("addComment(\"DEMO-13\", \"Fixed in the 2.3 build\")");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEMO 1", "DEMO-", "-1", "DEMO", "12", "1DEMO-2", "_DEMO-2", "DEMO-1a",
            "DEMO-1/comments", "DEMO-1?fields=x", "../DEMO-1", "DEMO-1#x", "DE MO-1", "DEMO--1"})
    void malformedIdIsAnInvalidArgumentQuotingTheStrippedValue(String issueId) {
        // The ID goes into the request path, so anything that isn't an ID must stop here.
        CallToolResult result = tool.handle(Map.of("issueId", " " + issueId + " ", "comment", "Fixed in the 2.3 build"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("\"" + issueId + "\" is not an issue ID. Issue IDs look like "
                + "\"ABC-123\"; use search_issues to find one.");
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void missingNullNonStringOrBlankIdIsAnInvalidArgument() {
        Map<String, Object> nullId = new HashMap<>();
        nullId.put("issueId", null);
        nullId.put("comment", "Fixed in the 2.3 build");

        for (Map<String, Object> arguments : List.of(
                Map.<String, Object>of("comment", "Fixed in the 2.3 build"),
                nullId,
                Map.<String, Object>of("issueId", 13, "comment", "Fixed in the 2.3 build"),
                Map.<String, Object>of("issueId", "  \t", "comment", "Fixed in the 2.3 build"))) {
            CallToolResult result = tool.handle(arguments);

            assertThat(isError(result)).as("arguments %s", arguments).isTrue();
            assertThat(text(result)).as("arguments %s", arguments).isEqualTo(ISSUE_ID_REQUIRED);
        }
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void nullArgumentsAreAnInvalidIssueIdArgument() {
        // issueId is checked first, so its message comes back even though comment is missing too.
        CallToolResult result = tool.handle(null);

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(ISSUE_ID_REQUIRED);
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void issueIdIsCheckedBeforeComment() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO", "comment", ""));

        assertThat(text(result)).startsWith("\"DEMO\" is not an issue ID.");
    }

    // --- input: comment ------------------------------------------------------------------

    @Test
    void passesTheCommentAsWritten() {
        // Not stripped: Markdown and line breaks, leading and trailing whitespace included.
        String comment = "  Checked on **Safari 18**:\n\n- login works\r\n- logout works  \n";

        tool.handle(Map.of("issueId", "DEMO-13", "comment", comment));

        assertThat(client.calls()).containsExactly("addComment(\"DEMO-13\", \"" + comment + "\")");
    }

    @Test
    void missingNullNonStringOrBlankCommentIsAnInvalidArgument() {
        Map<String, Object> nullComment = new HashMap<>();
        nullComment.put("issueId", "DEMO-13");
        nullComment.put("comment", null);

        for (Map<String, Object> arguments : List.of(
                Map.<String, Object>of("issueId", "DEMO-13"),
                nullComment,
                Map.<String, Object>of("issueId", "DEMO-13", "comment", 42),
                Map.<String, Object>of("issueId", "DEMO-13", "comment", ""),
                Map.<String, Object>of("issueId", "DEMO-13", "comment", " \t\n"))) {
            CallToolResult result = tool.handle(arguments);

            assertThat(isError(result)).as("arguments %s", arguments).isTrue();
            assertThat(text(result)).as("arguments %s", arguments).isEqualTo(COMMENT_REQUIRED);
        }
        assertThat(client.calls()).isEmpty();
    }

    // --- output --------------------------------------------------------------------------

    @Test
    void returnsTheIdAndLink() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "comment", "Fixed in the 2.3 build"));

        assertThat(isError(result)).isFalse();
        assertThat(text(result)).isEqualTo("""
                Added a comment to DEMO-13.
                https://youtrack.example.com/issue/DEMO-13""");
    }

    @Test
    void showsTheIdYouTrackReturnedNotTheOneSent() {
        CallToolResult result = tool.handle(Map.of("issueId", "demo-13", "comment", "Fixed in the 2.3 build"));

        assertThat(text(result)).startsWith("Added a comment to DEMO-13.\n");
    }

    @Test
    void returnsASingleTextBlock() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "comment", "Fixed in the 2.3 build"));

        assertThat(result.content()).hasSize(1).first().isInstanceOf(TextContent.class);
    }

    // --- errors --------------------------------------------------------------------------

    @Test
    void notFoundNamesTheIdAndPointsToSearch() {
        client.failingWith(new IssueNotFoundException("DEMO-99999"));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-99999", "comment", "Fixed in the 2.3 build"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(
                "Issue DEMO-99999 was not found. Check the ID, or use search_issues to find it.");
    }

    @Test
    void rejectionCarriesYouTracksText() {
        client.failingWith(new RequestRejectedException("Comment can't be empty."));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "comment", "Fixed in the 2.3 build"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack rejected the comment: Comment can't be empty.");
    }

    @Test
    void rejectionWithoutEndingPunctuationGetsAPeriod() {
        // Synthetic: the captured text with its period removed.
        client.failingWith(new RequestRejectedException("Comment can't be empty"));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "comment", "Fixed in the 2.3 build"));

        assertThat(text(result)).isEqualTo("YouTrack rejected the comment: Comment can't be empty.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Comment can't be empty?", "Comment can't be empty!"})
    void rejectionDoesNotAddAPeriodAfterAQuestionOrExclamationMark(String detail) {
        // Synthetic: the captured wording with its ending changed.
        client.failingWith(new RequestRejectedException(detail));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "comment", "Fixed in the 2.3 build"));

        assertThat(text(result)).isEqualTo("YouTrack rejected the comment: " + detail);
    }

    @Test
    void authFailureBecomesTheSharedAuthText() {
        client.failingWith(new YouTrackAuthException("The token lacks permission for this request."));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "comment", "Fixed in the 2.3 build"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack refused the request: the token is invalid or lacks access. "
                + "Tell the user; retrying won't help.");
    }

    @Test
    void unavailableSaysTheCommentMayExistAndToCheckBeforeRetrying() {
        // A failure after the POST was sent doesn't prove the comment wasn't added (design.md).
        client.failingWith(new YouTrackUnavailableException("Could not reach YouTrack.", new IOException("reset")));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "comment", "Fixed in the 2.3 build"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack could not be reached; the comment may have been added. "
                + "Check the issue with get_issue before trying again.");
    }

    @Test
    void unexpectedExceptionBecomesAGenericErrorResult() {
        client.failingWith(new IllegalStateException("bug with internal detail"));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "comment", "Fixed in the 2.3 build"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result))
                .isEqualTo("Internal error in youtrack-mcp; see the server log.")
                .doesNotContain("internal detail");
    }

    // --- helpers -------------------------------------------------------------------------

    private static boolean isError(CallToolResult result) {
        return Boolean.TRUE.equals(result.isError());
    }

    private static String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((TextContent) result.content().getFirst()).text();
    }
}
