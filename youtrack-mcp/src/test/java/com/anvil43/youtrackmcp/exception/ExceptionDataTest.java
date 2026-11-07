package com.anvil43.youtrackmcp.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract: design.md, "Errors", message format. Data the tools need lives in accessors; the
 * message is a sentence built from it (checked by key phrase, not exact wording).
 */
class ExceptionDataTest {

    @Test
    void issueNotFoundCarriesTheIdAndASentence() {
        IssueNotFoundException e = new IssueNotFoundException("DEMO-99999");

        assertThat(e.issueId()).isEqualTo("DEMO-99999");
        assertThat(e.getMessage()).containsIgnoringCase("DEMO-99999 was not found").endsWith(".");
    }

    @Test
    void invalidQueryCarriesYouTracksTextUneditedAndASentence() {
        InvalidQueryException e = new InvalidQueryException("Can't parse search query, please check and update query syntax");

        assertThat(e.detail()).isEqualTo("Can't parse search query, please check and update query syntax");
        assertThat(e.getMessage()).containsIgnoringCase("rejected the query: Can't parse search query, please check and update query syntax").endsWith(".");
    }

    @Test
    void invalidQueryDoesNotDoubleAPeriodYouTrackAlreadyWrote() {
        InvalidQueryException e = new InvalidQueryException("Value expected for field project.");

        assertThat(e.detail()).isEqualTo("Value expected for field project.");
        assertThat(e.getMessage()).endsWith("project.").doesNotContain("..");
    }

    // Synthetic: no real YouTrack message ending in ? or ! has been seen; the real wording
    // with its ending changed.
    @ParameterizedTest
    @ValueSource(strings = {"Value expected for field project?", "Value expected for field project!"})
    void invalidQueryMessageDoesNotAddAPeriodAfterAQuestionOrExclamationMark(String detail) {
        InvalidQueryException e = new InvalidQueryException(detail);

        assertThat(e.getMessage()).endsWith(detail).doesNotContain("?.").doesNotContain("!.");
    }

    @Test
    void requestRejectionCarriesYouTracksTextUneditedAndASentence() {
        RequestRejectedException e = new RequestRejectedException("Test Field is required");

        assertThat(e.detail()).isEqualTo("Test Field is required");
        assertThat(e.getMessage()).containsIgnoringCase("rejected the request: Test Field is required").endsWith(".");
    }

    // Synthetic for ? and !, as above.
    @ParameterizedTest
    @ValueSource(strings = {"Value expected for field project.", "Value expected for field project?",
            "Value expected for field project!"})
    void requestRejectionMessageDoesNotAddAPeriodAfterEndingPunctuation(String detail) {
        RequestRejectedException e = new RequestRejectedException(detail);

        assertThat(e.getMessage()).endsWith(detail).doesNotContain("..").doesNotContain("?.").doesNotContain("!.");
    }
}
