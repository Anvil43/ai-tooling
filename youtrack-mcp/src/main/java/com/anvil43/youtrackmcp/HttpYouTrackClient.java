package com.anvil43.youtrackmcp;

import com.anvil43.youtrackmcp.exception.InvalidQueryException;
import com.anvil43.youtrackmcp.exception.IssueNotFoundException;
import com.anvil43.youtrackmcp.exception.RequestRejectedException;
import com.anvil43.youtrackmcp.exception.UnexpectedResponseException;
import com.anvil43.youtrackmcp.exception.YouTrackAuthException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.AddedComment;
import com.anvil43.youtrackmcp.model.CreatedIssue;
import com.anvil43.youtrackmcp.model.Issue;
import com.anvil43.youtrackmcp.model.IssueSummary;
import com.anvil43.youtrackmcp.model.Project;
import com.anvil43.youtrackmcp.model.UpdatedIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

public class HttpYouTrackClient implements YouTrackClient {

    private static final String IO_EXCEPTION_MESSAGE = "Could not reach YouTrack.";
    private static final String INTERRUPTED_EXCEPTION_MESSAGE = "Interrupted while waiting for YouTrack.";
    private static final String AUTHENTICATION_RESPONSE_401 = "YouTrack rejected the token.";
    private static final String AUTHENTICATION_RESPONSE_403 = "The token lacks permission for this request.";

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpYouTrackClient.class);
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private static final String URI_FIXED_SEARCH_FIELDS = "idReadable,summary,customFields(name,value(name,login,fullName,presentation,text))";
    private static final String URI_EXTRA_ISSUE_FIELDS = ",description,created,updated,resolved,comments(text,created,author(login,fullName)),links(direction,linkType(name,sourceToTarget,targetToSource),issues(idReadable,summary))";
    private static final String URI_LIST_PROJECTS = "/api/admin/projects?fields=shortName,name,archived&$top=500";
    private static final String URI_SEARCH_ISSUES = "/api/issues?query=%s&fields=%s&$top=%d";
    private static final String URI_GET_ISSUE = "/api/issues/%s?fields=%s";
    private static final String URI_CREATE_ISSUE = "/api/issues?fields=idReadable,summary";
    private static final String URI_ADD_COMMENT = "/api/issues/%s/comments?fields=id,issue(idReadable)";
    private static final String URI_UPDATE_ISSUE = "/api/commands?fields=commands(description,error),issues(idReadable)";
    private static final String URI_ISSUE_PATH = "/issue/";

    private final YouTrackConfig config;
    private final HttpClient httpClient;

    public HttpYouTrackClient(YouTrackConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + path))
                .header("Authorization", "Bearer " + config.authenticationToken())
                .header("Accept", "application/json");
    }

    @Override
    public List<Project> listProjects() {
        HttpRequest request = requestBuilder(URI_LIST_PROJECTS).GET().build();
        HttpResponse<String> response = sendRequest(request);
        String responseBody = validateResponseAndExtractBody(response);
        return map(() -> YouTrackResponseMapper.projects(responseBody));
    }

    @Override
    public List<IssueSummary> searchIssues(String query, int limit) {
        if (null == query) {
            throw new NullPointerException("Query cannot be null.");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("Limit cannot be less than 1.");
        }
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedFields = URLEncoder.encode(URI_FIXED_SEARCH_FIELDS, StandardCharsets.UTF_8).replace("+", "%20");
        String fullSearchUrl = String.format(URI_SEARCH_ISSUES, encodedQuery, encodedFields, limit);
        HttpRequest request = requestBuilder(fullSearchUrl).GET().build();
        HttpResponse<String> response = sendRequest(request);
        if (400 == response.statusCode()) {
            InvalidQueryException exception = new InvalidQueryException(map(() -> YouTrackResponseMapper.queryErrorMessage(response.body())));
            LOGGER.warn(exception.getMessage());
            throw exception;
        }
        String responseBody = validateResponseAndExtractBody(response);
        return map(() -> YouTrackResponseMapper.issueSummaries(responseBody));
    }

    @Override
    public Issue getIssue(String issueId) {
        if (null == issueId) {
            throw new NullPointerException("Issue id cannot be null.");
        }
        String encodedFields = URLEncoder.encode(URI_FIXED_SEARCH_FIELDS + URI_EXTRA_ISSUE_FIELDS, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest request = requestBuilder(String.format(URI_GET_ISSUE, issueId, encodedFields)).GET().build();
        HttpResponse<String> response = sendRequest(request);
        if (404 == response.statusCode()) {
            IssueNotFoundException exception = new IssueNotFoundException(issueId);
            LOGGER.warn(exception.getMessage());
            throw exception;
        }
        String responseBody = validateResponseAndExtractBody(response);
        return map(() -> YouTrackResponseMapper.issue(responseBody));
    }

    @Override
    public CreatedIssue createIssue(String projectKey, String summary, String description) {
        if (null == projectKey) {
            throw new NullPointerException("Project key cannot be null.");
        }
        if (null == summary) {
            throw new NullPointerException("Summary cannot be null.");
        }
        ObjectNode requestBody = JSON_MAPPER.createObjectNode();
        requestBody.putObject("project").put("shortName", projectKey);
        requestBody.put("summary", summary);
        if (description != null) {
            requestBody.put("description", description);
        }
        BodyPublisher bodyPublisher = BodyPublishers.ofString(JSON_MAPPER.writeValueAsString(requestBody));
        HttpRequest request = requestBuilder(URI_CREATE_ISSUE).header("Content-Type", "application/json").POST(bodyPublisher).build();
        HttpResponse<String> response = sendRequest(request);
        if (400 == response.statusCode()) {
            RequestRejectedException requestRejectedException = new RequestRejectedException(map(() -> YouTrackResponseMapper.errorMessage(response.body())));
            LOGGER.warn(requestRejectedException.getMessage());
            throw requestRejectedException;
        }
        String responseBody = validateResponseAndExtractBody(response);
        CreatedIssue createdIssue = map(() -> YouTrackResponseMapper.createdIssue(responseBody, config.baseUrl() + URI_ISSUE_PATH));
        LOGGER.info("Created issue {}.", createdIssue.issueId());
        return createdIssue;
    }

    @Override
    public AddedComment addComment(String issueId, String comment) {
        if (null == issueId) {
            throw new NullPointerException("Issue id cannot be null.");
        }
        if (null == comment) {
            throw new NullPointerException("Comment cannot be null.");
        }
        ObjectNode requestBody = JSON_MAPPER.createObjectNode();
        requestBody.put("text", comment);
        BodyPublisher bodyPublisher = BodyPublishers.ofString(JSON_MAPPER.writeValueAsString(requestBody));
        HttpRequest request = requestBuilder(String.format(URI_ADD_COMMENT, issueId)).header("Content-Type", "application/json").POST(bodyPublisher).build();
        HttpResponse<String> response = sendRequest(request);
        if (400 == response.statusCode()) {
            RequestRejectedException requestRejectedException = new RequestRejectedException(map(() -> YouTrackResponseMapper.errorMessage(response.body())));
            LOGGER.warn(requestRejectedException.getMessage());
            throw requestRejectedException;
        }
        if (404 == response.statusCode()) {
            IssueNotFoundException exception = new IssueNotFoundException(issueId);
            LOGGER.warn(exception.getMessage());
            throw exception;
        }
        String responseBody = validateResponseAndExtractBody(response);
        AddedComment addedComment = map(() -> YouTrackResponseMapper.addedComment(responseBody, config.baseUrl() + URI_ISSUE_PATH));
        LOGGER.info("Added a comment to {}.", addedComment.issueId());
        return addedComment;
    }

    @Override
    public UpdatedIssue updateIssue(String issueId, String command) {
        if (null == issueId) {
            throw new NullPointerException("Issue id cannot be null.");
        }
        if (null == command) {
            throw new NullPointerException("Command cannot be null.");
        }
        ObjectNode requestBody = JSON_MAPPER.createObjectNode();
        ArrayNode issuesArray = JSON_MAPPER.createArrayNode();
        ObjectNode idObject = JSON_MAPPER.createObjectNode();
        requestBody.put("query", command);
        idObject.put("idReadable", issueId);
        issuesArray.add(idObject);
        requestBody.set("issues", issuesArray);
        BodyPublisher bodyPublisher = BodyPublishers.ofString(JSON_MAPPER.writeValueAsString(requestBody));
        HttpRequest request = requestBuilder(URI_UPDATE_ISSUE).header("Content-Type", "application/json").POST(bodyPublisher).build();
        HttpResponse<String> response = sendRequest(request);
        if (400 == response.statusCode()) {
            RequestRejectedException requestRejectedException = new RequestRejectedException(map(() -> YouTrackResponseMapper.commandErrorMessage(response.body())));
            LOGGER.warn("Update to issue {} rejected.", issueId);
            throw requestRejectedException;
        }
        String responseBody = validateResponseAndExtractBody(response);
        UpdatedIssue updatedIssue = map(() -> YouTrackResponseMapper.updatedIssue(responseBody, config.baseUrl() + URI_ISSUE_PATH));
        LOGGER.info("Updated issue {}.", updatedIssue.issueId());
        return updatedIssue;
    }

    private <T> T map(Supplier<T> mapping) {
        try {
            return mapping.get();
        }
        catch (UnexpectedResponseException e) {
            LOGGER.error("YouTrack's response did not have the expected shape.", e);
            throw new YouTrackUnavailableException("YouTrack's response did not have the expected shape.", e);
        }
    }

    private HttpResponse<String> sendRequest(HttpRequest httpRequest) {
        try {
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e) {
            LOGGER.error(IO_EXCEPTION_MESSAGE, e);
            throw new YouTrackUnavailableException(IO_EXCEPTION_MESSAGE, e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error(INTERRUPTED_EXCEPTION_MESSAGE, e);
            throw new YouTrackUnavailableException(INTERRUPTED_EXCEPTION_MESSAGE, e);
        }
    }

    private String validateResponseAndExtractBody(HttpResponse<String> response) {
        if ((response.statusCode() >= 200) && (response.statusCode() < 300)) {
            return response.body();
        }
        else if (401 == response.statusCode()) {
            LOGGER.error(AUTHENTICATION_RESPONSE_401);
            throw new YouTrackAuthException(AUTHENTICATION_RESPONSE_401);
        }
        else if (403 == response.statusCode()) {
            LOGGER.error(AUTHENTICATION_RESPONSE_403);
            throw new YouTrackAuthException(AUTHENTICATION_RESPONSE_403);
        }
        else {
            YouTrackUnavailableException exception = new YouTrackUnavailableException("YouTrack returned HTTP " + response.statusCode() + ".");
            LOGGER.error(exception.getMessage());
            throw exception;
        }
    }

}
