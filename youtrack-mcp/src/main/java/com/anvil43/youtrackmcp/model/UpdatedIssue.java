package com.anvil43.youtrackmcp.model;

import java.util.List;

public record UpdatedIssue(
        String issueId,
        List<String> changes,
        String url
) {

    public UpdatedIssue {
        changes = List.copyOf(changes);
    }

}
