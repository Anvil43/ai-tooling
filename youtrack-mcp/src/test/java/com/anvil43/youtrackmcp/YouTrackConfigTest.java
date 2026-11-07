package com.anvil43.youtrackmcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class YouTrackConfigTest {

    private static final String BASE_URL = "https://youtrack.example.com";
    private static final String TOKEN = "perm:test-token-value";

    @Test
    void readsBaseUrlAndTokenFromTheEnvironment() {
        YouTrackConfig config = YouTrackConfig.from(validEnvironment());

        assertThat(config.baseUrl()).isEqualTo(BASE_URL);
        assertThat(config.authenticationToken()).isEqualTo(TOKEN);
    }

    @Test
    void ignoresUnrelatedVariables() {
        Map<String, String> environment = validEnvironment();
        environment.put("PATH", "/usr/bin");

        assertThat(YouTrackConfig.from(environment).baseUrl()).isEqualTo(BASE_URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://youtrack.example.com/", "https://youtrack.example.com//",
            "https://youtrack.example.com///"})
    void stripsTrailingSlashesFromTheBaseUrl(String withSlashes) {
        Map<String, String> environment = validEnvironment();
        environment.put(YouTrackConfig.YOUTRACK_BASE_URL, withSlashes);

        assertThat(YouTrackConfig.from(environment).baseUrl()).isEqualTo(BASE_URL);
    }

    @Test
    void keepsAPathPrefixForSelfHostedInstances() {
        Map<String, String> environment = validEnvironment();
        environment.put(YouTrackConfig.YOUTRACK_BASE_URL, "https://tracker.example.com/youtrack/");

        assertThat(YouTrackConfig.from(environment).baseUrl()).isEqualTo("https://tracker.example.com/youtrack");
    }

    @Test
    void acceptsPlainHttp() {
        Map<String, String> environment = validEnvironment();
        environment.put(YouTrackConfig.YOUTRACK_BASE_URL, "http://localhost:8080");

        assertThat(YouTrackConfig.from(environment).baseUrl()).isEqualTo("http://localhost:8080");
    }

    @Test
    void acceptsAnUppercaseScheme() {
        Map<String, String> environment = validEnvironment();
        environment.put(YouTrackConfig.YOUTRACK_BASE_URL, "HTTPS://youtrack.example.com");

        assertThat(YouTrackConfig.from(environment).baseUrl()).isEqualTo("HTTPS://youtrack.example.com");
    }

    @Test
    void nullBaseUrlPassedToTheConstructorIsRejected() {
        // from() can't produce this (require runs first); it guards direct construction.
        assertThatThrownBy(() -> new YouTrackConfig(null, TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a url", "ftp://youtrack.example.com", "https://", "youtrack.example.com", "https:///api",
            // A query or fragment would end up in front of /api/... when the client builds URLs.
            "https://youtrack.example.com?x=1", "https://youtrack.example.com#top",
            "https://tracker.example.com/youtrack?x=1", "https://youtrack.example.com/?x=1"})
    void rejectsABaseUrlThatIsNotAnHttpUrlWithAHost(String invalid) {
        Map<String, String> environment = validEnvironment();
        environment.put(YouTrackConfig.YOUTRACK_BASE_URL, invalid);

        assertThatThrownBy(() -> YouTrackConfig.from(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(YouTrackConfig.YOUTRACK_BASE_URL);
    }

    @Test
    void constructingDirectlyAlsoNormalisesAndValidates() {
        assertThat(new YouTrackConfig(BASE_URL + "/", TOKEN).baseUrl()).isEqualTo(BASE_URL);
        assertThat(new YouTrackConfig(BASE_URL + "//", TOKEN).baseUrl()).isEqualTo(BASE_URL);
        assertThatThrownBy(() -> new YouTrackConfig("ftp://host", TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(YouTrackConfig.YOUTRACK_BASE_URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {YouTrackConfig.YOUTRACK_BASE_URL, YouTrackConfig.YOUTRACK_TOKEN})
    void failsWhenAVariableIsMissing(String variable) {
        Map<String, String> environment = validEnvironment();
        environment.remove(variable);

        assertThatThrownBy(() -> YouTrackConfig.from(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(variable);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void failsWhenTheTokenIsBlank(String blank) {
        Map<String, String> environment = validEnvironment();
        environment.put(YouTrackConfig.YOUTRACK_TOKEN, blank);

        assertThatThrownBy(() -> YouTrackConfig.from(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(YouTrackConfig.YOUTRACK_TOKEN);
    }

    @Test
    void failsWhenTheBaseUrlIsBlank() {
        Map<String, String> environment = validEnvironment();
        environment.put(YouTrackConfig.YOUTRACK_BASE_URL, " ");

        assertThatThrownBy(() -> YouTrackConfig.from(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(YouTrackConfig.YOUTRACK_BASE_URL);
    }

    @Test
    void toStringNeverContainsTheToken() {
        YouTrackConfig config = YouTrackConfig.from(validEnvironment());

        assertThat(config.toString())
                .contains(BASE_URL)
                .doesNotContain(TOKEN);
    }

    private static Map<String, String> validEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put(YouTrackConfig.YOUTRACK_BASE_URL, BASE_URL);
        environment.put(YouTrackConfig.YOUTRACK_TOKEN, TOKEN);
        return environment;
    }
}
