package com.anvil43.youtrackmcp.model;

import java.time.Instant;

public record IssueComment(
        String text,
        Instant created,
        User user
) {
}
