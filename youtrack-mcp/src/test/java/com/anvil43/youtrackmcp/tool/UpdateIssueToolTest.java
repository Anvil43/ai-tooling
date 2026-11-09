package com.anvil43.youtrackmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.anvil43.youtrackmcp.FakeYouTrackClient;
import com.anvil43.youtrackmcp.exception.RequestRejectedException;
import com.anvil43.youtrackmcp.exception.YouTrackAuthException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.UpdatedIssue;
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

class UpdateIssueToolTest {

    private static final String ISSUE_ID_REQUIRED = "issueId is required. Pass an issue ID, for example \"ABC-123\".";
    private static final String COMMAND_REQUIRED =
            "command is required. Pass a YouTrack command, for example \"State In Progress\".";
    private static final String REJECTION_HINT =
            " Nothing was changed. Check the issue ID, and the field names and values with get_issue.";

    private static final UpdatedIssue UPDATED = new UpdatedIssue("DEMO-13",
            List.of("Set State In Progress", "Set Assignee jane.doe"),
            "https://youtrack.example.com/issue/DEMO-13");

    private final FakeYouTrackClient client = new FakeYouTrackClient().withUpdatedIssue(UPDATED);
    private final UpdateIssueTool tool = new UpdateIssueTool(client);

    // --- specification -------------------------------------------------------------------

    @Test
    void isNamedUpdateIssue() {
        assertThat(tool.specification().tool().name()).isEqualTo("update_issue");
    }

    @Test
    void descriptionGivesExamplesAndSaysNotToGuessValues() {
        assertThat(tool.specification().tool().description()).isEqualTo(
                "Changes a YouTrack issue by applying a command, the same text as in YouTrack's command "
                        + "dialog: for example \"State In Progress\", \"Assignee me\", \"Priority Major\", "
                        + "\"tag Release\" or \"subtask of ABC-1\". One command can set several fields. Use the field names and values "
                        + "that get_issue shows; if you're not sure a value exists, ask the user rather than "
                        + "guessing. Use search_issues to find IDs.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requiresIssueIdAndCommandAsStrings() {
        Map<String, Object> schema = tool.specification().tool().inputSchema();

        assertThat(schema).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsOnlyKeys("issueId", "command");
        for (String name : List.of("issueId", "command")) {
            assertThat((Map<String, Object>) properties.get(name)).as(name)
                    .containsEntry("type", "string").doesNotContainKey("pattern");
        }
        assertThat((List<String>) schema.get("required")).containsExactlyInAnyOrder("issueId", "command");
    }

    @Test
    @SuppressWarnings("unchecked")
    void propertyDescriptionsSayWhatEachValueIs() {
        Map<String, Object> properties =
                (Map<String, Object>) tool.specification().tool().inputSchema().get("properties");

        assertThat((Map<String, Object>) properties.get("issueId"))
                .containsEntry("description", "A YouTrack issue ID, for example \"ABC-123\".");
        assertThat((Map<String, Object>) properties.get("command"))
                .containsEntry("description", "A YouTrack command, for example \"State In Progress Assignee me\". "
                        + "Use field names and values from get_issue.");
    }

    @Test
    void specificationHandlerDelegatesToHandle() {
        CallToolResult result = tool.specification().callHandler().apply(null, new CallToolRequest(
                "update_issue", Map.of("issueId", "DEMO-13", "command", "State In Progress")));

        assertThat(text(result)).startsWith("Updated DEMO-13:");
    }

    // --- input: issueId ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"DEMO-12", "demo-12", "A-1", "A_B1-30", "Ab9-007"})
    void passesAValidIdThroughWithItsCaseUnchanged(String issueId) {
        CallToolResult result = tool.handle(Map.of("issueId", issueId, "command", "State In Progress"));

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(client.calls()).containsExactly("updateIssue(\"" + issueId + "\", \"State In Progress\")");
    }

    @Test
    void stripsTheIdBeforeCheckingAndCalling() {
        tool.handle(Map.of("issueId", "  DEMO-13 \t", "command", "State In Progress"));

        assertThat(client.calls()).containsExactly("updateIssue(\"DEMO-13\", \"State In Progress\")");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEMO 1", "DEMO-", "-1", "DEMO", "12", "1DEMO-2", "_DEMO-2", "DEMO-1a",
            "DEMO-1/comments", "DEMO-1?fields=x", "../DEMO-1", "DEMO-1#x", "DE MO-1", "DEMO--1"})
    void malformedIdIsAnInvalidArgumentQuotingTheStrippedValue(String issueId) {
        CallToolResult result = tool.handle(Map.of("issueId", " " + issueId + " ", "command", "State In Progress"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("\"" + issueId + "\" is not an issue ID. Issue IDs look like "
                + "\"ABC-123\"; use search_issues to find one.");
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void missingNullNonStringOrBlankIdIsAnInvalidArgument() {
        Map<String, Object> nullId = new HashMap<>();
        nullId.put("issueId", null);
        nullId.put("command", "State In Progress");

        for (Map<String, Object> arguments : List.of(
                Map.<String, Object>of("command", "State In Progress"),
                nullId,
                Map.<String, Object>of("issueId", 13, "command", "State In Progress"),
                Map.<String, Object>of("issueId", "  \t", "command", "State In Progress"))) {
            CallToolResult result = tool.handle(arguments);

            assertThat(isError(result)).as("arguments %s", arguments).isTrue();
            assertThat(text(result)).as("arguments %s", arguments).isEqualTo(ISSUE_ID_REQUIRED);
        }
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void nullArgumentsAreAnInvalidIssueIdArgument() {
        CallToolResult result = tool.handle(null);

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(ISSUE_ID_REQUIRED);
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void issueIdIsCheckedBeforeCommand() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO", "command", ""));

        assertThat(text(result)).startsWith("\"DEMO\" is not an issue ID.");
    }

    // --- input: command ------------------------------------------------------------------

    @Test
    void stripsTheCommandBeforeCalling() {
        // A command is an instruction, so outer whitespace is never meaningful.
        tool.handle(Map.of("issueId", "DEMO-13", "command", "  Priority Major Assignee me \n"));

        assertThat(client.calls()).containsExactly("updateIssue(\"DEMO-13\", \"Priority Major Assignee me\")");
    }

    @Test
    void passesTheCommandOtherwiseUnchanged() {
        // Case, inner spacing and braces are YouTrack's to interpret.
        tool.handle(Map.of("issueId", "DEMO-13", "command", "state {in progress}  tag R&D"));

        assertThat(client.calls()).containsExactly("updateIssue(\"DEMO-13\", \"state {in progress}  tag R&D\")");
    }

    @Test
    void missingNullNonStringOrBlankCommandIsAnInvalidArgument() {
        // YouTrack answers an empty command with 200 and changes nothing, so it must stop here.
        Map<String, Object> nullCommand = new HashMap<>();
        nullCommand.put("issueId", "DEMO-13");
        nullCommand.put("command", null);

        for (Map<String, Object> arguments : List.of(
                Map.<String, Object>of("issueId", "DEMO-13"),
                nullCommand,
                Map.<String, Object>of("issueId", "DEMO-13", "command", 42),
                Map.<String, Object>of("issueId", "DEMO-13", "command", ""),
                Map.<String, Object>of("issueId", "DEMO-13", "command", " \t\n"))) {
            CallToolResult result = tool.handle(arguments);

            assertThat(isError(result)).as("arguments %s", arguments).isTrue();
            assertThat(text(result)).as("arguments %s", arguments).isEqualTo(COMMAND_REQUIRED);
        }
        assertThat(client.calls()).isEmpty();
    }

    // --- output --------------------------------------------------------------------------

    @Test
    void listsEachChangeThenTheLink() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "command", "State In Progress Assignee me"));

        assertThat(isError(result)).isFalse();
        assertThat(text(result)).isEqualTo("""
                Updated DEMO-13:
                - Set State In Progress
                - Set Assignee jane.doe
                https://youtrack.example.com/issue/DEMO-13""");
    }

    @Test
    void showsTheIdYouTrackReturnedNotTheOneSent() {
        CallToolResult result = tool.handle(Map.of("issueId", "demo-13", "command", "State In Progress"));

        assertThat(text(result)).startsWith("Updated DEMO-13:\n");
    }

    @Test
    void noChangesSaysSoAndStillGivesTheLink() {
        client.withUpdatedIssue(new UpdatedIssue("DEMO-13", List.of(),
                "https://youtrack.example.com/issue/DEMO-13"));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "command", "State In Progress"));

        assertThat(isError(result)).isFalse();
        assertThat(text(result)).isEqualTo("""
                No changes were made to DEMO-13.
                https://youtrack.example.com/issue/DEMO-13""");
    }

    @Test
    void returnsASingleTextBlock() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "command", "State In Progress"));

        assertThat(result.content()).hasSize(1).first().isInstanceOf(TextContent.class);
    }

    // --- errors --------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "State expected: Bogus.",
            "Unknown command: make it better.",
            "YouTrack is unable to locate an Issue-type entity with the specified ID."})
    void rejectionCarriesYouTracksTextAndSaysNothingChanged(String detail) {
        // The captured texts, with a period where YouTrack leaves it out; an unknown issue
        // arrives as a 400 like the others and isn't treated separately.
        client.failingWith(new RequestRejectedException(detail));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "command", "State Bogus"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack rejected the command: " + detail + REJECTION_HINT);
    }

    @Test
    void rejectionWithoutEndingPunctuationGetsAPeriod() {
        client.failingWith(new RequestRejectedException("State expected: Bogus"));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "command", "State Bogus"));

        assertThat(text(result)).isEqualTo("YouTrack rejected the command: State expected: Bogus." + REJECTION_HINT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"State expected: Bogus?", "State expected: Bogus!"})
    void rejectionDoesNotAddAPeriodAfterAQuestionOrExclamationMark(String detail) {
        // Synthetic: the captured wording with its ending changed.
        client.failingWith(new RequestRejectedException(detail));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "command", "State Bogus"));

        assertThat(text(result)).isEqualTo("YouTrack rejected the command: " + detail + REJECTION_HINT);
    }

    @Test
    void authFailureBecomesTheSharedAuthText() {
        client.failingWith(new YouTrackAuthException("The token lacks permission for this request."));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "command", "State In Progress"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack refused the request: the token is invalid or lacks access. "
                + "Tell the user; retrying won't help.");
    }

    @Test
    void unavailableSaysTheIssueMayHaveChangedAndToCheckBeforeRetrying() {
        client.failingWith(new YouTrackUnavailableException("Could not reach YouTrack.", new IOException("reset")));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "command", "State In Progress"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack could not be reached; the issue may have been updated. "
                + "Check it with get_issue before trying again.");
    }

    @Test
    void unexpectedExceptionBecomesAGenericErrorResult() {
        client.failingWith(new IllegalStateException("bug with internal detail"));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "command", "State In Progress"));

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
