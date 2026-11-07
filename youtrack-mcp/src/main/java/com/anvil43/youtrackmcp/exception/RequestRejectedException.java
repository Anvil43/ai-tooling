package com.anvil43.youtrackmcp.exception;

public final class RequestRejectedException extends YouTrackException {

    private static final String ERROR_MESSAGE = "YouTrack rejected the request: %s";

    private final String detail;

    public String detail() {
        return detail;
    }

    public RequestRejectedException(String detail) {
        this.detail = detail;
        String errorMessage = String.format(ERROR_MESSAGE, detail);
        if (!errorMessage.endsWith(".") && !errorMessage.endsWith("!") && !errorMessage.endsWith("?")) {
            errorMessage = errorMessage.concat(".");
        }
        super(errorMessage);
    }

}
