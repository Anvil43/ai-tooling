package com.anvil43.youtrackmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.anvil43.youtrackmcp.FakeYouTrackClient;
import com.anvil43.youtrackmcp.exception.IssueNotFoundException;
import com.anvil43.youtrackmcp.exception.RequestRejectedException;
import com.anvil43.youtrackmcp.exception.YouTrackAuthException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.UpdatedText;
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

class UpdateSummaryAndDescriptionToolTest {

    private static final String ISSUE_ID_REQUIRED = "issueId is required. Pass an issue ID, for example \"ABC-123\".";
    private static final String SUMMARY_BLANK = "summary can't be blank. Leave it out to keep the current summary.";
    private static final String DESCRIPTION_BLANK =
            "description can't be blank. Leave it out to keep the current description.";
    private static final String SUMMARY_MULTI_LINE = "summary must be a single line; put details in description.";
    private static final String NOTHING_TO_CHANGE = "Pass summary, description or both.";

    private static final UpdatedText UPDATED =
            new UpdatedText("DEMO-13", "https://youtrack.example.com/issue/DEMO-13");

    private final FakeYouTrackClient client = new FakeYouTrackClient().withUpdatedText(UPDATED);
    private final UpdateSummaryAndDescriptionTool tool = new UpdateSummaryAndDescriptionTool(client);

    // --- specification -------------------------------------------------------------------

    @Test
    void isNamedUpdateSummaryAndDescription() {
        assertThat(tool.specification().tool().name()).isEqualTo("update_summary_and_description");
    }

    @Test
    void descriptionSaysWhatChangesAndPointsToGetIssueAndUpdateIssue() {
        assertThat(tool.specification().tool().description()).isEqualTo(
                "Changes a YouTrack issue's summary, description or both. Pass only what should change; "
                        + "anything left out stays as it is. The description replaces the old one completely, "
                        + "so to edit part of it, call get_issue first and send the full new text. Takes an "
                        + "issue ID (for example \"ABC-123\"). For fields such as state or assignee, use "
                        + "update_issue.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requiresOnlyTheIssueIdAndTakesStrings() {
        // "At least one of summary and description" isn't expressible as required; the tool checks it.
        Map<String, Object> schema = tool.specification().tool().inputSchema();

        assertThat(schema).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsOnlyKeys("issueId", "summary", "description");
        for (String name : List.of("issueId", "summary", "description")) {
            assertThat((Map<String, Object>) properties.get(name)).as(name)
                    .containsEntry("type", "string").doesNotContainKey("pattern");
        }
        assertThat((List<String>) schema.get("required")).containsExactly("issueId");
    }

    @Test
    @SuppressWarnings("unchecked")
    void propertyDescriptionsSayWhatEachValueIs() {
        Map<String, Object> properties =
                (Map<String, Object>) tool.specification().tool().inputSchema().get("properties");

        assertThat((Map<String, Object>) properties.get("issueId"))
                .containsEntry("description", "A YouTrack issue ID, for example \"ABC-123\".");
        assertThat((Map<String, Object>) properties.get("summary"))
                .containsEntry("description", "The new one-line summary. Leave it out to keep the current one.");
        assertThat((Map<String, Object>) properties.get("description"))
                .containsEntry("description", "The new description, in Markdown; it replaces the whole old one. "
                        + "Line breaks are kept. Leave it out to keep the current one. Write only what the user "
                        + "told you or what the conversation shows; don't add details they didn't give.");
    }

    @Test
    void specificationHandlerDelegatesToHandle() {
        CallToolResult result = tool.specification().callHandler().apply(null, new CallToolRequest(
                "update_summary_and_description", Map.of("issueId", "DEMO-13", "summary", "Login fails on Safari 18")));

        assertThat(text(result)).startsWith("Updated the summary of DEMO-13.");
    }

    // --- input: issueId ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"DEMO-12", "demo-12", "A-1", "A_B1-30", "Ab9-007"})
    void passesAValidIdThroughWithItsCaseUnchanged(String issueId) {
        CallToolResult result = tool.handle(Map.of("issueId", issueId, "summary", "New summary"));

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(client.calls()).containsExactly("updateSummaryAndDescription(\"" + issueId + "\", \"New summary\", null)");
    }

    @Test
    void stripsTheIdBeforeCheckingAndCalling() {
        tool.handle(Map.of("issueId", "  DEMO-13 \t", "summary", "New summary"));

        assertThat(client.calls()).containsExactly("updateSummaryAndDescription(\"DEMO-13\", \"New summary\", null)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEMO 1", "DEMO-", "-1", "DEMO", "12", "1DEMO-2", "_DEMO-2", "DEMO-1a",
            "DEMO-1/comments", "DEMO-1?fields=x", "../DEMO-1", "DEMO-1#x", "DE MO-1", "DEMO--1"})
    void malformedIdIsAnInvalidArgumentQuotingTheStrippedValue(String issueId) {
        // The ID goes into the request path, so anything that isn't an ID must stop here.
        CallToolResult result = tool.handle(Map.of("issueId", " " + issueId + " ", "summary", "New summary"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("\"" + issueId + "\" is not an issue ID. Issue IDs look like "
                + "\"ABC-123\"; use search_issues to find one.");
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void missingNullNonStringOrBlankIdIsAnInvalidArgument() {
        Map<String, Object> nullId = new HashMap<>();
        nullId.put("issueId", null);
        nullId.put("summary", "New summary");

        for (Map<String, Object> arguments : List.of(
                Map.<String, Object>of("summary", "New summary"),
                nullId,
                Map.<String, Object>of("issueId", 13, "summary", "New summary"),
                Map.<String, Object>of("issueId", "  \t", "summary", "New summary"))) {
            CallToolResult result = tool.handle(arguments);

            assertThat(isError(result)).as("arguments %s", arguments).isTrue();
            assertThat(text(result)).as("arguments %s", arguments).isEqualTo(ISSUE_ID_REQUIRED);
        }
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void nullArgumentsAreAnInvalidIssueIdArgument() {
        // issueId is checked first, so its message comes back even though both parts are missing too.
        CallToolResult result = tool.handle(null);

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(ISSUE_ID_REQUIRED);
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void issueIdIsCheckedBeforeTheParts() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO", "summary", ""));

        assertThat(text(result)).startsWith("\"DEMO\" is not an issue ID.");
    }

    // --- input: which parts are sent -----------------------------------------------------

    @Test
    void summaryOnlyLeavesTheDescriptionOut() {
        tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary"));

        assertThat(client.calls()).containsExactly("updateSummaryAndDescription(\"DEMO-13\", \"New summary\", null)");
    }

    @Test
    void descriptionOnlyLeavesTheSummaryOut() {
        tool.handle(Map.of("issueId", "DEMO-13", "description", "New description"));

        assertThat(client.calls()).containsExactly("updateSummaryAndDescription(\"DEMO-13\", null, \"New description\")");
    }

    @Test
    void bothPartsAreSentTogether() {
        tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary", "description", "New description"));

        assertThat(client.calls())
                .containsExactly("updateSummaryAndDescription(\"DEMO-13\", \"New summary\", \"New description\")");
    }

    @Test
    void aNullPartCountsAsLeftOut() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("issueId", "DEMO-13");
        arguments.put("summary", null);
        arguments.put("description", "New description");

        CallToolResult result = tool.handle(arguments);

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(client.calls()).containsExactly("updateSummaryAndDescription(\"DEMO-13\", null, \"New description\")");
    }

    // --- input: summary ------------------------------------------------------------------

    @Test
    void stripsTheSummary() {
        // As create_issue does; a trailing line break is stripped rather than rejected.
        tool.handle(Map.of("issueId", "DEMO-13", "summary", "  Login fails on Safari 18 \n"));

        assertThat(client.calls())
                .containsExactly("updateSummaryAndDescription(\"DEMO-13\", \"Login fails on Safari 18\", null)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Two\nlines", "Two\r\nlines", "Two\rlines"})
    void summaryWithALineBreakIsAnInvalidArgument(String summary) {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", summary));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(SUMMARY_MULTI_LINE);
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void blankOrNonStringSummaryIsAnInvalidArgument() {
        for (Object summary : List.of("", "   ", " \t\n", 42)) {
            CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", summary));

            assertThat(isError(result)).as("summary %s", summary).isTrue();
            assertThat(text(result)).as("summary %s", summary).isEqualTo(SUMMARY_BLANK);
        }
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void blankSummaryIsRejectedEvenWithAValidDescription() {
        // A given part is never dropped silently, or the model would think the summary changed.
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "", "description", "New description"));

        assertThat(text(result)).isEqualTo(SUMMARY_BLANK);
        assertThat(client.calls()).isEmpty();
    }

    // --- input: description --------------------------------------------------------------

    @Test
    void passesTheDescriptionAsWritten() {
        // Not stripped: Markdown and line breaks, leading and trailing whitespace included.
        String description = "  Steps on **Safari 18**:\n\n- open login\r\n- submit  \n";

        tool.handle(Map.of("issueId", "DEMO-13", "description", description));

        assertThat(client.calls()).containsExactly("updateSummaryAndDescription(\"DEMO-13\", null, \"" + description + "\")");
    }

    @Test
    void blankOrNonStringDescriptionIsAnInvalidArgument() {
        // A blank description would wipe the old one; that is never what was meant.
        for (Object description : List.of("", "   ", " \t\n", 42)) {
            CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "description", description));

            assertThat(isError(result)).as("description %s", description).isTrue();
            assertThat(text(result)).as("description %s", description).isEqualTo(DESCRIPTION_BLANK);
        }
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void blankDescriptionIsRejectedEvenWithAValidSummary() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary", "description", " "));

        assertThat(text(result)).isEqualTo(DESCRIPTION_BLANK);
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void summaryIsCheckedBeforeDescription() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "", "description", ""));

        assertThat(text(result)).isEqualTo(SUMMARY_BLANK);
    }

    // --- input: nothing to change --------------------------------------------------------

    @Test
    void neitherPartIsAnInvalidArgument() {
        Map<String, Object> bothNull = new HashMap<>();
        bothNull.put("issueId", "DEMO-13");
        bothNull.put("summary", null);
        bothNull.put("description", null);

        for (Map<String, Object> arguments : List.of(Map.<String, Object>of("issueId", "DEMO-13"), bothNull)) {
            CallToolResult result = tool.handle(arguments);

            assertThat(isError(result)).as("arguments %s", arguments).isTrue();
            assertThat(text(result)).as("arguments %s", arguments).isEqualTo(NOTHING_TO_CHANGE);
        }
        assertThat(client.calls()).isEmpty();
    }

    // --- output --------------------------------------------------------------------------

    @Test
    void namesTheSummaryWhenOnlyItWasSent() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary"));

        assertThat(isError(result)).isFalse();
        assertThat(text(result)).isEqualTo("""
                Updated the summary of DEMO-13.
                https://youtrack.example.com/issue/DEMO-13""");
    }

    @Test
    void namesTheDescriptionWhenOnlyItWasSent() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "description", "New description"));

        assertThat(text(result)).isEqualTo("""
                Updated the description of DEMO-13.
                https://youtrack.example.com/issue/DEMO-13""");
    }

    @Test
    void namesBothWhenBothWereSent() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary",
                "description", "New description"));

        assertThat(text(result)).isEqualTo("""
                Updated the summary and description of DEMO-13.
                https://youtrack.example.com/issue/DEMO-13""");
    }

    @Test
    void showsTheIdYouTrackReturnedNotTheOneSent() {
        CallToolResult result = tool.handle(Map.of("issueId", "demo-13", "summary", "New summary"));

        assertThat(text(result)).startsWith("Updated the summary of DEMO-13.\n");
    }

    @Test
    void neverEchoesTheTextSent() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "Secret summary",
                "description", "Secret description"));

        assertThat(text(result)).doesNotContain("Secret");
    }

    @Test
    void returnsASingleTextBlock() {
        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary"));

        assertThat(result.content()).hasSize(1).first().isInstanceOf(TextContent.class);
    }

    // --- errors --------------------------------------------------------------------------

    @Test
    void notFoundNamesTheIdAndPointsToSearch() {
        client.failingWith(new IssueNotFoundException("DEMO-99999"));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-99999", "summary", "New summary"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(
                "Issue DEMO-99999 was not found. Check the ID, or use search_issues to find it.");
    }

    @Test
    void rejectionCarriesYouTracksText() {
        client.failingWith(new RequestRejectedException("Property Issue.summary is invalid"));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack rejected the change: Property Issue.summary is invalid.");
    }

    @Test
    void rejectionAlreadyEndingInAPeriodGetsNoSecondOne() {
        // Synthetic: the captured text with a period added.
        client.failingWith(new RequestRejectedException("Property Issue.summary is invalid."));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary"));

        assertThat(text(result)).isEqualTo("YouTrack rejected the change: Property Issue.summary is invalid.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Property Issue.summary is invalid?", "Property Issue.summary is invalid!"})
    void rejectionDoesNotAddAPeriodAfterAQuestionOrExclamationMark(String detail) {
        // Synthetic: the captured wording with its ending changed.
        client.failingWith(new RequestRejectedException(detail));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary"));

        assertThat(text(result)).isEqualTo("YouTrack rejected the change: " + detail);
    }

    @Test
    void rejectionDoesNotClaimNothingChanged() {
        // Not verified that a partly invalid update is all-or-nothing.
        client.failingWith(new RequestRejectedException("Property Issue.summary is invalid"));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary"));

        assertThat(text(result)).doesNotContainIgnoringCase("nothing");
    }

    @Test
    void authFailureBecomesTheSharedAuthText() {
        client.failingWith(new YouTrackAuthException("The token lacks permission for this request."));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack refused the request: the token is invalid or lacks access. "
                + "Tell the user; retrying won't help.");
    }

    @Test
    void unavailableSaysTheIssueMayHaveChangedAndToCheckBeforeRetrying() {
        // A failure after the POST was sent doesn't prove nothing changed.
        client.failingWith(new YouTrackUnavailableException("Could not reach YouTrack.", new IOException("reset")));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack could not be reached; the issue may have been updated. "
                + "Check it with get_issue before trying again.");
    }

    @Test
    void unexpectedExceptionBecomesAGenericErrorResult() {
        client.failingWith(new IllegalStateException("bug with internal detail"));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-13", "summary", "New summary"));

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
