package com.anvil43.youtrackmcp.model;

import java.time.Instant;
import java.util.List;

public record Issue(
        String issueId,
        String summary,
        String description,
        Instant created,
        Instant updated,
        Instant resolved,
        List<CustomField> customFields,
        List<IssueComment> issueComments
) {
}
