package com.anvil43.youtrackmcp.exception;

public final class InvalidQueryException extends YouTrackException {

    private static final String ERROR_MESSAGE = "YouTrack rejected the query: %s";

    private final String detail;

    public String detail() {
        return detail;
    }

    public InvalidQueryException(String detail) {
        this.detail = detail;
        String errorMessage = String.format(ERROR_MESSAGE, detail);
        if (!errorMessage.endsWith(".") && !errorMessage.endsWith("!") && !errorMessage.endsWith("?")) {
            errorMessage = errorMessage.concat(".");;
        }
        super(errorMessage);
    }

}
