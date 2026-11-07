package com.anvil43.youtrackmcp.exception;

public final class IssueNotFoundException extends YouTrackException {

    private static final String ERROR_MESSAGE = "Issue %s was not found.";

    private final String issueId;

    public String issueId() {
        return issueId;
    }

    public IssueNotFoundException(String issueId) {
        this.issueId = issueId;
        super(String.format(ERROR_MESSAGE, issueId));
    }

}
