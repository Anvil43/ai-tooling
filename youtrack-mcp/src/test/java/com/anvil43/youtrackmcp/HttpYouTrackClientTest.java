package com.anvil43.youtrackmcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anvil43.youtrackmcp.exception.InvalidQueryException;
import com.anvil43.youtrackmcp.exception.IssueNotFoundException;
import com.anvil43.youtrackmcp.exception.RequestRejectedException;
import com.anvil43.youtrackmcp.exception.YouTrackAuthException;
import com.anvil43.youtrackmcp.exception.YouTrackException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.AddedComment;
import com.anvil43.youtrackmcp.model.CreatedIssue;
import com.anvil43.youtrackmcp.model.CustomField;
import com.anvil43.youtrackmcp.model.Issue;
import com.anvil43.youtrackmcp.model.IssueComment;
import com.anvil43.youtrackmcp.model.IssueLink;
import com.anvil43.youtrackmcp.model.IssueSummary;
import com.anvil43.youtrackmcp.model.Project;
import com.anvil43.youtrackmcp.model.UpdatedIssue;
import com.anvil43.youtrackmcp.model.User;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Fixtures in src/test/resources/youtrack are sanitized copies of real YouTrack responses, except
 * issue-value-shapes.json, which is hand-built from YouTrack's OpenAPI spec for value shapes the
 * captured project doesn't have.
 */
class HttpYouTrackClientTest {

    @RegisterExtension
    static WireMockExtension youTrack = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String TOKEN = "perm-client-test-token";
    private static final String SEARCH_FIELDS =
            "idReadable,summary,customFields(name,value(name,login,fullName,presentation,text))";
    private static final String ISSUE_FIELDS = SEARCH_FIELDS
            + ",description,created,updated,resolved,comments(text,created,author(login,fullName))"
            + ",links(direction,linkType(name,sourceToTarget,targetToSource),issues(idReadable,summary))";
    private static final String RELATES =
            "{\"name\":\"Relates\",\"sourceToTarget\":\"relates to\",\"targetToSource\":\"\",\"$type\":\"IssueLinkType\"}";

    private HttpYouTrackClient client;
    private ListAppender<ILoggingEvent> log;
    private Logger clientLogger;

    @BeforeEach
    void setUp() {
        client = clientFor(youTrack.baseUrl());
        clientLogger = (Logger) LoggerFactory.getLogger(HttpYouTrackClient.class);
        log = new ListAppender<>();
        log.start();
        clientLogger.addAppender(log);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(log);
    }

    // --- listProjects --------------------------------------------------------------------

    @Nested
    class ListProjects {

        @Test
        void sendsTheProjectsRequestWithTokenAndFields() {
            youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects")).willReturn(okJson(fixture("projects.json"))));

            client.listProjects();

            youTrack.verify(getRequestedFor(urlPathEqualTo("/api/admin/projects"))
                    .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                    .withHeader("Accept", equalTo("application/json"))
                    .withQueryParam("fields", equalTo("shortName,name,archived"))
                    .withQueryParam("$top", equalTo("500")));
        }

        @Test
        void dropsArchivedProjectsAndKeepsOrder() {
            youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects")).willReturn(okJson(fixture("projects.json"))));

            assertThat(client.listProjects()).containsExactly(
                    new Project("DEMO", "Demo Project"),
                    new Project("WEB", "Website"));
        }

        @Test
        void toleratesATrailingSlashOnTheBaseUrl() {
            youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects")).willReturn(okJson(fixture("projects.json"))));

            List<Project> projects = clientFor(youTrack.baseUrl() + "/").listProjects();

            assertThat(projects).hasSize(2);
        }

        @Test
        void keepsAPathPrefixOnTheBaseUrl() {
            // Self-hosted YouTrack often lives under a path; URI.resolve would drop it.
            youTrack.stubFor(get(urlPathEqualTo("/youtrack/api/admin/projects")).willReturn(okJson(fixture("projects.json"))));

            List<Project> projects = clientFor(youTrack.baseUrl() + "/youtrack").listProjects();

            assertThat(projects).hasSize(2);
        }

        @Test
        void returnsAnEmptyListWhenNoProjectsAreVisible() {
            youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects")).willReturn(okJson("[]")));

            assertThat(client.listProjects()).isEmpty();
        }
    }

    // --- searchIssues --------------------------------------------------------------------

    @Nested
    class SearchIssues {

        @Test
        void sendsTheQueryFieldsAndLimit() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(okJson("[]")));

            client.searchIssues("project: DEMO #Unresolved", 51);

            youTrack.verify(getRequestedFor(urlPathEqualTo("/api/issues"))
                    .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                    .withQueryParam("query", equalTo("project: DEMO #Unresolved"))
                    .withQueryParam("fields", equalTo(SEARCH_FIELDS))
                    .withQueryParam("$top", equalTo("51")));
        }

        @Test
        void encodesSpacesInTheQueryAsPercent20() {
            // WireMock decodes '+' and '%20' alike, so check the raw URL the client sent.
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(okJson("[]")));

            client.searchIssues("project: DEMO #Unresolved", 51);

            assertThat(youTrack.getAllServeEvents().getFirst().getRequest().getUrl())
                    .contains("query=project%3A%20DEMO%20%23Unresolved")
                    .doesNotContain("+");
        }

        @Test
        void mapsEachIssueWithItsCustomFieldsInOrder() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(okJson(fixture("search.json"))));

            List<IssueSummary> issues = client.searchIssues("project: DEMO", 51);

            assertThat(issues).hasSize(6);
            assertThat(issues.getFirst()).isEqualTo(new IssueSummary("DEMO-1", "Sample issue 1", List.of(
                    new CustomField("Priority", "Normal"),
                    new CustomField("Type", "Epic"),
                    new CustomField("State", "Fixed"),
                    new CustomField("Assignee", "Jane Doe"),
                    new CustomField("Subsystem", null),
                    new CustomField("Fix versions", null),
                    new CustomField("Affected versions", null),
                    new CustomField("Fixed in build", null))));
            assertThat(issues).extracting(IssueSummary::issueId)
                    .containsExactly("DEMO-1", "DEMO-6", "DEMO-5", "DEMO-4", "DEMO-3", "DEMO-2");
        }

        @Test
        void unsetUserFieldIsNull() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(okJson(fixture("search.json"))));

            IssueSummary second = client.searchIssues("project: DEMO", 51).get(1);

            assertThat(second.customFields()).contains(new CustomField("Assignee", null));
        }

        @Test
        void returnsAnEmptyListWhenNothingMatches() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(okJson("[]")));

            assertThat(client.searchIssues("Stat: Open", 51)).isEmpty();
        }

        @Test
        void nullQueryIsAProgrammingErrorAndSendsNoRequest() {
            assertThatThrownBy(() -> client.searchIssues(null, 51))
                    .isInstanceOf(NullPointerException.class)
                    .message().containsIgnoringCase("query");
            assertThat(youTrack.getAllServeEvents()).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void limitBelowOneIsAProgrammingErrorAndSendsNoRequest(int limit) {
            assertThatThrownBy(() -> client.searchIssues("project: DEMO", limit))
                    .isInstanceOf(IllegalArgumentException.class)
                    .message().containsIgnoringCase("limit");
            assertThat(youTrack.getAllServeEvents()).isEmpty();
        }
    }

    // --- getIssue ------------------------------------------------------------------------

    @Nested
    class GetIssue {

        @Test
        void requestsTheIssueByIdWithTheFullFields() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues/DEMO-1")).willReturn(okJson(fixture("issue.json"))));

            client.getIssue("DEMO-1");

            youTrack.verify(getRequestedFor(urlPathEqualTo("/api/issues/DEMO-1"))
                    .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                    .withQueryParam("fields", equalTo(ISSUE_FIELDS)));
        }

        @Test
        void mapsTheFullIssue() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues/DEMO-1")).willReturn(okJson(fixture("issue.json"))));

            Issue issue = client.getIssue("DEMO-1");

            assertThat(issue).isEqualTo(new Issue(
                    "DEMO-1",
                    "Sample issue 1",
                    "Sample description.",
                    Instant.ofEpochMilli(1793046012787L),
                    Instant.ofEpochMilli(1793308356048L),
                    Instant.ofEpochMilli(1793308347870L),
                    List.of(
                            new CustomField("Priority", "Normal"),
                            new CustomField("Type", "Epic"),
                            new CustomField("State", "Fixed"),
                            new CustomField("Assignee", "Jane Doe"),
                            new CustomField("Subsystem", null),
                            new CustomField("Fix versions", null),
                            new CustomField("Affected versions", null),
                            new CustomField("Fixed in build", null)),
                    List.of(new IssueComment(
                            "Sample comment.",
                            Instant.ofEpochMilli(1793308356048L),
                            new User("jdoe", "Jane Doe"))),
                    // YouTrack's order, link types as returned and issues as listed: not by ID.
                    List.of(
                            new IssueLink("relates to", "DEMO-4", "Sample issue 4"),
                            new IssueLink("parent for", "DEMO-3", "Sample issue 3"),
                            new IssueLink("parent for", "DEMO-2", "Sample issue 2"),
                            new IssueLink("subtask of", "DEMO-9", "Sample issue 9"))));
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "true       | true",
                "[1, 2]     | 1, 2",
                "[\"a\", 3] | a, 3"})
        void rendersPrimitivesAtTopLevelAndInsideArrays(String value, String rendered) {
            // Contract: primitives render as is, and array elements render like top-level values.
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(okJson(
                    "[{\"idReadable\":\"DEMO-1\",\"summary\":\"Primitives\",\"customFields\":"
                            + "[{\"name\":\"Field\",\"value\":" + value + ",\"$type\":\"SimpleIssueCustomField\"}],"
                            + "\"$type\":\"Issue\"}]")));

            List<IssueSummary> issues = client.searchIssues("project: DEMO", 51);

            assertThat(issues.getFirst().customFields()).containsExactly(new CustomField("Field", rendered));
        }

        @Test
        void rendersEveryValueShape() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues/DEMO-7")).willReturn(okJson(fixture("issue-value-shapes.json"))));

            Issue issue = client.getIssue("DEMO-7");

            assertThat(issue.customFields()).containsExactly(
                    new CustomField("Owner", "Alice Smith"),            // fullName wins over name
                    new CustomField("Reporter bot", "build-bot"),       // login when nothing else
                    new CustomField("Reviewers", "Alice Smith, Jane Doe"),
                    new CustomField("Components", "Backend, API"),
                    new CustomField("Estimation", "1d 2h"),
                    new CustomField("Notes", "Check the cache first."),
                    new CustomField("Story points", "5"),
                    new CustomField("Colour code", "blue"),
                    new CustomField("Mystery", "(unrecognised FutureValue)"),
                    new CustomField("Subsystem", null),
                    new CustomField("Fix versions", null));
        }

        @Test
        void unresolvedIssueWithoutDescriptionCommentsOrLinks() {
            // No links still means one entry per link type and direction, each with no issues.
            youTrack.stubFor(get(urlPathEqualTo("/api/issues/DEMO-7")).willReturn(okJson(fixture("issue-value-shapes.json"))));

            Issue issue = client.getIssue("DEMO-7");

            assertThat(issue.resolved()).isNull();
            assertThat(issue.description()).isNull();
            assertThat(issue.issueComments()).isEmpty();
            assertThat(issue.links()).isEmpty();
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "OUTWARD | is required for",
                "INWARD  | depends on",
                "BOTH    | is required for"})
        void eachDirectionTakesItsPhrase(String direction, String relation) {
            stubIssueWithLinks("[" + link(direction, "Depend", "\"is required for\"", "\"depends on\"",
                    linkedIssue("DEMO-2", "\"Other\"")) + "]");

            assertThat(client.getIssue("DEMO-7").links()).containsExactly(new IssueLink(relation, "DEMO-2", "Other"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"\"\"", "\"   \"", "null"})
        void blankPhraseFallsBackToTheLinkTypeName(String phrase) {
            // Relates has an empty targetToSource; a custom type could leave the phrase it uses blank.
            stubIssueWithLinks("["
                    + link("OUTWARD", "Blocks", phrase, "\"blocked by\"", linkedIssue("DEMO-2", "\"Other\"")) + ","
                    + link("INWARD", "Blocks", "\"blocks\"", phrase, linkedIssue("DEMO-3", "\"Another\"")) + "]");

            assertThat(client.getIssue("DEMO-7").links()).containsExactly(
                    new IssueLink("Blocks", "DEMO-2", "Other"),
                    new IssueLink("Blocks", "DEMO-3", "Another"));
        }

        @Test
        void nullLinkedSummaryIsKept() {
            stubIssueWithLinks("[" + link("BOTH", "Relates", "\"relates to\"", "\"\"", linkedIssue("DEMO-2", "null")) + "]");

            assertThat(client.getIssue("DEMO-7").links()).containsExactly(new IssueLink("relates to", "DEMO-2", null));
        }

        @Test
        void severalIssuesInSeveralEntriesKeepYouTracksOrder() {
            // Entries in the order returned, issues in the order listed in each, an empty entry
            // in between contributing nothing; neither is sorted by relation or ID.
            stubIssueWithLinks("["
                    + link("OUTWARD", "Subtask", "\"parent for\"", "\"subtask of\"",
                            linkedIssue("DEMO-30", "\"Third\"") + "," + linkedIssue("DEMO-4", "\"Fourth\"") + ","
                                    + linkedIssue("DEMO-12", "\"Twelfth\"")) + ","
                    + link("INWARD", "Depend", "\"is required for\"", "\"depends on\"", "") + ","
                    + link("BOTH", "Relates", "\"relates to\"", "\"\"",
                            linkedIssue("DEMO-9", "\"Ninth\"") + "," + linkedIssue("DEMO-1", "\"First\"")) + "]");

            assertThat(client.getIssue("DEMO-7").links()).containsExactly(
                    new IssueLink("parent for", "DEMO-30", "Third"),
                    new IssueLink("parent for", "DEMO-4", "Fourth"),
                    new IssueLink("parent for", "DEMO-12", "Twelfth"),
                    new IssueLink("relates to", "DEMO-9", "Ninth"),
                    new IssueLink("relates to", "DEMO-1", "First"));
        }

        @Test
        void missingLinksKeyIsUnavailable() {
            // YouTrack always sends links when asked, even for an issue with none.
            String fixture = fixture("issue-value-shapes.json");
            String withoutLinks = fixture.substring(0, fixture.indexOf(",\"links\":")) + ",\"$type\":\"Issue\"}";
            youTrack.stubFor(get(urlPathEqualTo("/api/issues/DEMO-7")).willReturn(okJson(withoutLinks)));

            assertThatThrownBy(() -> client.getIssue("DEMO-7")).isInstanceOf(YouTrackUnavailableException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "{}",                                                                             // not an array
                "[\"relates to DEMO-2\"]",                                                        // entry not an object
                "[{\"direction\":\"SIDEWAYS\",\"linkType\":" + RELATES + ",\"issues\":[]}]",      // unknown direction
                "[{\"direction\":\"\",\"linkType\":" + RELATES + ",\"issues\":[]}]",              // empty direction
                "[{\"linkType\":" + RELATES + ",\"issues\":[]}]",                                 // no direction
                "[{\"direction\":\"BOTH\",\"issues\":[]}]",                                       // no linkType
                "[{\"direction\":\"BOTH\",\"linkType\":" + RELATES + "}]",                        // no issues
                "[{\"direction\":\"BOTH\",\"linkType\":" + RELATES + ",\"issues\":[{\"summary\":\"No ID\"}]}]",  // no ID
                "[{\"direction\":\"BOTH\",\"linkType\":" + RELATES + ",\"issues\":[{\"summary\":\"Null ID\",\"idReadable\":null}]}]"}) // null ID
        void malformedLinksAreUnavailable(String links) {
            stubIssueWithLinks(links);

            assertThatThrownBy(() -> client.getIssue("DEMO-7")).isInstanceOf(YouTrackUnavailableException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"resolved", "description"})
        void missingNullableKeyIsUnavailable(String key) {
            // YouTrack sends "resolved": null and "description": null when unset (checked
            // 2026-10-30), so a missing key means the response isn't what we asked for.
            String withoutKey = fixture("issue-value-shapes.json").replace("\"" + key + "\":null,", "");
            assertThat(withoutKey).doesNotContain("\"" + key + "\"");
            youTrack.stubFor(get(urlPathEqualTo("/api/issues/DEMO-7")).willReturn(okJson(withoutKey)));

            assertThatThrownBy(() -> client.getIssue("DEMO-7")).isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void nullIssueIdIsAProgrammingErrorAndSendsNoRequest() {
            assertThatThrownBy(() -> client.getIssue(null))
                    .isInstanceOf(NullPointerException.class)
                    .message().containsIgnoringCase("issue");
            assertThat(youTrack.getAllServeEvents()).isEmpty();
        }
    }

    // --- createIssue ---------------------------------------------------------------------

    @Nested
    class CreateIssue {

        @Test
        void postsTheIssueWithTokenFieldsAndAJsonBody() {
            stubCreate(okJson(fixture("create-issue.json")));

            client.createIssue("DEMO", "Login fails on Safari", "Steps to reproduce");

            youTrack.verify(postRequestedFor(urlPathEqualTo("/api/issues"))
                    .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                    .withHeader("Accept", equalTo("application/json"))
                    .withHeader("Content-Type", containing("application/json"))
                    .withQueryParam("fields", equalTo("idReadable,summary"))
                    .withRequestBody(equalToJson("""
                            {"project":{"shortName":"DEMO"},"summary":"Login fails on Safari",
                             "description":"Steps to reproduce"}""")));
        }

        @Test
        void leavesANullDescriptionOutOfTheBody() {
            stubCreate(okJson(fixture("create-issue.json")));

            client.createIssue("DEMO", "Login fails on Safari", null);

            // equalToJson is strict: an extra "description" key, even null, fails the match.
            youTrack.verify(postRequestedFor(urlPathEqualTo("/api/issues")).withRequestBody(equalToJson("""
                    {"project":{"shortName":"DEMO"},"summary":"Login fails on Safari"}""")));
        }

        @Test
        void sendsTheKeyAsGiven() {
            // YouTrack matches keys case-insensitively, so the client doesn't change the case.
            stubCreate(okJson(fixture("create-issue.json")));

            client.createIssue("demo", "Login fails on Safari", null);

            youTrack.verify(postRequestedFor(urlPathEqualTo("/api/issues")).withRequestBody(equalToJson("""
                    {"project":{"shortName":"demo"},"summary":"Login fails on Safari"}""")));
        }

        @Test
        void escapesQuotesBackslashesAndLineBreaksInTheBody() {
            stubCreate(okJson(fixture("create-issue.json")));

            client.createIssue("DEMO", "Say \"hi\" \\ wave", "Line one\n\nLine \"two\"\r\nC:\\temp");

            youTrack.verify(postRequestedFor(urlPathEqualTo("/api/issues")).withRequestBody(equalToJson("""
                    {"project":{"shortName":"DEMO"},"summary":"Say \\"hi\\" \\\\ wave",
                     "description":"Line one\\n\\nLine \\"two\\"\\r\\nC:\\\\temp"}""")));
        }

        @Test
        void mapsTheCreatedIssueWithItsLink() {
            stubCreate(okJson(fixture("create-issue.json")));

            CreatedIssue created = client.createIssue("DEMO", "Login fails on Safari", null);

            assertThat(created).isEqualTo(new CreatedIssue(
                    "DEMO-13", "Login fails on Safari", youTrack.baseUrl() + "/issue/DEMO-13"));
        }

        @Test
        void summaryComesFromTheResponseNotTheArgument() {
            stubCreate(okJson("{\"summary\":\"As stored\",\"idReadable\":\"DEMO-13\",\"$type\":\"Issue\"}"));

            assertThat(client.createIssue("DEMO", "As sent", null).summary()).isEqualTo("As stored");
        }

        @Test
        void linkKeepsAPathPrefixAndDropsATrailingSlash() {
            youTrack.stubFor(post(urlPathEqualTo("/youtrack/api/issues")).willReturn(okJson(fixture("create-issue.json"))));

            CreatedIssue created = clientFor(youTrack.baseUrl() + "/youtrack/").createIssue("DEMO", "Login fails on Safari", null);

            assertThat(created.url()).isEqualTo(youTrack.baseUrl() + "/youtrack/issue/DEMO-13");
        }

        @Test
        void nullProjectKeyIsAProgrammingErrorAndSendsNoRequest() {
            assertThatThrownBy(() -> client.createIssue(null, "Login fails on Safari", null))
                    .isInstanceOf(NullPointerException.class)
                    .message().containsIgnoringCase("project");
            assertThat(youTrack.getAllServeEvents()).isEmpty();
        }

        @Test
        void nullSummaryIsAProgrammingErrorAndSendsNoRequest() {
            assertThatThrownBy(() -> client.createIssue("DEMO", null, null))
                    .isInstanceOf(NullPointerException.class)
                    .message().containsIgnoringCase("summary");
            assertThat(youTrack.getAllServeEvents()).isEmpty();
        }
    }

    // --- addComment ----------------------------------------------------------------------

    @Nested
    class AddComment {

        @Test
        void postsTheCommentWithTokenFieldsAndAJsonBody() {
            stubComment("DEMO-13", okJson(fixture("add-comment.json")));

            client.addComment("DEMO-13", "Fixed in the 2.3 build");

            // The tool argument is "comment"; YouTrack's field is "text".
            youTrack.verify(postRequestedFor(urlPathEqualTo("/api/issues/DEMO-13/comments"))
                    .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                    .withHeader("Accept", equalTo("application/json"))
                    .withHeader("Content-Type", containing("application/json"))
                    .withQueryParam("fields", equalTo("id,issue(idReadable)"))
                    .withRequestBody(equalToJson("""
                            {"text":"Fixed in the 2.3 build"}""")));
        }

        @Test
        void sendsTheIdAsGiven() {
            // Captured: YouTrack accepts a lowercase ID, so the client doesn't change the case.
            stubComment("demo-13", okJson(fixture("add-comment.json")));

            client.addComment("demo-13", "Fixed in the 2.3 build");

            youTrack.verify(postRequestedFor(urlPathEqualTo("/api/issues/demo-13/comments")));
        }

        @Test
        void escapesQuotesBackslashesAndLineBreaksInTheBody() {
            stubComment("DEMO-13", okJson(fixture("add-comment.json")));

            client.addComment("DEMO-13", "Line one\n\nLine \"two\"\r\nC:\\temp");

            youTrack.verify(postRequestedFor(urlPathEqualTo("/api/issues/DEMO-13/comments")).withRequestBody(equalToJson("""
                    {"text":"Line one\\n\\nLine \\"two\\"\\r\\nC:\\\\temp"}""")));
        }

        @Test
        void mapsTheAddedCommentWithTheIssueLink() {
            stubComment("DEMO-13", okJson(fixture("add-comment.json")));

            AddedComment added = client.addComment("DEMO-13", "Fixed in the 2.3 build");

            assertThat(added).isEqualTo(new AddedComment("DEMO-13", youTrack.baseUrl() + "/issue/DEMO-13"));
        }

        @Test
        void issueIdComesFromTheResponseNotTheArgument() {
            stubComment("demo-13", okJson(fixture("add-comment.json")));

            AddedComment added = client.addComment("demo-13", "Fixed in the 2.3 build");

            assertThat(added.issueId()).isEqualTo("DEMO-13");
            assertThat(added.url()).endsWith("/issue/DEMO-13");
        }

        @Test
        void linkKeepsAPathPrefixAndDropsATrailingSlash() {
            youTrack.stubFor(post(urlPathEqualTo("/youtrack/api/issues/DEMO-13/comments"))
                    .willReturn(okJson(fixture("add-comment.json"))));

            AddedComment added = clientFor(youTrack.baseUrl() + "/youtrack/").addComment("DEMO-13", "Fixed in the 2.3 build");

            assertThat(added.url()).isEqualTo(youTrack.baseUrl() + "/youtrack/issue/DEMO-13");
        }

        @Test
        void nullIssueIdIsAProgrammingErrorAndSendsNoRequest() {
            assertThatThrownBy(() -> client.addComment(null, "Fixed in the 2.3 build"))
                    .isInstanceOf(NullPointerException.class)
                    .message().containsIgnoringCase("issue");
            assertThat(youTrack.getAllServeEvents()).isEmpty();
        }

        @Test
        void nullCommentIsAProgrammingErrorAndSendsNoRequest() {
            assertThatThrownBy(() -> client.addComment("DEMO-13", null))
                    .isInstanceOf(NullPointerException.class)
                    .message().containsIgnoringCase("comment");
            assertThat(youTrack.getAllServeEvents()).isEmpty();
        }
    }

    @Nested
    class UpdateIssue {

        private static final String COMMAND_FIELDS = "commands(description,error),issues(idReadable)";

        @Test
        void postsTheCommandWithTokenFieldsAndAJsonBody() {
            stubUpdate(okJson(fixture("update-issue.json")));

            client.updateIssue("DEMO-13", "State In Progress Priority Major Assignee Unassigned");

            // The tool argument is "command"; YouTrack's field is "query".
            youTrack.verify(postRequestedFor(urlPathEqualTo("/api/commands"))
                    .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                    .withHeader("Accept", equalTo("application/json"))
                    .withHeader("Content-Type", containing("application/json"))
                    .withQueryParam("fields", equalTo(COMMAND_FIELDS))
                    .withRequestBody(equalToJson("""
                            {"query":"State In Progress Priority Major Assignee Unassigned",
                             "issues":[{"idReadable":"DEMO-13"}]}""")));
        }

        @Test
        void sendsTheIdAndCommandAsGiven() {
            // Captured: a lowercase ID works. Stripping the command is the tool's job.
            stubUpdate(okJson(fixture("update-issue.json")));

            client.updateIssue("demo-13", " state {in progress} ");

            youTrack.verify(postRequestedFor(urlPathEqualTo("/api/commands")).withRequestBody(equalToJson("""
                    {"query":" state {in progress} ","issues":[{"idReadable":"demo-13"}]}""")));
        }

        @Test
        void escapesQuotesAndBackslashesInTheBody() {
            stubUpdate(okJson(fixture("update-issue.json")));

            client.updateIssue("DEMO-13", "tag {say \"hi\"} C:\\temp");

            youTrack.verify(postRequestedFor(urlPathEqualTo("/api/commands")).withRequestBody(equalToJson("""
                    {"query":"tag {say \\"hi\\"} C:\\\\temp","issues":[{"idReadable":"DEMO-13"}]}""")));
        }

        @Test
        void mapsEachChangeInOrderWithoutYouTracksMarkup() {
            stubUpdate(okJson(fixture("update-issue.json")));

            UpdatedIssue updated = client.updateIssue("DEMO-13", "State In Progress Priority Major Assignee Unassigned");

            assertThat(updated).isEqualTo(new UpdatedIssue("DEMO-13",
                    List.of("Set State In Progress", "Set Priority Major", "Clear field Assignee"),
                    youTrack.baseUrl() + "/issue/DEMO-13"));
        }

        @Test
        void issueIdComesFromTheResponseNotTheArgument() {
            stubUpdate(okJson(fixture("update-issue.json")));

            UpdatedIssue updated = client.updateIssue("demo-13", "State In Progress");

            assertThat(updated.issueId()).isEqualTo("DEMO-13");
            assertThat(updated.url()).endsWith("/issue/DEMO-13");
        }

        @Test
        void noParsedCommandsIsAnEmptyListOfChanges() {
            // Captured with an empty command; the tool's blank check normally stops this first.
            stubUpdate(okJson(fixture("update-issue-no-changes.json")));

            UpdatedIssue updated = client.updateIssue("DEMO-13", " ");

            assertThat(updated.changes()).isEmpty();
            assertThat(updated.issueId()).isEqualTo("DEMO-13");
        }

        @Test
        void decodesHtmlEntitiesInChanges() {
            // Synthetic: YouTrack escapes values it echoes back (seen in a captured error).
            stubUpdate(okJson(commandResponse(
                    "Set Summary <span class=\\\"bold\\\">R&amp;D &lt;draft&gt; &quot;v2&quot; it&#39;s</span>")));

            UpdatedIssue updated = client.updateIssue("DEMO-13", "State In Progress");

            assertThat(updated.changes()).containsExactly("Set Summary R&D <draft> \"v2\" it's");
        }

        @Test
        void decodesAmpersandLastSoAnEscapedEntityStaysAnEntity() {
            // "&amp;lt;" is the escaped form of the text "&lt;", not of "<".
            stubUpdate(okJson(commandResponse("tag <span class=\\\"bold\\\">&amp;lt;b&amp;gt;</span>")));

            UpdatedIssue updated = client.updateIssue("DEMO-13", "State In Progress");

            assertThat(updated.changes()).containsExactly("tag &lt;b&gt;");
        }

        @Test
        void stripsEveryTagNotOnlySpans() {
            stubUpdate(okJson(commandResponse("<b>Set</b> State <span class=\\\"bold\\\"><i>Fixed</i></span>")));

            UpdatedIssue updated = client.updateIssue("DEMO-13", "State Fixed");

            assertThat(updated.changes()).containsExactly("Set State Fixed");
        }

        @Test
        void linkKeepsAPathPrefixAndDropsATrailingSlash() {
            youTrack.stubFor(post(urlPathEqualTo("/youtrack/api/commands"))
                    .willReturn(okJson(fixture("update-issue.json"))));

            UpdatedIssue updated = clientFor(youTrack.baseUrl() + "/youtrack/").updateIssue("DEMO-13", "State In Progress");

            assertThat(updated.url()).isEqualTo(youTrack.baseUrl() + "/youtrack/issue/DEMO-13");
        }

        @Test
        void nullIssueIdIsAProgrammingErrorAndSendsNoRequest() {
            assertThatThrownBy(() -> client.updateIssue(null, "State In Progress"))
                    .isInstanceOf(NullPointerException.class)
                    .message().containsIgnoringCase("issue");
            assertThat(youTrack.getAllServeEvents()).isEmpty();
        }

        @Test
        void nullCommandIsAProgrammingErrorAndSendsNoRequest() {
            assertThatThrownBy(() -> client.updateIssue("DEMO-13", null))
                    .isInstanceOf(NullPointerException.class)
                    .message().containsIgnoringCase("command");
            assertThat(youTrack.getAllServeEvents()).isEmpty();
        }
    }

    // --- errors --------------------------------------------------------------------------

    @Nested
    class Errors {

        @Test
        void unauthorizedIsAnAuthFailure() {
            stubProjects(401, fixture("error-401.json"));

            assertThatThrownBy(() -> client.listProjects()).isInstanceOf(YouTrackAuthException.class);
        }

        @Test
        void forbiddenIsAnAuthFailure() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(errorJson(403, "{\"error\":\"Forbidden\"}")));

            assertThatThrownBy(() -> client.searchIssues("project: DEMO", 51)).isInstanceOf(YouTrackAuthException.class);
        }

        @Test
        void unknownIssueIsNotFoundAndCarriesTheId() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues/DEMO-99999")).willReturn(errorJson(404, fixture("error-404.json"))));

            Throwable thrown = catchThrowable(() -> client.getIssue("DEMO-99999"));

            assertThat(thrown).isInstanceOf(IssueNotFoundException.class);
            assertThat(((IssueNotFoundException) thrown).issueId()).isEqualTo("DEMO-99999");
        }

        @Test
        void badQueryIsInvalidAndCarriesYouTracksExplanation() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(errorJson(400, fixture("error-400-query.json"))));

            Throwable thrown = catchThrowable(() -> client.searchIssues("project: (((", 51));

            assertThat(thrown).isInstanceOf(InvalidQueryException.class);
            assertThat(((InvalidQueryException) thrown).detail())
                    .isEqualTo("Can't parse search query, please check and update query syntax "
                            + "(Value expected for field project.)");
        }

        @Test
        void badQueryWithoutChildErrorsCarriesJustTheDescription() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(errorJson(400,
                    "{\"error\":\"invalid_query\",\"error_description\":\"Can't parse search query, please check and update query syntax\"}")));

            Throwable thrown = catchThrowable(() -> client.searchIssues("project:", 51));

            assertThat(thrown).isInstanceOf(InvalidQueryException.class);
            assertThat(((InvalidQueryException) thrown).detail()).isEqualTo("Can't parse search query, please check and update query syntax");
        }

        @Test
        void badQueryWithSeveralChildErrorsJoinsThemInOrder() {
            // The fixture has one child; the second follows the same real "Value expected" wording.
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(errorJson(400,
                    "{\"error\":\"invalid_query\",\"error_description\":\"Can't parse search query, please check and update query syntax\","
                            + "\"error_children\":[{\"error\":\"Value expected for field project.\",\"error_description\":\"\"},"
                            + "{\"error\":\"Value expected for field State.\",\"error_description\":\"\"}]}")));

            Throwable thrown = catchThrowable(() -> client.searchIssues("project: State:", 51));

            assertThat(thrown).isInstanceOf(InvalidQueryException.class);
            assertThat(((InvalidQueryException) thrown).detail())
                    .isEqualTo("Can't parse search query, please check and update query syntax "
                            + "(Value expected for field project.; Value expected for field State.)");
        }

        @Test
        void badQueryUsesYouTracksDescriptionWhateverItSays() {
            // The detail is built from the response, not from a fixed sentence.
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(errorJson(400,
                    "{\"error\":\"invalid_query\",\"error_description\":\"Query is too complex\","
                            + "\"error_children\":[{\"error\":\"Too many terms\",\"error_description\":\"\"}]}")));

            Throwable thrown = catchThrowable(() -> client.searchIssues("project: DEMO", 51));

            assertThat(thrown).isInstanceOf(InvalidQueryException.class);
            assertThat(((InvalidQueryException) thrown).detail()).isEqualTo("Query is too complex (Too many terms)");
        }

        @Test
        void badQueryWithAChildThatIsNotAnObjectIsUnavailable() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(errorJson(400,
                    "{\"error\":\"invalid_query\",\"error_description\":\"Can't parse search query, please check and update query syntax\","
                            + "\"error_children\":[\"Value expected for field project.\"]}")));

            assertThatThrownBy(() -> client.searchIssues("project:", 51)).isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void badQueryWithAnEmptyChildListCarriesJustTheDescription() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(errorJson(400,
                    "{\"error\":\"invalid_query\",\"error_description\":\"Can't parse search query, please check and update query syntax\",\"error_children\":[]}")));

            Throwable thrown = catchThrowable(() -> client.searchIssues("project:", 51));

            assertThat(thrown).isInstanceOf(InvalidQueryException.class);
            assertThat(((InvalidQueryException) thrown).detail()).isEqualTo("Can't parse search query, please check and update query syntax");
        }

        @ParameterizedTest
        @ValueSource(strings = {"<html><body>400 Bad Request</body></html>", "{\"error\":\"bad_request\"}"})
        void badRequestThatIsNotAYouTrackQueryErrorIsUnavailable(String body) {
            // A 400 without YouTrack's error_description (e.g. from a proxy) didn't come from the
            // query parser; telling the model to fix its query would mislead it.
            youTrack.stubFor(get(urlPathEqualTo("/api/issues"))
                    .willReturn(aResponse().withStatus(400).withBody(body)));

            assertThatThrownBy(() -> client.searchIssues("project: DEMO", 51))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void requiredFieldRejectionCarriesYouTracksDescription() {
            stubCreate(errorJson(400, fixture("error-400-create-required-field.json")));

            Throwable thrown = catchThrowable(() -> client.createIssue("DEMO", "Login fails on Safari", null));

            assertThat(thrown).isInstanceOf(RequestRejectedException.class);
            assertThat(((RequestRejectedException) thrown).detail()).isEqualTo("Test Field is required");
        }

        @Test
        void unknownProjectIsARejectionLikeAnyOther400() {
            // Every 400 from create is one rejection; the client doesn't read YouTrack's wording.
            stubCreate(errorJson(400, fixture("error-400-create-unknown-project.json")));

            Throwable thrown = catchThrowable(() -> client.createIssue("NOPE", "Login fails on Safari", null));

            assertThat(thrown).isInstanceOf(RequestRejectedException.class);
            assertThat(((RequestRejectedException) thrown).detail())
                    .isEqualTo("YouTrack is unable to locate an Project-type entity with the specified ID");
        }

        @ParameterizedTest
        @ValueSource(strings = {"<html><body>400 Bad Request</body></html>", "{\"error\":\"bad_request\"}"})
        void badRequestOnCreateWithoutAnErrorDescriptionIsUnavailable(String body) {
            stubCreate(aResponse().withStatus(400).withBody(body));

            assertThatThrownBy(() -> client.createIssue("DEMO", "Login fails on Safari", null))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @ParameterizedTest
        @ValueSource(ints = {401, 403})
        void rejectedTokenOnCreateIsAnAuthFailure(int status) {
            stubCreate(errorJson(status, fixture("error-401.json")));

            assertThatThrownBy(() -> client.createIssue("DEMO", "Login fails on Safari", null))
                    .isInstanceOf(YouTrackAuthException.class);
        }

        @Test
        void serverErrorOnCreateIsUnavailable() {
            stubCreate(errorJson(503, "<html>Service Unavailable</html>"));

            assertThatThrownBy(() -> client.createIssue("DEMO", "Login fails on Safari", null))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void connectionFailureOnCreateIsUnavailableWithTheCause() {
            stubCreate(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

            Throwable thrown = catchThrowable(() -> client.createIssue("DEMO", "Login fails on Safari", null));

            assertThat(thrown).isInstanceOf(YouTrackUnavailableException.class);
            assertThat(thrown.getCause()).isNotNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"{\"summary\":\"No ID\",\"$type\":\"Issue\"}", "{\"idReadable\":\"DEMO-13\",\"$type\":\"Issue\"}", "[]"})
        void createdResponseWithoutTheExpectedShapeIsUnavailable(String body) {
            stubCreate(okJson(body));

            assertThatThrownBy(() -> client.createIssue("DEMO", "Login fails on Safari", null))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void commentOnAnUnknownIssueIsNotFoundAndCarriesTheId() {
            stubComment("DEMO-99999", errorJson(404, fixture("error-404.json")));

            Throwable thrown = catchThrowable(() -> client.addComment("DEMO-99999", "Fixed in the 2.3 build"));

            assertThat(thrown).isInstanceOf(IssueNotFoundException.class);
            assertThat(((IssueNotFoundException) thrown).issueId()).isEqualTo("DEMO-99999");
        }

        @Test
        void emptyCommentRejectionCarriesYouTracksDescription() {
            // Captured with empty text; the tool's blank check normally stops this first.
            stubComment("DEMO-13", errorJson(400, fixture("error-400-comment-empty.json")));

            Throwable thrown = catchThrowable(() -> client.addComment("DEMO-13", ""));

            assertThat(thrown).isInstanceOf(RequestRejectedException.class);
            assertThat(((RequestRejectedException) thrown).detail()).isEqualTo("Comment can't be empty.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"<html><body>400 Bad Request</body></html>", "{\"error\":\"bad_request\"}"})
        void badRequestOnCommentWithoutAnErrorDescriptionIsUnavailable(String body) {
            stubComment("DEMO-13", aResponse().withStatus(400).withBody(body));

            assertThatThrownBy(() -> client.addComment("DEMO-13", "Fixed in the 2.3 build"))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @ParameterizedTest
        @ValueSource(ints = {401, 403})
        void rejectedTokenOnCommentIsAnAuthFailure(int status) {
            stubComment("DEMO-13", errorJson(status, fixture("error-401.json")));

            assertThatThrownBy(() -> client.addComment("DEMO-13", "Fixed in the 2.3 build"))
                    .isInstanceOf(YouTrackAuthException.class);
        }

        @Test
        void serverErrorOnCommentIsUnavailable() {
            stubComment("DEMO-13", errorJson(503, "<html>Service Unavailable</html>"));

            assertThatThrownBy(() -> client.addComment("DEMO-13", "Fixed in the 2.3 build"))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void connectionFailureOnCommentIsUnavailableWithTheCause() {
            stubComment("DEMO-13", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

            Throwable thrown = catchThrowable(() -> client.addComment("DEMO-13", "Fixed in the 2.3 build"));

            assertThat(thrown).isInstanceOf(YouTrackUnavailableException.class);
            assertThat(thrown.getCause()).isNotNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "{\"issue\":{\"idReadable\":\"DEMO-13\",\"$type\":\"Issue\"},\"$type\":\"IssueComment\"}",
                "{\"id\":\"7-2\",\"$type\":\"IssueComment\"}",
                "{\"issue\":{\"$type\":\"Issue\"},\"id\":\"7-2\",\"$type\":\"IssueComment\"}",
                "[]"})
        void addedCommentResponseWithoutTheExpectedShapeIsUnavailable(String body) {
            stubComment("DEMO-13", okJson(body));

            assertThatThrownBy(() -> client.addComment("DEMO-13", "Fixed in the 2.3 build"))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void unknownValueRejectionCarriesYouTracksDescription() {
            stubUpdate(errorJson(400, fixture("error-400-command-unknown-value.json")));

            Throwable thrown = catchThrowable(() -> client.updateIssue("DEMO-13", "State Bogus"));

            assertThat(thrown).isInstanceOf(RequestRejectedException.class);
            assertThat(((RequestRejectedException) thrown).detail()).isEqualTo("State expected: Bogus");
        }

        @Test
        void unknownIssueOnUpdateIsARejectionLikeAnyOther400() {
            // Captured: YouTrack answers 400 here, not 404, and the wording isn't matched.
            stubUpdate(errorJson(400, fixture("error-400-command-unknown-issue.json")));

            Throwable thrown = catchThrowable(() -> client.updateIssue("DEMO-99999", "State In Progress"));

            assertThat(thrown).isInstanceOf(RequestRejectedException.class);
            assertThat(((RequestRejectedException) thrown).detail())
                    .isEqualTo("YouTrack is unable to locate an Issue-type entity with the specified ID");
        }

        @Test
        void rejectionDetailOnUpdateIsDecoded() {
            // Captured: "untag R&R" came back as "tag expected: R&amp;R".
            stubUpdate(errorJson(400, fixture("error-400-command-escaped.json")));

            Throwable thrown = catchThrowable(() -> client.updateIssue("DEMO-13", "untag R&R"));

            assertThat(((RequestRejectedException) thrown).detail()).isEqualTo("tag expected: R&R");
        }

        @Test
        void invalidTagNameRejectionUsesOnlyTheTopLevelDescription() {
            // Captured: the specific reason is in error_children, written for developers.
            stubUpdate(errorJson(400, fixture("error-400-command-tag-name.json")));

            Throwable thrown = catchThrowable(() -> client.updateIssue("DEMO-13", "tag R&R"));

            assertThat(((RequestRejectedException) thrown).detail()).isEqualTo("Property Tag.name is invalid");
        }

        @ParameterizedTest
        @ValueSource(strings = {"<html><body>400 Bad Request</body></html>", "{\"error\":\"Bad Request\"}"})
        void badRequestOnUpdateWithoutAnErrorDescriptionIsUnavailable(String body) {
            stubUpdate(aResponse().withStatus(400).withBody(body));

            assertThatThrownBy(() -> client.updateIssue("DEMO-13", "State In Progress"))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @ParameterizedTest
        @ValueSource(ints = {401, 403})
        void rejectedTokenOnUpdateIsAnAuthFailure(int status) {
            stubUpdate(errorJson(status, fixture("error-401.json")));

            assertThatThrownBy(() -> client.updateIssue("DEMO-13", "State In Progress"))
                    .isInstanceOf(YouTrackAuthException.class);
        }

        @Test
        void serverErrorOnUpdateIsUnavailable() {
            stubUpdate(errorJson(503, "<html>Service Unavailable</html>"));

            assertThatThrownBy(() -> client.updateIssue("DEMO-13", "State In Progress"))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void connectionFailureOnUpdateIsUnavailableWithTheCause() {
            stubUpdate(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

            Throwable thrown = catchThrowable(() -> client.updateIssue("DEMO-13", "State In Progress"));

            assertThat(thrown).isInstanceOf(YouTrackUnavailableException.class);
            assertThat(thrown.getCause()).isNotNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "{\"commands\":[]}",
                "{\"commands\":[],\"issues\":[]}",
                "{\"commands\":[],\"issues\":[{\"$type\":\"Issue\"}]}",
                "{\"issues\":[{\"idReadable\":\"DEMO-13\"}]}",
                "{\"commands\":{},\"issues\":[{\"idReadable\":\"DEMO-13\"}]}",
                "{\"commands\":[{\"error\":false}],\"issues\":[{\"idReadable\":\"DEMO-13\"}]}",
                "[]"})
        void updatedIssueResponseWithoutTheExpectedShapeIsUnavailable(String body) {
            stubUpdate(okJson(body));

            assertThatThrownBy(() -> client.updateIssue("DEMO-13", "State In Progress"))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void successWithACommandFlaggedAsAnErrorIsUnavailable() {
            // Never seen: commands were all-or-nothing in the captures, so this means the issue
            // may be in an unknown state, and "may have been updated" is the honest answer.
            stubUpdate(okJson("{\"commands\":[{\"error\":false,\"description\":\"Set Priority Major\"},"
                    + "{\"error\":true,\"description\":\"State expected: Bogus\"}],"
                    + "\"issues\":[{\"idReadable\":\"DEMO-13\"}]}"));

            assertThatThrownBy(() -> client.updateIssue("DEMO-13", "Priority Major State Bogus"))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void serverErrorIsUnavailable() {
            stubProjects(503, "Service Unavailable");

            assertThatThrownBy(() -> client.listProjects()).isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void serverErrorMessageNamesTheStatusNotTheBody() {
            stubProjects(503, "<html>Service Unavailable</html>");

            assertThatThrownBy(() -> client.listProjects())
                    .isInstanceOf(YouTrackUnavailableException.class)
                    .hasNoCause()
                    .message().contains("503").doesNotContain("<html>");
        }

        @Test
        void notFoundOutsideGetIssueIsUnavailable() {
            // A 404 on the projects endpoint means a wrong base URL, not a missing entity.
            stubProjects(404, fixture("error-404.json"));

            assertThatThrownBy(() -> client.listProjects()).isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void connectionFailureIsUnavailableWithTheCause() {
            youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects"))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            Throwable thrown = catchThrowable(() -> client.listProjects());

            assertThat(thrown).isInstanceOf(YouTrackUnavailableException.class);
            assertThat(thrown.getCause()).isNotNull();
        }

        @Test
        void unparseableBodyIsUnavailable() {
            youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects")).willReturn(okJson("{not json")));

            assertThatThrownBy(() -> client.listProjects()).isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void parseableBodyMissingARequiredValueIsUnavailable() {
            // Valid JSON, wrong shape: an issue without idReadable.
            youTrack.stubFor(get(urlPathEqualTo("/api/issues"))
                    .willReturn(okJson("[{\"summary\":\"No ID\",\"customFields\":[],\"$type\":\"Issue\"}]")));

            assertThatThrownBy(() -> client.searchIssues("project: DEMO", 51))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void topLevelObjectWhereAnArrayIsExpectedIsUnavailable() {
            // Iterating {} yields nothing, which would read as "no projects are visible".
            youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects")).willReturn(okJson("{}")));

            assertThatThrownBy(() -> client.listProjects()).isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void issueWithoutCustomFieldsIsUnavailable() {
            // A missing array also iterates as empty; it must not read as "no fields set".
            youTrack.stubFor(get(urlPathEqualTo("/api/issues"))
                    .willReturn(okJson("[{\"idReadable\":\"DEMO-1\",\"summary\":\"No fields\",\"$type\":\"Issue\"}]")));

            assertThatThrownBy(() -> client.searchIssues("project: DEMO", 51))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "{\"name\":\"Normal\"}", "[{\"name\":\"Backend\"}]"})
        void fieldValueObjectWithoutATypeIsUnavailable(String value) {
            // YouTrack always sends $type, so an object without one is a malformed response,
            // not an unknown value type. Applies to array elements too.
            youTrack.stubFor(get(urlPathEqualTo("/api/issues"))
                    .willReturn(okJson("[{\"idReadable\":\"DEMO-1\",\"summary\":\"No type\",\"customFields\":"
                            + "[{\"name\":\"Priority\",\"value\":" + value + ",\"$type\":\"SingleEnumIssueCustomField\"}],"
                            + "\"$type\":\"Issue\"}]")));

            assertThatThrownBy(() -> client.searchIssues("project: DEMO", 51))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "{\"name\":\"Priority\",\"$type\":\"SingleEnumIssueCustomField\"}",                  // no value key
                "{\"value\":{\"name\":\"Normal\",\"$type\":\"EnumBundleElement\"},\"$type\":\"SingleEnumIssueCustomField\"}", // no name
                "\"Priority\""})                                                                    // not an object
        void customFieldWithoutTheExpectedShapeIsUnavailable(String field) {
            // YouTrack sends "value": null for an unset field, so a missing key is malformed, not unset.
            youTrack.stubFor(get(urlPathEqualTo("/api/issues"))
                    .willReturn(okJson("[{\"idReadable\":\"DEMO-1\",\"summary\":\"Bad field\",\"customFields\":["
                            + field + "],\"$type\":\"Issue\"}]")));

            assertThatThrownBy(() -> client.searchIssues("project: DEMO", 51))
                    .isInstanceOf(YouTrackUnavailableException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"\"just text\"", "{\"created\":1793308356048,\"author\":{\"login\":\"jdoe\",\"fullName\":\"Jane Doe\"}}",
                "{\"text\":\"Hi\",\"created\":1793308356048}",
                "{\"text\":\"Hi\",\"created\":1793308356048,\"author\":\"jdoe\"}"})
        void malformedCommentIsUnavailable(String comment) {
            // Not an object, no text key, no author: each must surface as YouTrack being unavailable,
            // never escape as some other exception type.
            String body = fixture("issue-value-shapes.json").replace("\"comments\":[]", "\"comments\":[" + comment + "]");
            assertThat(body).contains(comment);
            youTrack.stubFor(get(urlPathEqualTo("/api/issues/DEMO-7")).willReturn(okJson(body)));

            assertThatThrownBy(() -> client.getIssue("DEMO-7")).isInstanceOf(YouTrackUnavailableException.class);
        }

        @Test
        void interruptionIsUnavailableAndKeepsTheInterruptFlag() {
            youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects")).willReturn(okJson(fixture("projects.json"))));

            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> client.listProjects()).isInstanceOf(YouTrackUnavailableException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted(); // clear it so later tests aren't affected
            }
        }

        @Test
        void exceptionMessagesNeverContainTheToken() {
            stubProjects(401, fixture("error-401.json"));
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(errorJson(400, fixture("error-400-query.json"))));
            youTrack.stubFor(get(urlPathEqualTo("/api/issues/DEMO-99999")).willReturn(errorJson(404, fixture("error-404.json"))));

            for (Throwable thrown : List.of(
                    catchThrowable(() -> client.listProjects()),
                    catchThrowable(() -> client.searchIssues("project: (((", 51)),
                    catchThrowable(() -> client.getIssue("DEMO-99999")))) {
                assertThat(thrown).isInstanceOf(YouTrackException.class);
                assertThat(String.valueOf(thrown.getMessage())).doesNotContain(TOKEN);
            }
        }
    }

    // --- logging -------------------------------------------------------------------------

    @Nested
    class Logging {

        @Test
        void notFoundLogsOneWarningWithoutAStackTrace() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues/DEMO-99999")).willReturn(errorJson(404, fixture("error-404.json"))));

            catchThrowable(() -> client.getIssue("DEMO-99999"));

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
            });
        }

        @Test
        void invalidQueryLogsOneWarningWithoutAStackTrace() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues")).willReturn(errorJson(400, fixture("error-400-query.json"))));

            catchThrowable(() -> client.searchIssues("project: (((", 51));

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
                // The exception's sentence, not the raw response body.
                assertThat(event.getFormattedMessage()).containsIgnoringCase("rejected the query").doesNotContain("error_children");
            });
        }

        @Test
        void unauthorizedLogsTokenRejected() {
            stubProjects(401, fixture("error-401.json"));

            catchThrowable(() -> client.listProjects());

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).containsIgnoringCase("rejected the token");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }

        @Test
        void forbiddenLogsTokenLacksPermission() {
            stubProjects(403, "{\"error\":\"Forbidden\"}");

            catchThrowable(() -> client.listProjects());

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).containsIgnoringCase("lacks permission");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }

        @Test
        void connectionFailureLogsAnErrorWithTheCausesStackTrace() {
            youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects"))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            catchThrowable(() -> client.listProjects());

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getThrowableProxy()).isNotNull();
            });
        }

        @ParameterizedTest
        @ValueSource(strings = {"{not json", "{}"})
        void malformedBodyLogsOneErrorWithTheCausesStackTrace(String body) {
            youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects")).willReturn(okJson(body)));

            catchThrowable(() -> client.listProjects());

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getThrowableProxy()).isNotNull();
            });
        }

        @Test
        void serverErrorLogsOneErrorNamingTheStatusNotTheBody() {
            stubProjects(503, "<html>Service Unavailable</html>");

            catchThrowable(() -> client.listProjects());

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("503").doesNotContain("<html>");
                assertThat(event.getThrowableProxy()).isNull(); // no cause to show, and no body via an exception message
            });
        }

        @Test
        void badRequestThatIsNotAQueryErrorLogsOnceAsAnError() {
            youTrack.stubFor(get(urlPathEqualTo("/api/issues"))
                    .willReturn(aResponse().withStatus(400).withBody("<html><body>400 Bad Request</body></html>")));

            catchThrowable(() -> client.searchIssues("project: DEMO", 51));

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).doesNotContain("<html>");
            });
        }

        @Test
        void createLogsTheNewIdAtInfoButNeverTheSummaryOrDescription() {
            stubCreate(okJson(fixture("create-issue.json")));

            client.createIssue("DEMO", "Secret summary text", "Secret description text");

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).containsIgnoringCase("created issue DEMO-13")
                        .doesNotContain("Secret");
            });
        }

        @Test
        void rejectedCreateLogsOneWarningWithoutAStackTraceOrTheIssueText() {
            stubCreate(errorJson(400, fixture("error-400-create-required-field.json")));

            catchThrowable(() -> client.createIssue("DEMO", "Secret summary text", "Secret description text"));

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).containsIgnoringCase("rejected the request")
                        .doesNotContain("Secret").doesNotContain("error_rule_name");
            });
        }

        @Test
        void addCommentLogsTheIssueIdAtInfoButNeverTheComment() {
            stubComment("DEMO-13", okJson(fixture("add-comment.json")));

            client.addComment("DEMO-13", "Secret comment text");

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).containsIgnoringCase("added a comment to DEMO-13")
                        .doesNotContain("Secret");
            });
        }

        @Test
        void rejectedCommentLogsOneWarningWithoutAStackTraceOrTheComment() {
            stubComment("DEMO-13", errorJson(400, fixture("error-400-comment-empty.json")));

            catchThrowable(() -> client.addComment("DEMO-13", "Secret comment text"));

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).containsIgnoringCase("rejected the request")
                        .doesNotContain("Secret");
            });
        }

        @Test
        void commentOnAnUnknownIssueLogsOneWarningWithoutAStackTraceOrTheComment() {
            stubComment("DEMO-99999", errorJson(404, fixture("error-404.json")));

            catchThrowable(() -> client.addComment("DEMO-99999", "Secret comment text"));

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("Secret");
            });
        }

        @Test
        void updateLogsTheIssueIdAtInfoButNeverTheCommandOrTheChanges() {
            stubUpdate(okJson(fixture("update-issue.json")));

            client.updateIssue("DEMO-13", "Secret command text");

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).containsIgnoringCase("updated issue DEMO-13")
                        .doesNotContain("Secret").doesNotContain("In Progress").doesNotContain("Major");
            });
        }

        @Test
        void rejectedUpdateLogsOneWarningNamingTheIssueButNotYouTracksDetail() {
            // YouTrack's detail echoes values from the command, which can name people, so it
            // stays out of the log on this endpoint.
            stubUpdate(errorJson(400, "{\"error\":\"Bad Request\",\"error_description\":\"Assignee expected: Secret Person\"}"));

            catchThrowable(() -> client.updateIssue("DEMO-13", "Assignee Secret Person"));

            assertThat(log.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).containsIgnoringCase("rejected")
                        .contains("DEMO-13").doesNotContain("Secret");
            });
        }

        @Test
        void successLogsNoWarningsOrErrors() {
            youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects")).willReturn(okJson(fixture("projects.json"))));

            client.listProjects();

            assertThat(log.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        }

        @Test
        void logsNeverContainTheToken() {
            stubProjects(401, fixture("error-401.json"));
            youTrack.stubFor(get(urlPathEqualTo("/api/issues/DEMO-99999")).willReturn(errorJson(404, fixture("error-404.json"))));

            catchThrowable(() -> client.listProjects());
            catchThrowable(() -> client.getIssue("DEMO-99999"));

            assertThat(log.list).isNotEmpty().allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).doesNotContain(TOKEN));
        }
    }

    // --- helpers -------------------------------------------------------------------------

    private static HttpYouTrackClient clientFor(String baseUrl) {
        return new HttpYouTrackClient(new YouTrackConfig(baseUrl, TOKEN), HttpClient.newHttpClient());
    }

    private static void stubProjects(int status, String body) {
        youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects")).willReturn(errorJson(status, body)));
    }

    private static void stubCreate(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        youTrack.stubFor(post(urlPathEqualTo("/api/issues")).willReturn(response));
    }

    private static void stubComment(String issueId, com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        youTrack.stubFor(post(urlPathEqualTo("/api/issues/" + issueId + "/comments")).willReturn(response));
    }

    private static void stubUpdate(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        youTrack.stubFor(post(urlPathEqualTo("/api/commands")).willReturn(response));
    }

    /** A successful command response for DEMO-13 with one parsed command; {@code description} is JSON-escaped. */
    private static String commandResponse(String description) {
        return "{\"commands\":[{\"delete\":false,\"error\":false,\"description\":\"" + description
                + "\",\"$type\":\"ParsedCommand\"}],\"issues\":[{\"idReadable\":\"DEMO-13\",\"$type\":\"Issue\"}],"
                + "\"query\":\"State In Progress\"}";
    }

    /** Stubs DEMO-7 (issue-value-shapes.json) with its links array replaced by {@code links}. */
    private static void stubIssueWithLinks(String links) {
        String fixture = fixture("issue-value-shapes.json");
        String body = fixture.substring(0, fixture.indexOf(",\"links\":")) + ",\"links\":" + links + ",\"$type\":\"Issue\"}";
        youTrack.stubFor(get(urlPathEqualTo("/api/issues/DEMO-7")).willReturn(okJson(body)));
    }

    /** One links entry; the phrases are JSON values, so pass {@code "\"text\""} or {@code "null"}. */
    private static String link(String direction, String name, String sourceToTarget, String targetToSource, String issues) {
        return "{\"issues\":[" + issues + "],\"linkType\":{\"name\":\"" + name + "\",\"sourceToTarget\":" + sourceToTarget
                + ",\"targetToSource\":" + targetToSource + ",\"$type\":\"IssueLinkType\"},\"direction\":\"" + direction
                + "\",\"$type\":\"IssueLink\"}";
    }

    /** One linked issue; the summary is a JSON value, so pass {@code "\"text\""} or {@code "null"}. */
    private static String linkedIssue(String issueId, String summary) {
        return "{\"summary\":" + summary + ",\"idReadable\":\"" + issueId + "\",\"$type\":\"Issue\"}";
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder errorJson(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String fixture(String name) {
        try (InputStream in = HttpYouTrackClientTest.class.getResourceAsStream("/youtrack/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
