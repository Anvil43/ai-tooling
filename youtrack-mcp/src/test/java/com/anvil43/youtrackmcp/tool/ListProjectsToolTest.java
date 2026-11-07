package com.anvil43.youtrackmcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.anvil43.youtrackmcp.FakeYouTrackClient;
import com.anvil43.youtrackmcp.exception.YouTrackAuthException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.Project;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Contract: design.md, "list_projects contract" and "Errors". */
class ListProjectsToolTest {

    private static final Project YTMCP = new Project("YTMCP", "YouTrack MCP server");
    private static final Project WEB = new Project("WEB", "Website");

    private final FakeYouTrackClient client = new FakeYouTrackClient();
    private final ListProjectsTool tool = new ListProjectsTool(client);

    // --- specification -------------------------------------------------------------------

    @Test
    void isNamedListProjects() {
        assertThat(tool.specification().tool().name()).isEqualTo("list_projects");
    }

    @Test
    void descriptionTellsTheModelNotToGuessTheProject() {
        assertThat(tool.specification().tool().description()).isEqualTo(
                "Lists the YouTrack projects visible to this server's token, as KEY: Name. "
                        + "Archived projects are excluded. Use the key in search_issues queries "
                        + "(\"project: <KEY>\") and when creating issues. Never guess which project the "
                        + "user means: if they haven't named one, show them this list and ask.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsAnOptionalStringNameArgument() {
        Map<String, Object> schema = tool.specification().tool().inputSchema();

        assertThat(schema).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsOnlyKeys("name");
        assertThat((Map<String, Object>) properties.get("name")).containsEntry("type", "string");
        Object required = schema.get("required");
        if (required != null) {
            assertThat((List<String>) required).doesNotContain("name");
        }
    }

    @Test
    void specificationHandlerDelegatesToHandle() {
        client.withProjects(YTMCP);
        SyncToolSpecification specification = tool.specification();

        CallToolResult result = specification.callHandler()
                .apply(null, new CallToolRequest("list_projects", Map.of()));

        assertThat(text(result)).startsWith("1 project:");
    }

    @Test
    void specificationHandlerTreatsNullArgumentsAsNone() {
        client.withProjects(YTMCP);
        Tool definition = tool.specification().tool();

        CallToolResult result = tool.specification().callHandler()
                .apply(null, new CallToolRequest(definition.name(), null));

        assertThat(isError(result)).isFalse();
        assertThat(text(result)).startsWith("1 project:");
    }

    // --- output --------------------------------------------------------------------------

    @Test
    void listsProjectsInTheClientsOrderWithTheAskLine() {
        client.withProjects(YTMCP, WEB);

        CallToolResult result = tool.handle(Map.of());

        assertThat(isError(result)).isFalse();
        assertThat(text(result)).isEqualTo("""
                2 projects:
                YTMCP: YouTrack MCP server
                WEB: Website
                If the user hasn't said which of these to use, ask them.""");
    }

    @Test
    void usesTheSingularForOneProject() {
        client.withProjects(WEB);

        assertThat(text(tool.handle(Map.of()))).isEqualTo("""
                1 project:
                WEB: Website
                If the user hasn't said which of these to use, ask them.""");
    }

    @Test
    void printsKeysAndNamesExactlyAsTheClientReturnsThem() {
        client.withProjects(new Project("Ab1", "mixed Case name"));

        assertThat(text(tool.handle(Map.of()))).contains("\nAb1: mixed Case name\n");
    }

    @Test
    void returnsASingleTextBlock() {
        client.withProjects(YTMCP, WEB);

        CallToolResult result = tool.handle(Map.of());

        assertThat(result.content()).hasSize(1).first().isInstanceOf(TextContent.class);
    }

    @Test
    void saysSoWhenNoProjectsAreVisible() {
        CallToolResult result = tool.handle(Map.of());

        assertThat(isError(result)).isFalse();
        assertThat(text(result)).isEqualTo("No projects are visible to this token.");
    }

    // --- name filter ---------------------------------------------------------------------

    @Test
    void filtersByCaseInsensitiveSubstringOfTheName() {
        client.withProjects(YTMCP, WEB);

        assertThat(text(tool.handle(Map.of("name", "mcp")))).isEqualTo("""
                1 project:
                YTMCP: YouTrack MCP server
                If the user hasn't said which of these to use, ask them.""");
    }

    @Test
    void filterMatchesTheNameNotTheKey() {
        client.withProjects(new Project("ABC", "Website"));

        assertThat(text(tool.handle(Map.of("name", "abc")))).isEqualTo("No projects matched \"abc\".");
    }

    @Test
    void saysSoWhenTheFilterMatchesNothing() {
        client.withProjects(YTMCP, WEB);

        CallToolResult result = tool.handle(Map.of("name", "foo"));

        assertThat(isError(result)).isFalse();
        assertThat(text(result)).isEqualTo("No projects matched \"foo\".");
    }

    @Test
    void stripsSurroundingWhitespaceFromTheFilter() {
        client.withProjects(YTMCP, WEB);

        assertThat(text(tool.handle(Map.of("name", "  mcp \t")))).isEqualTo("""
                1 project:
                YTMCP: YouTrack MCP server
                If the user hasn't said which of these to use, ask them.""");
    }

    @Test
    void noMatchMessageQuotesTheStrippedFilter() {
        client.withProjects(YTMCP, WEB);

        assertThat(text(tool.handle(Map.of("name", " foo ")))).isEqualTo("No projects matched \"foo\".");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankFilterMeansNoFilter(String blank) {
        client.withProjects(YTMCP, WEB);

        assertThat(text(tool.handle(Map.of("name", blank)))).startsWith("2 projects:");
    }

    @Test
    void blankFilterWithNoProjectsSaysNoneAreVisible() {
        assertThat(text(tool.handle(Map.of("name", "   ")))).isEqualTo("No projects are visible to this token.");
    }

    @Test
    void nonStringFilterMeansNoFilter() {
        client.withProjects(YTMCP, WEB);

        CallToolResult result = tool.handle(Map.of("name", 42));

        assertThat(isError(result)).isFalse();
        assertThat(text(result)).startsWith("2 projects:");
    }

    @Test
    void missingFilterMeansNoFilter() {
        client.withProjects(YTMCP, WEB);
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("name", null);

        assertThat(text(tool.handle(arguments))).startsWith("2 projects:");
    }

    @Test
    void callsTheClientOncePerCall() {
        client.withProjects(YTMCP);

        tool.handle(Map.of("name", "mcp"));

        assertThat(client.calls()).containsExactly("listProjects");
    }

    // --- errors --------------------------------------------------------------------------

    @Test
    void authFailureBecomesAnErrorResult() {
        client.failingWith(new YouTrackAuthException("401 Unauthorized"));

        CallToolResult result = tool.handle(Map.of());

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo(
                "YouTrack refused the request: the token is invalid or lacks access. "
                        + "Tell the user; retrying won't help.");
    }

    @Test
    void unavailableBecomesAnErrorResult() {
        client.failingWith(new YouTrackUnavailableException("connection refused", new IOException("refused")));

        CallToolResult result = tool.handle(Map.of());

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).isEqualTo("YouTrack could not be reached. Tell the user; try again later.");
    }

    @Test
    void unexpectedExceptionBecomesAGenericErrorResult() {
        client.failingWith(new IllegalStateException("bug with internal detail"));

        CallToolResult result = tool.handle(Map.of());

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
