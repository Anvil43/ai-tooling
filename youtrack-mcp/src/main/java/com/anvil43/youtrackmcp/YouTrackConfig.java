package com.anvil43.youtrackmcp;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

public record YouTrackConfig(String baseUrl, String authenticationToken) {

    public static final String YOUTRACK_BASE_URL = "YOUTRACK_BASE_URL";
    public static final String YOUTRACK_TOKEN = "YOUTRACK_TOKEN";

    public YouTrackConfig {
        if (null == baseUrl) {
            throw new IllegalArgumentException("baseUrl cannot be null");
        }
        baseUrl = normalizeBaseUrl(baseUrl);
        validateBaseUrl(baseUrl);
    }

    public static YouTrackConfig fromEnvironment() {
        return from(System.getenv());
    }

    static YouTrackConfig from(Map<String, String> environment) {
        require(environment, YOUTRACK_BASE_URL);
        require(environment, YOUTRACK_TOKEN);
        return new YouTrackConfig(environment.get(YOUTRACK_BASE_URL), environment.get(YOUTRACK_TOKEN));
    }

    private static void require(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if ((value == null) || (value.isBlank())) {
            throw new IllegalStateException("Missing environment variable: " + key);
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private static void validateBaseUrl(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            if (null == uri.getScheme()) {
                throw new IllegalStateException(generateBaseUrlErrorMessage("Missing scheme", baseUrl));
            }
            if ((!uri.getScheme().toLowerCase(Locale.ROOT).equals("https"))
                    && (!uri.getScheme().toLowerCase(Locale.ROOT).equals("http"))) {
                throw new IllegalStateException(generateBaseUrlErrorMessage("Invalid scheme", baseUrl));
            }
            if (null == uri.getHost()) {
                throw new IllegalStateException(generateBaseUrlErrorMessage("Missing host", baseUrl));
            }
            if ((uri.getQuery() != null) || (uri.getFragment() != null)) {
                throw new IllegalStateException(generateBaseUrlErrorMessage("Query or fragment present in base url", baseUrl));
            }
        }
        catch (IllegalArgumentException e) {
            throw new IllegalStateException(generateBaseUrlErrorMessage("Invalid URI", baseUrl), e);
        }
    }

    private static String generateBaseUrlErrorMessage(String errorMessage, String baseUrl) {
        return YOUTRACK_BASE_URL  + " " + errorMessage + ", was: " + baseUrl;
    }

    @Override
    public String toString() {
        return "YouTrackConfig [baseUrl=" + baseUrl + ", authenticationToken=********]";
    }

}
