package com.anvil43.youtrackmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.anvil43.youtrackmcp.FakeYouTrackClient;
import com.anvil43.youtrackmcp.exception.RequestRejectedException;
import com.anvil43.youtrackmcp.exception.YouTrackAuthException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.CreatedIssue;
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

class CreateIssueToolTest {

    private static final String PROJECT_REQUIRED =
            "project is required. Pass a project key from list_projects, for example \"ABC\".";
    private static final String SUMMARY_REQUIRED = "summary is required. Pass a one-line summary of the issue.";
    private static final String SUMMARY_SINGLE_LINE = "summary must be a single line; put details in description.";
    private static final String REJECTION_HINT =
            " Check the project key with list_projects. If a field is required, tell the user; create_issue can't set fields.";

    private static final CreatedIssue CREATED =
            new CreatedIssue("DEMO-13", "Login fails on Safari", "https://youtrack.example.com/issue/DEMO-13");

    private final FakeYouTrackClient client = new FakeYouTrackClient().withCreatedIssue(CREATED);
    private final CreateIssueTool tool = new CreateIssueTool(client);

    // --- specification -------------------------------------------------------------------

    @Test
    void isNamedCreateIssue() {
        assertThat(tool.specification().tool().name()).isEqualTo("create_issue");
    }

    @Test
    void descriptionSaysNotToPickAProject() {
        assertThat(tool.specification().tool().description()).isEqualTo(
                "Creates a YouTrack issue and returns its ID. Takes a project key from list_projects "
                        + "(for example \"ABC\"), a one-line summary and an optional description in Markdown. "
                        + "If the user hasn't said which project, don't pick one: call list_projects and ask them. "
                        + "Set fields such as state or assignee afterwards with update_issue.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requiresProjectAndSummaryAsStringsAndDescriptionIsOptional() {
        Map<String, Object> schema = tool.specification().tool().inputSchema();

        assertThat(schema).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsOnlyKeys("project", "summary", "description");
        for (String name : List.of("project", "summary", "description")) {
            assertThat((Map<String, Object>) properties.get(name)).as(name)
                    .containsEntry("type", "string").doesNotContainKey("pattern");
        }
        assertThat((List<String>) schema.get("required")).containsExactlyInAnyOrder("project", "summary");
    }

    @Test
    @SuppressWarnings("unchecked")
    void propertyDescriptionsSayWhatEachValueIs() {
        // Each says what the value is, not what errors mean; the don't-guess rule stays in the
        // tool description.
        Map<String, Object> properties =
                (Map<String, Object>) tool.specification().tool().inputSchema().get("properties");

        assertThat((Map<String, Object>) properties.get("project"))
                .containsEntry("description", "A project key from list_projects, for example \"ABC\".");
        assertThat((Map<String, Object>) properties.get("summary"))
                .containsEntry("description", "A one-line summary of the issue.");
        assertThat((Map<String, Object>) properties.get("description"))
                .containsEntry("description", "Optional details, in Markdown. Line breaks are kept. "
                        + "Write only what the user told you or what the conversation shows; "
                        + "don't add steps, versions or causes they didn't give.");
    }

    @Test
    void specificationHandlerDelegatesToHandle() {
        CallToolResult result = tool.specification().callHandler().apply(null, new CallToolRequest(
                "create_issue", Map.of("project", "DEMO", "summary", "Login fails on Safari")));

        assertThat(text(result)).startsWith("Created DEMO-13: ");
    }

    // --- input: project ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"DEMO", "demo", "A", "A_B1", "Ab9"})
    void passesAValidKeyThroughWithItsCaseUnchanged(String project) {
        CallToolResult result = tool.handle(Map.of("project", project, "summary", "Login fails on Safari"));

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(client.calls()).containsExactly("createIssue(\"" + project + "\", \"Login fails on Safari\", null)");
    }

    @Test
    void stripsTheKeyBeforeCheckingAndCalling() {
        tool.handle(Map.of("project", "  DEMO \t", "summary", "Login fails on Safari"));

        assertThat(client.calls()).containsExactly("createIssue(\"DEMO\", \"Login fails on Safari\", null)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEMO-1", "DE MO", "1DEMO", "_DEMO", "DEMO!", "project: DEMO", "DEMO/x", "\"DEMO\""})
    void malformedKeyIsAnInvalidArgumentQuotingTheStrippedValue(String project) {
        CallToolResult result = tool.handle(Map.of("project", " " + project + " ", "summary", "Login fails on Safari"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("\"" + project + "\" is not a project key. Project keys look like "
                + "\"ABC\"; use list_projects to see them.");
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void missingNullNonStringOrBlankProjectIsAnInvalidArgument() {
        Map<String, Object> nullProject = new HashMap<>();
        nullProject.put("project", null);
        nullProject.put("summary", "Login fails on Safari");

        for (Map<String, Object> arguments : List.of(
                Map.<String, Object>of("summary", "Login fails on Safari"),
                nullProject,
                Map.<String, Object>of("project", 12, "summary", "Login fails on Safari"),
                Map.<String, Object>of("project", "  \t", "summary", "Login fails on Safari"))) {
            CallToolResult result = tool.handle(arguments);

            assertThat(isError(result)).as("arguments %s", arguments).isTrue();
            assertThat(text(result)).as("arguments %s", arguments).isEqualTo(PROJECT_REQUIRED);
        }
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void nullArgumentsAreAnInvalidProjectArgument() {
        // Project is checked first, so its message comes back even though summary is missing too.
        CallToolResult result = tool.handle(null);

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(PROJECT_REQUIRED);
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void projectIsCheckedBeforeSummary() {
        CallToolResult result = tool.handle(Map.of("project", "DEMO-1", "summary", ""));

        assertThat(text(result)).startsWith("\"DEMO-1\" is not a project key.");
    }

    // --- input: summary ------------------------------------------------------------------

    @Test
    void stripsTheSummary() {
        tool.handle(Map.of("project", "DEMO", "summary", "  Login fails on Safari \t"));

        assertThat(client.calls()).containsExactly("createIssue(\"DEMO\", \"Login fails on Safari\", null)");
    }

    @Test
    void missingNullNonStringOrBlankSummaryIsAnInvalidArgument() {
        Map<String, Object> nullSummary = new HashMap<>();
        nullSummary.put("project", "DEMO");
        nullSummary.put("summary", null);

        for (Map<String, Object> arguments : List.of(
                Map.<String, Object>of("project", "DEMO"),
                nullSummary,
                Map.<String, Object>of("project", "DEMO", "summary", 12),
                Map.<String, Object>of("project", "DEMO", "summary", ""),
                Map.<String, Object>of("project", "DEMO", "summary", " \t\n"))) {
            CallToolResult result = tool.handle(arguments);

            assertThat(isError(result)).as("arguments %s", arguments).isTrue();
            assertThat(text(result)).as("arguments %s", arguments).isEqualTo(SUMMARY_REQUIRED);
        }
        assertThat(client.calls()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Login fails\non Safari", "Login fails\r\non Safari", "Login fails\ron Safari"})
    void summaryWithALineBreakIsRejectedNotFlattened(String summary) {
        CallToolResult result = tool.handle(Map.of("project", "DEMO", "summary", summary));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(SUMMARY_SINGLE_LINE);
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void lineBreaksAroundTheSummaryAreStrippedNotRejected() {
        CallToolResult result = tool.handle(Map.of("project", "DEMO", "summary", "\nLogin fails on Safari\r\n"));

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(client.calls()).containsExactly("createIssue(\"DEMO\", \"Login fails on Safari\", null)");
    }

    // --- input: description --------------------------------------------------------------

    @Test
    void passesTheDescriptionAsWritten() {
        String description = "  Steps:\n\n1. Open **Safari**\r\n2. Log in  \n";

        tool.handle(Map.of("project", "DEMO", "summary", "Login fails on Safari", "description", description));

        assertThat(client.calls()).containsExactly(
                "createIssue(\"DEMO\", \"Login fails on Safari\", \"" + description + "\")");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", " \t\n"})
    void blankDescriptionIsNotSent(String blank) {
        tool.handle(Map.of("project", "DEMO", "summary", "Login fails on Safari", "description", blank));

        assertThat(client.calls()).containsExactly("createIssue(\"DEMO\", \"Login fails on Safari\", null)");
    }

    @Test
    void nullDescriptionIsNotSent() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("project", "DEMO");
        arguments.put("summary", "Login fails on Safari");
        arguments.put("description", null);

        tool.handle(arguments);

        assertThat(client.calls()).containsExactly("createIssue(\"DEMO\", \"Login fails on Safari\", null)");
    }

    @Test
    void nonStringDescriptionIsIgnoredAndTheIssueIsStillCreated() {
        // The SDK's schema check stops this before handle runs; if one gets through, the
        // description is treated like a blank one, not an internal error.
        CallToolResult result = tool.handle(Map.of("project", "DEMO", "summary", "Login fails on Safari", "description", 42));

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(client.calls()).containsExactly("createIssue(\"DEMO\", \"Login fails on Safari\", null)");
    }

    // --- output --------------------------------------------------------------------------

    @Test
    void returnsTheIdSummaryAndLink() {
        CallToolResult result = tool.handle(Map.of("project", "DEMO", "summary", "Login fails on Safari"));

        assertThat(isError(result)).isFalse();
        assertThat(text(result)).isEqualTo("""
                Created DEMO-13: Login fails on Safari
                https://youtrack.example.com/issue/DEMO-13""");
    }

    @Test
    void showsTheSummaryYouTrackStoredNotTheOneSent() {
        client.withCreatedIssue(new CreatedIssue("DEMO-13", "As stored", "https://youtrack.example.com/issue/DEMO-13"));

        CallToolResult result = tool.handle(Map.of("project", "DEMO", "summary", "As sent"));

        assertThat(text(result)).startsWith("Created DEMO-13: As stored\n");
    }

    @Test
    void returnsASingleTextBlock() {
        CallToolResult result = tool.handle(Map.of("project", "DEMO", "summary", "Login fails on Safari"));

        assertThat(result.content()).hasSize(1).first().isInstanceOf(TextContent.class);
    }

    // --- errors --------------------------------------------------------------------------

    @Test
    void rejectionCarriesYouTracksTextAndTheHint() {
        client.failingWith(new RequestRejectedException("Test Field is required"));

        CallToolResult result = tool.handle(Map.of("project", "DEMO", "summary", "Login fails on Safari"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack rejected the issue: Test Field is required." + REJECTION_HINT);
    }

    @Test
    void unknownProjectRejectionGetsTheSameHint() {
        client.failingWith(new RequestRejectedException(
                "YouTrack is unable to locate an Project-type entity with the specified ID"));

        CallToolResult result = tool.handle(Map.of("project", "NOPE", "summary", "Login fails on Safari"));

        assertThat(text(result)).isEqualTo("YouTrack rejected the issue: YouTrack is unable to locate an "
                + "Project-type entity with the specified ID." + REJECTION_HINT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Value expected for field project.", "Value expected for field project?",
            "Value expected for field project!"})
    void rejectionDoesNotAddAPeriodAfterEndingPunctuation(String detail) {
        // Synthetic details (no real create rejection ending this way has been seen): the real
        // wording from a query error, used for its ending.
        client.failingWith(new RequestRejectedException(detail));

        CallToolResult result = tool.handle(Map.of("project", "DEMO", "summary", "Login fails on Safari"));

        assertThat(text(result)).isEqualTo("YouTrack rejected the issue: " + detail + REJECTION_HINT);
    }

    @Test
    void authFailureBecomesTheSharedAuthText() {
        client.failingWith(new YouTrackAuthException("The token lacks permission for this request."));

        CallToolResult result = tool.handle(Map.of("project", "DEMO", "summary", "Login fails on Safari"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack refused the request: the token is invalid or lacks access. "
                + "Tell the user; retrying won't help.");
    }

    @Test
    void unavailableSaysTheIssueMayExistAndToSearchBeforeRetrying() {
        // A failure after the POST was sent doesn't prove the issue wasn't created.
        client.failingWith(new YouTrackUnavailableException("Could not reach YouTrack.", new IOException("reset")));

        CallToolResult result = tool.handle(Map.of("project", "DEMO", "summary", "Login fails on Safari"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack could not be reached; the issue may have been created. "
                + "Search for it before trying again.");
    }

    @Test
    void unexpectedExceptionBecomesAGenericErrorResult() {
        client.failingWith(new IllegalStateException("bug with internal detail"));

        CallToolResult result = tool.handle(Map.of("project", "DEMO", "summary", "Login fails on Safari"));

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
