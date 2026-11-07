package com.anvil43.youtrackmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.anvil43.youtrackmcp.FakeYouTrackClient;
import com.anvil43.youtrackmcp.exception.InvalidQueryException;
import com.anvil43.youtrackmcp.exception.YouTrackAuthException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.CustomField;
import com.anvil43.youtrackmcp.model.IssueSummary;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Contract: design.md, "search_issues contract", "Read tools: v1 decisions" and "Errors". */
class SearchIssuesToolTest {

    private static final String HEADER_NOTE = " (fields without a value are left out):";
    private static final String QUERY_REQUIRED = "query is required. Pass a YouTrack search query, for example "
            + "\"project: <KEY> #Unresolved\", using a project key from list_projects.";

    private static final IssueSummary DEMO_1 = new IssueSummary("DEMO-1", "Sample issue 1", List.of(
            new CustomField("Priority", "Normal"),
            new CustomField("Type", "Epic"),
            new CustomField("State", "Fixed"),
            new CustomField("Assignee", "Jane Doe"),
            new CustomField("Subsystem", null),
            new CustomField("Fix versions", null)));
    private static final IssueSummary DEMO_6 = new IssueSummary("DEMO-6", "Sample issue 6", List.of(
            new CustomField("Priority", "Normal"),
            new CustomField("Type", "Task"),
            new CustomField("State", "Submitted"),
            new CustomField("Assignee", null)));

    private final FakeYouTrackClient client = new FakeYouTrackClient();
    private final SearchIssuesTool tool = new SearchIssuesTool(client);

    // --- specification -------------------------------------------------------------------

    @Test
    void isNamedSearchIssues() {
        assertThat(tool.specification().tool().name()).isEqualTo("search_issues");
    }

    @Test
    void descriptionGivesQuotedExamplesAndSaysNotToGuess() {
        assertThat(tool.specification().tool().description()).isEqualTo(
                "Searches YouTrack issues with a YouTrack search query, for example "
                        + "\"project: <KEY> #Unresolved\" or \"project: <KEY> assignee: me\". "
                        + "Queries usually start with project: <KEY>. If the user hasn't named a project "
                        + "and context doesn't say, ask them rather than guessing; use list_projects to "
                        + "show the options. Custom field names and values differ between projects. If you "
                        + "don't know a project's fields yet, run a broad query such as \"project: <KEY>\" "
                        + "first and take names and values from the results rather than guessing. Returns "
                        + "up to 50 issues, one per line, in YouTrack's default order; add a sort by: "
                        + "clause to choose another, for example \"project: <KEY> sort by: created asc\". "
                        + "Use get_issue for an issue's description and comments.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requiresAStringQueryArgumentAndNothingElse() {
        Map<String, Object> schema = tool.specification().tool().inputSchema();

        assertThat(schema).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsOnlyKeys("query");
        assertThat((Map<String, Object>) properties.get("query")).containsEntry("type", "string");
        assertThat((List<String>) schema.get("required")).containsExactly("query");
    }

    @Test
    void specificationHandlerDelegatesToHandle() {
        client.withIssueSummaries(List.of(DEMO_6));

        CallToolResult result = tool.specification().callHandler()
                .apply(null, new CallToolRequest("search_issues", Map.of("query", "project: DEMO")));

        assertThat(text(result)).startsWith("1 issue" + HEADER_NOTE);
    }

    // --- input ---------------------------------------------------------------------------

    @Test
    void passesTheStrippedQueryAndAsksForOneMoreThanTheCap() {
        tool.handle(Map.of("query", "  project: DEMO #Unresolved \t"));

        assertThat(client.calls()).containsExactly("searchIssues(\"project: DEMO #Unresolved\", 51)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", " \t\n"})
    void blankQueryIsAnInvalidArgumentAndTheClientIsNotCalled(String blank) {
        CallToolResult result = tool.handle(Map.of("query", blank));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(QUERY_REQUIRED);
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void missingNullOrNonStringQueryIsAnInvalidArgument() {
        Map<String, Object> nullQuery = new HashMap<>();
        nullQuery.put("query", null);

        for (Map<String, Object> arguments : List.of(Map.<String, Object>of(), nullQuery, Map.<String, Object>of("query", 42))) {
            CallToolResult result = tool.handle(arguments);

            assertThat(isError(result)).as("arguments %s", arguments).isTrue();
            assertThat(text(result)).as("arguments %s", arguments).isEqualTo(QUERY_REQUIRED);
        }
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void nullArgumentsAreAnInvalidArgument() {
        CallToolResult result = tool.handle(null);

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(QUERY_REQUIRED);
        assertThat(client.calls()).isEmpty();
    }

    // --- output --------------------------------------------------------------------------

    @Test
    void listsIssuesInTheClientsOrderWithOnlyTheSetFields() {
        client.withIssueSummaries(List.of(DEMO_1, DEMO_6));

        CallToolResult result = tool.handle(Map.of("query", "project: DEMO"));

        assertThat(isError(result)).isFalse();
        assertThat(text(result)).isEqualTo("""
                2 issues (fields without a value are left out):
                DEMO-1: Sample issue 1 | Priority: Normal | Type: Epic | State: Fixed | Assignee: Jane Doe
                DEMO-6: Sample issue 6 | Priority: Normal | Type: Task | State: Submitted""");
    }

    @Test
    void usesTheSingularForOneIssue() {
        client.withIssueSummaries(List.of(DEMO_6));

        assertThat(text(tool.handle(Map.of("query", "project: DEMO")))).isEqualTo("""
                1 issue (fields without a value are left out):
                DEMO-6: Sample issue 6 | Priority: Normal | Type: Task | State: Submitted""");
    }

    @Test
    void issueWithNoSetFieldsIsJustIdAndSummary() {
        client.withIssueSummaries(List.of(
                new IssueSummary("DEMO-7", "Nothing set", List.of(new CustomField("Assignee", null))),
                new IssueSummary("DEMO-8", "No fields at all", List.of())));

        assertThat(text(tool.handle(Map.of("query", "project: DEMO")))).isEqualTo("""
                2 issues (fields without a value are left out):
                DEMO-7: Nothing set
                DEMO-8: No fields at all""");
    }

    @Test
    void blankFieldValuesAreLeftOut() {
        client.withIssueSummaries(List.of(new IssueSummary("DEMO-7", "Blank values", List.of(
                new CustomField("Notes", ""),
                new CustomField("State", "Open"),
                new CustomField("Comment", "   ")))));

        assertThat(text(tool.handle(Map.of("query", "project: DEMO")))).isEqualTo("""
                1 issue (fields without a value are left out):
                DEMO-7: Blank values | State: Open""");
    }

    @Test
    void nullSummaryIsShownAsEmpty() {
        client.withIssueSummaries(List.of(
                new IssueSummary("DEMO-7", null, List.of(new CustomField("State", "Open"))),
                new IssueSummary("DEMO-8", null, List.of())));

        assertThat(text(tool.handle(Map.of("query", "project: DEMO")))).isEqualTo("""
                2 issues (fields without a value are left out):
                DEMO-7:  | State: Open
                DEMO-8:\s""");
    }

    @Test
    void noMatchesIsAnExplicitNonErrorText() {
        CallToolResult result = tool.handle(Map.of("query", "project: DEMO Stat: Open"));

        assertThat(isError(result)).isFalse();
        assertThat(text(result))
                .isEqualTo("0 issues matched. If the query names fields or values, check their spelling.");
    }

    @Test
    void exactlyFiftyIssuesHasNoOverflowLine() {
        client.withIssueSummaries(issues(50));

        String text = text(tool.handle(Map.of("query", "project: DEMO")));

        assertThat(text).startsWith("50 issues" + HEADER_NOTE + "\n").endsWith("\nDEMO-50: Issue 50");
        assertThat(text.lines()).hasSize(51);
    }

    @Test
    void fiftyOneIssuesShowsTheFirstFiftyAndTheOverflowLine() {
        client.withIssueSummaries(issues(51));

        String text = text(tool.handle(Map.of("query", "project: DEMO")));

        assertThat(text)
                .startsWith("50 issues" + HEADER_NOTE + "\nDEMO-1: Issue 1\n")
                .endsWith("\nDEMO-50: Issue 50\n"
                        + "Showing the first 50 matching issues. Refine the query to narrow the results.")
                .doesNotContain("DEMO-51");
        assertThat(text.lines()).hasSize(52);
    }

    @Test
    void flattensLineBreaksInSummariesAndFieldValues() {
        client.withIssueSummaries(List.of(new IssueSummary("DEMO-9", "Two\nline\r\nsummary\rhere",
                List.of(new CustomField("Notes", "first\r\nsecond"), new CustomField("Multi\nname", "x")))));

        assertThat(text(tool.handle(Map.of("query", "project: DEMO")))).isEqualTo("""
                1 issue (fields without a value are left out):
                DEMO-9: Two line summary here | Notes: first second | Multi name: x""");
    }

    @Test
    void passesOtherCharactersThroughIncludingTheSeparator() {
        client.withIssueSummaries(List.of(new IssueSummary("DEMO-10", "Build | deploy  script",
                List.of(new CustomField("Fix versions", "1.0, 1.1")))));

        assertThat(text(tool.handle(Map.of("query", "project: DEMO"))))
                .endsWith("\nDEMO-10: Build | deploy  script | Fix versions: 1.0, 1.1");
    }

    @Test
    void returnsASingleTextBlock() {
        client.withIssueSummaries(List.of(DEMO_1, DEMO_6));

        CallToolResult result = tool.handle(Map.of("query", "project: DEMO"));

        assertThat(result.content()).hasSize(1).first().isInstanceOf(TextContent.class);
    }

    // --- errors --------------------------------------------------------------------------

    @Test
    void invalidQueryCarriesYouTracksTextAndAsksForAFix() {
        client.failingWith(new InvalidQueryException(
                "Can't parse search query, please check and update query syntax (Value expected for field project.)"));

        CallToolResult result = tool.handle(Map.of("query", "project:"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack rejected the query: Can't parse search query, please check "
                + "and update query syntax (Value expected for field project.). Fix the query and try again.");
    }

    @Test
    void invalidQueryDoesNotDoubleAPeriodYouTrackAlreadyWrote() {
        client.failingWith(new InvalidQueryException("Value expected for field project."));

        assertThat(text(tool.handle(Map.of("query", "project:")))).isEqualTo(
                "YouTrack rejected the query: Value expected for field project. Fix the query and try again.");
    }

    // Synthetic: no real YouTrack message ending in ? or ! has been seen; the real wording
    // with its ending changed.
    @ParameterizedTest
    @ValueSource(strings = {"Value expected for field project?", "Value expected for field project!"})
    void invalidQueryDoesNotAddAPeriodAfterAQuestionOrExclamationMark(String detail) {
        client.failingWith(new InvalidQueryException(detail));

        assertThat(text(tool.handle(Map.of("query", "project:")))).isEqualTo(
                "YouTrack rejected the query: " + detail + " Fix the query and try again.");
    }

    @Test
    void authFailureBecomesAnErrorResult() {
        client.failingWith(new YouTrackAuthException("YouTrack rejected the token."));

        CallToolResult result = tool.handle(Map.of("query", "project: DEMO"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack refused the request: the token is invalid or lacks access. "
                + "Tell the user; retrying won't help.");
    }

    @Test
    void unavailableBecomesAnErrorResult() {
        client.failingWith(new YouTrackUnavailableException("Could not reach YouTrack.", new IOException("refused")));

        CallToolResult result = tool.handle(Map.of("query", "project: DEMO"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack could not be reached. Tell the user; try again later.");
    }

    @Test
    void unexpectedExceptionBecomesAGenericErrorResult() {
        client.failingWith(new IllegalStateException("bug with internal detail"));

        CallToolResult result = tool.handle(Map.of("query", "project: DEMO"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result))
                .isEqualTo("Internal error in youtrack-mcp; see the server log.")
                .doesNotContain("internal detail");
    }

    // --- helpers -------------------------------------------------------------------------

    /** DEMO-1 "Issue 1" through DEMO-n "Issue n", each with no custom fields. */
    private static List<IssueSummary> issues(int count) {
        List<IssueSummary> issues = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            issues.add(new IssueSummary("DEMO-" + i, "Issue " + i, List.of()));
        }
        return issues;
    }

    private static boolean isError(CallToolResult result) {
        return Boolean.TRUE.equals(result.isError());
    }

    private static String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((TextContent) result.content().getFirst()).text();
    }
}
