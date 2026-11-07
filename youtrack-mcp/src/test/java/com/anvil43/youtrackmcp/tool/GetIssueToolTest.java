package com.anvil43.youtrackmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.anvil43.youtrackmcp.FakeYouTrackClient;
import com.anvil43.youtrackmcp.exception.IssueNotFoundException;
import com.anvil43.youtrackmcp.exception.YouTrackAuthException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.CustomField;
import com.anvil43.youtrackmcp.model.Issue;
import com.anvil43.youtrackmcp.model.IssueComment;
import com.anvil43.youtrackmcp.model.User;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Contract: design.md, "get_issue contract", "Read tools: v1 decisions" and "Errors". */
class GetIssueToolTest {

    private static final String ID_REQUIRED = "issueId is required. Pass an issue ID, for example \"ABC-123\".";

    private static final Instant CREATED = Instant.parse("2026-10-01T09:30:00Z");
    private static final Instant UPDATED = Instant.parse("2026-10-28T14:02:11Z");
    private static final Instant RESOLVED = Instant.parse("2026-10-28T14:02:11Z");
    private static final User JANE = new User("jdoe", "Jane Doe");
    private static final User JOHN = new User("jroe", "John Roe");

    private static final List<CustomField> FIELDS = List.of(
            new CustomField("State", "Fixed"),
            new CustomField("Assignee", null),
            new CustomField("Priority", "Major"),
            new CustomField("Fix versions", null));

    private final FakeYouTrackClient client = new FakeYouTrackClient();
    private final GetIssueTool tool = new GetIssueTool(client);

    // --- specification -------------------------------------------------------------------

    @Test
    void isNamedGetIssue() {
        assertThat(tool.specification().tool().name()).isEqualTo("get_issue");
    }

    @Test
    void descriptionQuotesTheExampleId() {
        assertThat(tool.specification().tool().description()).isEqualTo(
                "Returns one YouTrack issue by ID (for example \"ABC-123\"): its dates, every custom "
                        + "field, the description and all comments. Use search_issues to find IDs.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requiresAStringIssueIdWithNoPattern() {
        Map<String, Object> schema = tool.specification().tool().inputSchema();

        assertThat(schema).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsOnlyKeys("issueId");
        Map<String, Object> issueId = (Map<String, Object>) properties.get("issueId");
        assertThat(issueId).containsEntry("type", "string").doesNotContainKey("pattern");
        assertThat((List<String>) schema.get("required")).containsExactly("issueId");
    }

    @Test
    void specificationHandlerDelegatesToHandle() {
        client.withIssue(issue("Some text", List.of()));

        CallToolResult result = tool.specification().callHandler()
                .apply(null, new CallToolRequest("get_issue", Map.of("issueId", "DEMO-12")));

        assertThat(text(result)).startsWith("DEMO-12: Login fails on Safari\n");
    }

    // --- input ---------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"DEMO-12", "demo-12", "A-1", "A_B1-30", "Ab9-007"})
    void passesAValidIdThroughWithItsCaseUnchanged(String issueId) {
        client.withIssue(issue("Some text", List.of()));

        CallToolResult result = tool.handle(Map.of("issueId", issueId));

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(client.calls()).containsExactly("getIssue(\"" + issueId + "\")");
    }

    @Test
    void stripsSurroundingWhitespaceBeforeCheckingAndCalling() {
        client.withIssue(issue("Some text", List.of()));

        tool.handle(Map.of("issueId", "  DEMO-12 \t"));

        assertThat(client.calls()).containsExactly("getIssue(\"DEMO-12\")");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEMO 1", "DEMO-", "-1", "DEMO", "12", "1DEMO-2", "_DEMO-2", "DEMO-1a",
            "DEMO-1/comments", "DEMO-1?fields=x", "../DEMO-1", "DEMO-1#x", "DE MO-1", "DEMO--1"})
    void malformedIdIsAnInvalidArgumentQuotingTheStrippedValue(String issueId) {
        CallToolResult result = tool.handle(Map.of("issueId", " " + issueId + " "));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("\"" + issueId + "\" is not an issue ID. Issue IDs look like "
                + "\"ABC-123\"; use search_issues to find one.");
        assertThat(client.calls()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", " \t\n"})
    void blankIdIsAnInvalidArgumentAndTheClientIsNotCalled(String blank) {
        CallToolResult result = tool.handle(Map.of("issueId", blank));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(ID_REQUIRED);
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void missingNullOrNonStringIdIsAnInvalidArgument() {
        Map<String, Object> nullId = new HashMap<>();
        nullId.put("issueId", null);

        for (Map<String, Object> arguments : List.of(Map.<String, Object>of(), nullId, Map.<String, Object>of("issueId", 12))) {
            CallToolResult result = tool.handle(arguments);

            assertThat(isError(result)).as("arguments %s", arguments).isTrue();
            assertThat(text(result)).as("arguments %s", arguments).isEqualTo(ID_REQUIRED);
        }
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void nullArgumentsAreAnInvalidArgument() {
        CallToolResult result = tool.handle(null);

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(ID_REQUIRED);
        assertThat(client.calls()).isEmpty();
    }

    // --- output --------------------------------------------------------------------------

    @Test
    void rendersTheFullLayout() {
        client.withIssue(issue("Steps:\n1. Open Safari\n2. Log in", List.of(
                new IssueComment("Reproduced on 17.1.", Instant.parse("2026-10-02T10:00:00Z"), JANE),
                new IssueComment("Fixed in\nthe login form.", Instant.parse("2026-10-03T08:15:00Z"), JOHN))));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-12"));

        assertThat(isError(result)).isFalse();
        assertThat(text(result)).isEqualTo("""
                DEMO-12: Login fails on Safari
                Created: 2026-10-01T09:30:00Z
                Updated: 2026-10-28T14:02:11Z
                Resolved: 2026-10-28T14:02:11Z
                State: Fixed
                Priority: Major
                Unset: Assignee, Fix versions

                Description:
                Steps:
                1. Open Safari
                2. Log in

                Comments (2):
                Jane Doe (jdoe), 2026-10-02T10:00:00Z:
                Reproduced on 17.1.

                John Roe (jroe), 2026-10-03T08:15:00Z:
                Fixed in
                the login form.""");
    }

    @Test
    void openIssueHasNoResolvedLine() {
        client.withIssue(new Issue("DEMO-12", "Login fails on Safari", "Some text", CREATED, UPDATED, null,
                List.of(new CustomField("State", "Open")), List.of()));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12")))).startsWith("""
                DEMO-12: Login fails on Safari
                Created: 2026-10-01T09:30:00Z
                Updated: 2026-10-28T14:02:11Z
                State: Open

                """);
    }

    @Test
    void noUnsetLineWhenEveryFieldIsSet() {
        client.withIssue(new Issue("DEMO-12", "Login fails on Safari", "Some text", CREATED, UPDATED, null,
                List.of(new CustomField("State", "Open")), List.of()));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12")))).doesNotContain("Unset:");
    }

    @Test
    void blankFieldValuesAreListedAsUnset() {
        client.withIssue(new Issue("DEMO-12", "Blank values", "Some text", CREATED, UPDATED, null, List.of(
                new CustomField("Notes", ""),
                new CustomField("State", "Open"),
                new CustomField("Comment", "   "),
                new CustomField("Assignee", null)), List.of()));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12")))).startsWith("""
                DEMO-12: Blank values
                Created: 2026-10-01T09:30:00Z
                Updated: 2026-10-28T14:02:11Z
                State: Open
                Unset: Notes, Comment, Assignee

                """);
    }

    @Test
    void issueWithNoFieldsHasJustTheDates() {
        client.withIssue(new Issue("DEMO-12", "Bare", "Some text", CREATED, UPDATED, null, List.of(), List.of()));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12")))).isEqualTo("""
                DEMO-12: Bare
                Created: 2026-10-01T09:30:00Z
                Updated: 2026-10-28T14:02:11Z

                Description:
                Some text

                Comments: none.""");
    }

    @Test
    void nullSummaryIsShownAsEmpty() {
        client.withIssue(new Issue("DEMO-12", null, "Some text", CREATED, UPDATED, null, List.of(), List.of()));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12")))).startsWith("""
                DEMO-12:\s
                Created: 2026-10-01T09:30:00Z
                """);
    }

    @Test
    void datesAreTruncatedToSeconds() {
        client.withIssue(new Issue("DEMO-12", "Dates", "Some text",
                Instant.parse("2026-10-01T09:30:00.123Z"), Instant.parse("2026-10-28T14:02:11.999Z"),
                Instant.parse("2026-10-29T00:00:00.500Z"), List.of(),
                List.of(new IssueComment("Hi", Instant.parse("2026-10-02T10:00:00.001Z"), JANE))));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12"))))
                .contains("\nCreated: 2026-10-01T09:30:00Z\n")
                .contains("\nUpdated: 2026-10-28T14:02:11Z\n")
                .contains("\nResolved: 2026-10-29T00:00:00Z\n")
                .contains("\nJane Doe (jdoe), 2026-10-02T10:00:00Z:\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n"})
    void blankDescriptionSaysNone(String blank) {
        client.withIssue(issue(blank, List.of()));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12"))))
                .contains("\n\nDescription: none.\n\n")
                .doesNotContain("Description:\n");
    }

    @Test
    void nullDescriptionSaysNone() {
        client.withIssue(issue(null, List.of()));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12")))).contains("\n\nDescription: none.\n\n");
    }

    @Test
    void noCommentsSaysNone() {
        client.withIssue(issue("Some text", List.of()));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12")))).endsWith("\n\nComments: none.");
    }

    @Test
    void usesTheSingularForOneComment() {
        client.withIssue(issue("Some text", List.of(new IssueComment("Hi", CREATED, JANE))));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12")))).endsWith("""


                Comments (1):
                Jane Doe (jdoe), 2026-10-01T09:30:00Z:
                Hi""");
    }

    @Test
    void nullOrEmptyCommentTextSaysEmpty() {
        client.withIssue(issue("Some text", List.of(
                new IssueComment(null, CREATED, JANE),
                new IssueComment("", UPDATED, JOHN))));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12")))).endsWith("""
                Comments (2):
                Jane Doe (jdoe), 2026-10-01T09:30:00Z:
                (empty)

                John Roe (jroe), 2026-10-28T14:02:11Z:
                (empty)""");
    }

    @Test
    void whitespaceOnlyCommentTextIsKeptAsWritten() {
        client.withIssue(issue("Some text", List.of(new IssueComment("  \t", CREATED, JANE))));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12"))))
                .endsWith("Jane Doe (jdoe), 2026-10-01T09:30:00Z:\n  \t")
                .doesNotContain("(empty)");
    }

    @Test
    void keepsLineBreaksInTheDescriptionAndComments() {
        client.withIssue(issue("line one\n\nline three", List.of(new IssueComment("a\nb", CREATED, JANE))));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12"))))
                .contains("\nDescription:\nline one\n\nline three\n\n")
                .endsWith("Jane Doe (jdoe), 2026-10-01T09:30:00Z:\na\nb");
    }

    @Test
    void flattensLineBreaksInSingleLineSlots() {
        client.withIssue(new Issue("DEMO-12", "Two\nline\r\nsummary", "Some text", CREATED, UPDATED, null,
                List.of(new CustomField("Notes", "first\r\nsecond"), new CustomField("Odd\rname", null)),
                List.of(new IssueComment("Hi", CREATED, new User("jdoe", "Jane\nDoe")))));

        assertThat(text(tool.handle(Map.of("issueId", "DEMO-12")))).isEqualTo("""
                DEMO-12: Two line summary
                Created: 2026-10-01T09:30:00Z
                Updated: 2026-10-28T14:02:11Z
                Notes: first second
                Unset: Odd name

                Description:
                Some text

                Comments (1):
                Jane Doe (jdoe), 2026-10-01T09:30:00Z:
                Hi""");
    }

    @Test
    void returnsASingleTextBlock() {
        client.withIssue(issue("Some text", List.of()));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-12"));

        assertThat(result.content()).hasSize(1).first().isInstanceOf(TextContent.class);
    }

    // --- errors --------------------------------------------------------------------------

    @Test
    void notFoundNamesTheIdAndPointsToSearch() {
        client.failingWith(new IssueNotFoundException("DEMO-99999"));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-99999"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(
                "Issue DEMO-99999 was not found. Check the ID, or use search_issues to find it.");
    }

    @Test
    void authFailureBecomesAnErrorResult() {
        client.failingWith(new YouTrackAuthException("The token lacks permission for this request."));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-12"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack refused the request: the token is invalid or lacks access. "
                + "Tell the user; retrying won't help.");
    }

    @Test
    void unavailableBecomesAnErrorResult() {
        client.failingWith(new YouTrackUnavailableException("Could not reach YouTrack.", new IOException("refused")));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-12"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack could not be reached. Tell the user; try again later.");
    }

    @Test
    void unexpectedExceptionBecomesAGenericErrorResult() {
        client.failingWith(new IllegalStateException("bug with internal detail"));

        CallToolResult result = tool.handle(Map.of("issueId", "DEMO-12"));

        assertThat(isError(result)).isTrue();
        assertThat(text(result))
                .isEqualTo("Internal error in youtrack-mcp; see the server log.")
                .doesNotContain("internal detail");
    }

    // --- helpers -------------------------------------------------------------------------

    /** DEMO-12, resolved, with {@link #FIELDS} and the given description and comments. */
    private static Issue issue(String description, List<IssueComment> comments) {
        return new Issue("DEMO-12", "Login fails on Safari", description, CREATED, UPDATED, RESOLVED,
                FIELDS, comments);
    }

    private static boolean isError(CallToolResult result) {
        return Boolean.TRUE.equals(result.isError());
    }

    private static String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((TextContent) result.content().getFirst()).text();
    }
}
