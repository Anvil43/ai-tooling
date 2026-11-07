package com.anvil43.youtrackmcp.model;

import java.util.List;

public record IssueSummary(
        String issueId,
        String summary,
        List<CustomField> customFields
) {
}
