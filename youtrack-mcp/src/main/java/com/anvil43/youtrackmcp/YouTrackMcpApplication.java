package com.anvil43.youtrackmcp;

import com.anvil43.youtrackmcp.tool.AddCommentTool;
import com.anvil43.youtrackmcp.tool.CreateIssueTool;
import com.anvil43.youtrackmcp.tool.GetIssueTool;
import com.anvil43.youtrackmcp.tool.ListProjectsTool;
import com.anvil43.youtrackmcp.tool.SearchIssuesTool;
import com.anvil43.youtrackmcp.tool.UpdateIssueTool;
import com.anvil43.youtrackmcp.tool.UpdateSummaryAndDescriptionTool;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;

public class YouTrackMcpApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(YouTrackMcpApplication.class);

    public static void main(String[] args) {
        try {
            YouTrackConfig youTrackConfig = YouTrackConfig.fromEnvironment();
            // DURING EXECUTION, EXECUTION IN MAIN ENDS WHILE THE SERVER CONTINUES TO RUN
            // AND ACCEPT REQUESTS. PUTTING THE HTTP CLIENT INTO A TRY-WITH-RESOURCES WOULD
            // RESULT IN THE RESOURCE AUTO-CLOSING WHEN MAIN FINISHES LEAVING THE SERVER
            // DAEMON UNABLE TO ACTUALLY SEND REQUESTS TO THE YOUTRACK API.
            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpYouTrackClient httpYouTrackClient = new HttpYouTrackClient(youTrackConfig, httpClient);
            StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
            McpSyncServer syncServer = McpServer.sync(transportProvider)
                    .serverInfo("youtrack-mcp", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .build())
                    .tools(
                        new ListProjectsTool(httpYouTrackClient).specification(),
                        new GetIssueTool(httpYouTrackClient).specification(),
                        new SearchIssuesTool(httpYouTrackClient).specification(),
                        new CreateIssueTool(httpYouTrackClient).specification(),
                        new AddCommentTool(httpYouTrackClient).specification(),
                        new UpdateIssueTool(httpYouTrackClient).specification(),
                        new UpdateSummaryAndDescriptionTool(httpYouTrackClient).specification()
                    ).build();
        }
        catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            System.exit(1);
        }
    }

}
