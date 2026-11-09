package com.anvil43.youtrackmcp;

import com.anvil43.youtrackmcp.exception.InvalidQueryException;
import com.anvil43.youtrackmcp.exception.IssueNotFoundException;
import com.anvil43.youtrackmcp.exception.RequestRejectedException;
import com.anvil43.youtrackmcp.exception.YouTrackAuthException;
import com.anvil43.youtrackmcp.exception.YouTrackUnavailableException;
import com.anvil43.youtrackmcp.model.AddedComment;
import com.anvil43.youtrackmcp.model.CreatedIssue;
import com.anvil43.youtrackmcp.model.Issue;
import com.anvil43.youtrackmcp.model.IssueSummary;
import com.anvil43.youtrackmcp.model.Project;
import com.anvil43.youtrackmcp.model.UpdatedIssue;

import java.util.List;

public interface YouTrackClient {

    /**
     * Lists the projects visible to the token, excluding archived ones, in YouTrack's order.
     *
     * @return the projects; empty if none are visible, never null
     * @throws YouTrackAuthException the token is invalid or lacks access
     * @throws YouTrackUnavailableException YouTrack couldn't be reached or answered unexpectedly
     */
    List<Project> listProjects();

    /**
     * Searches issues with a YouTrack query.
     *
     * @param query a YouTrack query, passed through as given
     * @param limit the maximum number of issues to return; at least 1
     * @return the matching issues in YouTrack's order; empty if none match, never null
     * @throws InvalidQueryException YouTrack rejected the query; {@code detail()} says why
     * @throws YouTrackAuthException the token is invalid or lacks access
     * @throws YouTrackUnavailableException YouTrack couldn't be reached or answered unexpectedly
     * @throws NullPointerException if {@code query} is null
     * @throws IllegalArgumentException if {@code limit} is less than 1
     */
    List<IssueSummary> searchIssues(String query, int limit);

    /**
     * Fetches one issue with its description, dates, custom fields, comments and links to other
     * issues.
     *
     * <p>Links come one per linked issue, in YouTrack's order. Each carries the relation as
     * YouTrack phrases it from this issue's side, such as {@code subtask of} or
     * {@code relates to}, falling back to the link type's name when that phrase is blank.
     *
     * @param issueId a readable issue ID such as {@code ABC-123}
     * @return the issue, never null
     * @throws IssueNotFoundException no issue has this ID, or the token can't see it
     * @throws YouTrackAuthException the token is invalid or lacks access
     * @throws YouTrackUnavailableException YouTrack couldn't be reached or answered unexpectedly
     * @throws NullPointerException if {@code issueId} is null
     */
    Issue getIssue(String issueId);

    /**
     * Creates an issue in a project.
     *
     * @param projectKey  a project key such as {@code ABC}; YouTrack matches it case-insensitively
     * @param summary     the summary, sent as given
     * @param description the description, sent as given; null means none is sent
     * @return the created issue, never null
     * @throws RequestRejectedException YouTrack rejected the issue; {@code detail()} says why
     * @throws YouTrackAuthException the token is invalid or lacks access
     * @throws YouTrackUnavailableException YouTrack couldn't be reached or answered unexpectedly;
     *         the issue may or may not have been created
     * @throws NullPointerException if {@code projectKey} or {@code summary} is null
     */
    CreatedIssue createIssue(String projectKey, String summary, String description);

    /**
     * Adds a comment to an issue.
     *
     * @param issueId a readable issue ID such as {@code ABC-123}; YouTrack matches it case-insensitively
     * @param comment the comment in Markdown, sent as given
     * @return the added comment, with the issue ID as YouTrack returned it; never null
     * @throws IssueNotFoundException no issue has this ID, or the token can't see it
     * @throws RequestRejectedException YouTrack rejected the comment; {@code detail()} says why
     * @throws YouTrackAuthException the token is invalid or lacks access
     * @throws YouTrackUnavailableException YouTrack couldn't be reached or answered unexpectedly;
     *         the comment may or may not have been added
     * @throws NullPointerException if {@code issueId} or {@code comment} is null
     */
    AddedComment addComment(String issueId, String comment);

    /**
     * Applies a YouTrack command to an issue, such as {@code State In Progress Assignee me}.
     * YouTrack applies the whole command or none of it.
     *
     * @param issueId a readable issue ID such as {@code ABC-123}; YouTrack matches it case-insensitively
     * @param command a YouTrack command, sent as given
     * @return the updated issue, with the issue ID as YouTrack returned it and the changes YouTrack made,
     *         one per field, in YouTrack's order, as plain text; never null
     * @throws RequestRejectedException YouTrack rejected the command, including when no issue has this
     *         ID; {@code detail()} says why, and nothing was changed
     * @throws YouTrackAuthException the token is invalid or lacks access
     * @throws YouTrackUnavailableException YouTrack couldn't be reached or answered unexpectedly;
     *         the issue may or may not have been updated
     * @throws NullPointerException if {@code issueId} or {@code command} is null
     */
    UpdatedIssue updateIssue(String issueId, String command);

}
