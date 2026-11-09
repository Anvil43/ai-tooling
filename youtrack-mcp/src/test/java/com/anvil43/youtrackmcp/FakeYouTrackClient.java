package com.anvil43.youtrackmcp;

import com.anvil43.youtrackmcp.model.AddedComment;
import com.anvil43.youtrackmcp.model.CreatedIssue;
import com.anvil43.youtrackmcp.model.Issue;
import com.anvil43.youtrackmcp.model.IssueSummary;
import com.anvil43.youtrackmcp.model.Project;
import com.anvil43.youtrackmcp.model.UpdatedIssue;
import com.anvil43.youtrackmcp.model.UpdatedText;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link YouTrackClient} for tool tests. Each call returns what the test set up, or
 * throws the failure the test set up, and records how it was called.
 */
public final class FakeYouTrackClient implements YouTrackClient {

    private List<Project> projects = List.of();
    private List<IssueSummary> issueSummaries = List.of();
    private Issue issue;
    private CreatedIssue createdIssue;
    private AddedComment addedComment;
    private UpdatedIssue updatedIssue;
    private UpdatedText updatedText;
    private RuntimeException failure;
    private final List<String> calls = new ArrayList<>();

    public FakeYouTrackClient withProjects(Project... projects) {
        this.projects = List.of(projects);
        return this;
    }

    /** What {@code searchIssues} returns, whatever the query or limit. */
    public FakeYouTrackClient withIssueSummaries(List<IssueSummary> issueSummaries) {
        this.issueSummaries = List.copyOf(issueSummaries);
        return this;
    }

    /** What {@code getIssue} returns, whatever the ID. */
    public FakeYouTrackClient withIssue(Issue issue) {
        this.issue = issue;
        return this;
    }

    /** What {@code createIssue} returns, whatever the arguments. */
    public FakeYouTrackClient withCreatedIssue(CreatedIssue createdIssue) {
        this.createdIssue = createdIssue;
        return this;
    }

    /** What {@code addComment} returns, whatever the arguments. */
    public FakeYouTrackClient withAddedComment(AddedComment addedComment) {
        this.addedComment = addedComment;
        return this;
    }

    /** What {@code updateIssue} returns, whatever the arguments. */
    public FakeYouTrackClient withUpdatedIssue(UpdatedIssue updatedIssue) {
        this.updatedIssue = updatedIssue;
        return this;
    }

    /** What {@code updateSummaryAndDescription} returns, whatever the arguments. */
    public FakeYouTrackClient withUpdatedText(UpdatedText updatedText) {
        this.updatedText = updatedText;
        return this;
    }

    /** Every call throws {@code failure} from now on. */
    public FakeYouTrackClient failingWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    /**
     * One entry per call, with its arguments: {@code "listProjects"},
     * {@code "searchIssues(\"project: DEMO\", 51)"}, {@code "getIssue(\"DEMO-1\")"},
     * {@code "createIssue(\"DEMO\", \"Summary\", null)"} (a null argument is written unquoted),
     * {@code "addComment(\"DEMO-13\", \"Text\")"}, {@code "updateIssue(\"DEMO-13\", \"State Fixed\")"},
     * {@code "updateSummaryAndDescription(\"DEMO-13\", \"Summary\", null)"}.
     */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public List<Project> listProjects() {
        calls.add("listProjects");
        throwIfFailing();
        return projects;
    }

    @Override
    public List<IssueSummary> searchIssues(String query, int limit) {
        calls.add("searchIssues(\"" + query + "\", " + limit + ")");
        throwIfFailing();
        return issueSummaries;
    }

    @Override
    public Issue getIssue(String issueId) {
        calls.add("getIssue(\"" + issueId + "\")");
        throwIfFailing();
        if (issue == null) {
            throw new IllegalStateException("FakeYouTrackClient: no issue set up");
        }
        return issue;
    }

    @Override
    public CreatedIssue createIssue(String projectKey, String summary, String description) {
        calls.add("createIssue(" + quoted(projectKey) + ", " + quoted(summary) + ", " + quoted(description) + ")");
        throwIfFailing();
        if (createdIssue == null) {
            throw new IllegalStateException("FakeYouTrackClient: no created issue set up");
        }
        return createdIssue;
    }

    @Override
    public AddedComment addComment(String issueId, String comment) {
        calls.add("addComment(" + quoted(issueId) + ", " + quoted(comment) + ")");
        throwIfFailing();
        if (addedComment == null) {
            throw new IllegalStateException("FakeYouTrackClient: no added comment set up");
        }
        return addedComment;
    }

    @Override
    public UpdatedIssue updateIssue(String issueId, String command) {
        calls.add("updateIssue(" + quoted(issueId) + ", " + quoted(command) + ")");
        throwIfFailing();
        if (updatedIssue == null) {
            throw new IllegalStateException("FakeYouTrackClient: no updated issue set up");
        }
        return updatedIssue;
    }

    @Override
    public UpdatedText updateSummaryAndDescription(String issueId, String summary, String description) {
        calls.add("updateSummaryAndDescription(" + quoted(issueId) + ", " + quoted(summary) + ", " + quoted(description) + ")");
        throwIfFailing();
        if (updatedText == null) {
            throw new IllegalStateException("FakeYouTrackClient: no updated text set up");
        }
        return updatedText;
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private void throwIfFailing() {
        if (failure != null) {
            throw failure;
        }
    }
}
