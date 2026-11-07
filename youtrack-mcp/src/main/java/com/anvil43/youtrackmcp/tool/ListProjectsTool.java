package com.anvil43.youtrackmcp.tool;

import com.anvil43.youtrackmcp.YouTrackClient;
import com.anvil43.youtrackmcp.exception.YouTrackException;
import com.anvil43.youtrackmcp.model.Project;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ListProjectsTool {

    private static final String INPUT_SCHEMA = """
            {
                "type" : "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Optional. Case insensitive part of the project name to filter by."
                    }
                }
            }
            """;

    private static final String DESCRIPTION = """
            Lists the YouTrack projects visible to this server's token, as KEY: Name. Archived projects are excluded. Use the key in search_issues queries ("project: <KEY>") and when creating issues. Never guess which project the user means: if they haven't named one, show them this list and ask.""";

    private static final Logger LOGGER = LoggerFactory.getLogger(ListProjectsTool.class);

    private final Tool tool = Tool.builder("list_projects", McpJsonDefaults.getMapper(), INPUT_SCHEMA)
            .description(DESCRIPTION).build();

    private final YouTrackClient youTrackClient;

    public ListProjectsTool(YouTrackClient youTrackClient) {
        this.youTrackClient = youTrackClient;
    }

    public SyncToolSpecification specification() {
        return SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> handle(request.arguments()))
            .build();
    }

    CallToolResult handle(Map<String, Object> arguments) {
        try {
            List<Project> projects = youTrackClient.listProjects();
            String nameFilter = generateNameFilter(arguments);
            List<Project> filteredProjects = generateFilteredProjects(projects, nameFilter);
            List<String> output = new ArrayList<>();
            output.add(generateProjectCount(filteredProjects, nameFilter));
            filteredProjects.forEach(project -> output.add(project.shortName() + ": " + project.name()));
            if (!filteredProjects.isEmpty()) {
                output.add("If the user hasn't said which of these to use, ask them.");
            }
            return ToolResults.text(String.join("\n", output));
        }
        catch (YouTrackException e) {
            return ToolResults.error(e);
        }
        catch (Exception e) {
            LOGGER.error("list_projects failed", e);
            return ToolResults.internalError();
        }
    }

    private boolean hasFilter(Map<String, Object> arguments) {
        if ((null == arguments) || !arguments.containsKey("name")) {
            return false;
        }
        if (arguments.get("name") instanceof String name) {
            return !name.isBlank();
        }
        return false;
    }

    private String generateNameFilter(Map<String, Object> arguments) {
        if (hasFilter(arguments)) {
            return arguments.get("name").toString().strip();
        }
        else {
            return null;
        }
    }

    private List<Project> generateFilteredProjects(List<Project> projects, String nameFilter) {
        List<Project> filteredProjects = new ArrayList<>();
        projects.forEach(project -> {
            if (null == nameFilter) {
                filteredProjects.add(project);
            }
            else if (project.name().toUpperCase(Locale.ROOT).contains(nameFilter.toUpperCase(Locale.ROOT))) {
                filteredProjects.add(project);
            }
        });
        return filteredProjects;
    }

    private String generateProjectCount(List<Project> filteredProjects, String nameFilter) {
        if ((null != nameFilter) && filteredProjects.isEmpty()) {
            return "No projects matched \"" + nameFilter + "\".";
        }
        else if (filteredProjects.isEmpty()) {
            return "No projects are visible to this token.";
        }
        else if (filteredProjects.size() == 1) {
            return "1 project:";
        }
        else {
            return filteredProjects.size() + " projects:";
        }
    }

}
