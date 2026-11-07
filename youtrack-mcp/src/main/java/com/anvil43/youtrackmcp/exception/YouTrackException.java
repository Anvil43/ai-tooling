package com.anvil43.youtrackmcp.exception;

public sealed abstract class YouTrackException extends RuntimeException permits InvalidQueryException, IssueNotFoundException, YouTrackAuthException, YouTrackUnavailableException, RequestRejectedException {

    public YouTrackException(String message) {
        super(message);
    }
    public YouTrackException(String message, Throwable cause) { super(message, cause); }

}
