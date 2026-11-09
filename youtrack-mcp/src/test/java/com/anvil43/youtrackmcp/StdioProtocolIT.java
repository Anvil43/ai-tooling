package com.anvil43.youtrackmcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Launches the shaded jar the way Claude Code does (a child process speaking JSON-RPC over
 * stdin/stdout) and checks the protocol from the outside. Runs under Failsafe, after `package`.
 * WireMock stands in for YouTrack, so a tools/call goes through the real HttpYouTrackClient.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StdioProtocolIT {

    private static final String PROTOCOL_VERSION = "2025-11-25";
    private static final Path JAR = Path.of(System.getProperty("youtrack.mcp.jar", "target/youtrack-mcp.jar"));
    private static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    static WireMockExtension youTrack = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @TempDir
    Path logDir;

    private ServerProcess server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.destroy();
        }
    }

    @Test
    void handshakeAdvertisesToolsButNotResourcesOrPrompts() throws Exception {
        server = ServerProcess.start(validEnvironment());

        JsonNode response = server.request(1, "initialize", initializeParams());

        JsonNode result = response.path("result");
        assertThat(result.path("protocolVersion").asString()).isEqualTo(PROTOCOL_VERSION);
        assertThat(result.path("serverInfo").path("name").asString()).isEqualTo("youtrack-mcp");

        JsonNode capabilities = result.path("capabilities");
        assertThat(capabilities.has("tools")).isTrue();
        assertThat(capabilities.path("tools").path("listChanged").asBoolean(true)).isFalse();
        assertThat(capabilities.has("resources")).isFalse();
        assertThat(capabilities.has("prompts")).isFalse();
    }

    @Test
    void listsTheRegisteredTools() throws Exception {
        server = ServerProcess.start(validEnvironment());
        server.initialize();

        JsonNode response = server.request(2, "tools/list", "{}");

        JsonNode tools = response.path("result").path("tools");
        assertThat(tools.isArray()).isTrue();
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("name").asString()));
        // Add each tool here as it is registered.
        assertThat(names).containsExactlyInAnyOrder("list_projects", "search_issues", "get_issue", "create_issue", "add_comment",
                "update_issue", "update_summary_and_description");
    }

    @Test
    void sdkRejectsArgumentsThatBreakTheInputSchema() throws Exception {
        server = ServerProcess.start(validEnvironment());
        server.initialize();

        JsonNode response = server.rawRequest(2, "tools/call", """
                {"name":"list_projects","arguments":{"name":42}}""");

        // SDK 2.1.0 answers with an isError result, not a JSON-RPC error, and never reaches the
        // handler: the stub client would have produced the tool's internal-error text instead.
        assertThat(response.has("error")).as("not a JSON-RPC error: %s", response).isFalse();
        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean(false)).isTrue();
        String text = result.path("content").path(0).path("text").asString();
        assertThat(text)
                .contains("input validation failed")
                .contains("/name")
                .doesNotContain("Internal error in youtrack-mcp");
    }

    @ParameterizedTest
    @CsvSource({"search_issues, query", "get_issue, issueId", "create_issue, project", "add_comment, issueId",
            "update_issue, issueId", "update_summary_and_description, issueId"})
    void sdkRejectsAMissingRequiredArgument(String toolName, String argumentName) throws Exception {
        server = ServerProcess.start(validEnvironment());
        server.initialize();

        JsonNode response = server.rawRequest(2, "tools/call", """
                {"name":"%s","arguments":{}}""".formatted(toolName));

        // SDK 2.1.0 enforces "required" before the handler runs, so the tool's own
        // "<argument> is required." text is reached only for null or blank values.
        assertThat(response.has("error")).as("not a JSON-RPC error: %s", response).isFalse();
        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean(false)).isTrue();
        String text = result.path("content").path(0).path("text").asString();
        assertThat(text)
                .contains("input validation failed")
                .contains("required property '" + argumentName + "' not found")
                .doesNotContain(argumentName + " is required.");
        assertThat(youTrack.getAllServeEvents()).as("YouTrack was called").isEmpty();
    }

    @Test
    void listProjectsCallsYouTrackAndReturnsTheProjects() throws Exception {
        youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects")).willReturn(okJson(fixture("projects.json"))));
        server = ServerProcess.start(validEnvironment());
        server.initialize();

        JsonNode result = callListProjects(2);

        // Also guards main's HttpClient: if it were closed when main returns, this call would
        // fail with "could not be reached" instead.
        assertThat(result.path("isError").asBoolean(false)).as("tool error: %s", result).isFalse();
        assertThat(result.path("content").path(0).path("text").asString()).isEqualTo("""
                2 projects:
                DEMO: Demo Project
                WEB: Website
                If the user hasn't said which of these to use, ask them.""");
        youTrack.verify(getRequestedFor(urlPathEqualTo("/api/admin/projects"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    @Test
    void rejectedTokenIsAToolErrorAndTheServerKeepsRunning() throws Exception {
        stubRejectedToken();
        server = ServerProcess.start(validEnvironment());
        server.initialize();

        JsonNode first = callListProjects(2);
        JsonNode second = callListProjects(3);

        for (JsonNode result : List.of(first, second)) {
            assertThat(result.path("isError").asBoolean(false)).isTrue();
            assertThat(result.path("content").path(0).path("text").asString()).isEqualTo(
                    "YouTrack refused the request: the token is invalid or lacks access. Tell the user; retrying won't help.");
        }
    }

    @Test
    void stdoutCarriesOnlyJsonRpcMessages() throws Exception {
        server = ServerProcess.start(validEnvironment());
        server.initialize();
        server.request(2, "tools/list", "{}");

        server.closeStdin();
        server.awaitExit();

        assertThat(server.stdoutLines()).isNotEmpty();
        for (String line : server.stdoutLines()) {
            JsonNode message = parse(line);
            assertThat(message.isObject()).as("stdout line is a JSON object: %s", line).isTrue();
            assertThat(message.path("jsonrpc").asString()).as("stdout line is JSON-RPC 2.0: %s", line).isEqualTo("2.0");
        }
    }

    @Test
    void exitsCleanlyWhenStdinCloses() throws Exception {
        server = ServerProcess.start(validEnvironment());
        server.initialize();

        server.closeStdin();
        int exitCode = server.awaitExit();

        assertThat(exitCode).isZero();
    }

    @Test
    void failsFastWithAClearErrorWhenConfigIsMissing() throws Exception {
        server = ServerProcess.start(Map.of("LOG_DIR", logDir.toString()));

        int exitCode = server.awaitExit();

        assertThat(exitCode).isEqualTo(1);
        assertThat(server.stdoutLines()).as("nothing may reach stdout, even on failure").isEmpty();
        assertThat(server.stderr()).contains("Missing environment variable: " + YouTrackConfig.YOUTRACK_BASE_URL);
    }

    @Test
    void neverWritesTheTokenToStderrOrTheLogFile() throws Exception {
        // A rejected token makes the client log at ERROR, so the log has something in it to check.
        stubRejectedToken();
        server = ServerProcess.start(validEnvironment());
        server.initialize();
        server.request(2, "tools/list", "{}");
        callListProjects(3);
        server.closeStdin();
        server.awaitExit();

        assertThat(server.stderr()).doesNotContain(TOKEN);
        Path logFile = logDir.resolve("youtrack-mcp.log");
        if (Files.exists(logFile)) {
            assertThat(Files.readString(logFile)).doesNotContain(TOKEN);
        }
    }

    // --- helpers -------------------------------------------------------------------------

    private static final String TOKEN = "perm:protocol-test-token";

    private Map<String, String> validEnvironment() {
        return Map.of(
                YouTrackConfig.YOUTRACK_BASE_URL, youTrack.baseUrl(),
                YouTrackConfig.YOUTRACK_TOKEN, TOKEN,
                "LOG_DIR", logDir.toString());
    }

    private static String initializeParams() {
        return """
                {"protocolVersion":"%s","capabilities":{},"clientInfo":{"name":"StdioProtocolIT","version":"0"}}"""
                .formatted(PROTOCOL_VERSION);
    }

    /** Calls list_projects with no arguments and returns the tool result. */
    private JsonNode callListProjects(int id) throws Exception {
        return server.request(id, "tools/call", """
                {"name":"list_projects","arguments":{}}""").path("result");
    }

    private static void stubRejectedToken() {
        youTrack.stubFor(get(urlPathEqualTo("/api/admin/projects"))
                .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                        .withBody(fixture("error-401.json"))));
    }

    private static String fixture(String name) {
        try (InputStream in = StdioProtocolIT.class.getResourceAsStream("/youtrack/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (RuntimeException e) {
            return fail("stdout line is not JSON: " + line, e);
        }
    }

    /** The server as a child process, with stdout collected line by line and stderr drained. */
    private static final class ServerProcess {

        private static final long RESPONSE_TIMEOUT_SECONDS = 15;
        private static final long EXIT_TIMEOUT_SECONDS = 15;

        private final Process process;
        private final Writer stdin;
        private final BlockingQueue<String> pendingLines = new LinkedBlockingQueue<>();
        private final List<String> allLines = new CopyOnWriteArrayList<>();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final Thread stdoutReader;
        private final Thread stderrReader;

        private ServerProcess(Process process) {
            this.process = process;
            this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            this.stdoutReader = Thread.ofVirtual().start(this::readStdout);
            this.stderrReader = Thread.ofVirtual().start(() -> drain(process.getErrorStream()));
        }

        static ServerProcess start(Map<String, String> environment) throws IOException {
            if (!Files.isRegularFile(JAR)) {
                fail("Shaded jar not found at " + JAR + "; run `mvn verify`");
            }
            String java = ProcessHandle.current().info().command().orElse("java");
            ProcessBuilder builder = new ProcessBuilder(java, "-jar", JAR.toString());
            // Start from an empty environment so the developer's own YOUTRACK_* values never leak in.
            builder.environment().clear();
            builder.environment().putAll(environment);
            return new ServerProcess(builder.start());
        }

        void initialize() throws Exception {
            request(1, "initialize", initializeParams());
            notify("notifications/initialized");
        }

        /** Sends a request and returns its response, failing the test if it is a JSON-RPC error. */
        JsonNode request(int id, String method, String paramsJson) throws Exception {
            JsonNode message = rawRequest(id, method, paramsJson);
            assertThat(message.has("error")).as("%s returned an error: %s", method, message).isFalse();
            return message;
        }

        /** Sends a request and returns its response, error or not. */
        JsonNode rawRequest(int id, String method, String paramsJson) throws Exception {
            send("""
                    {"jsonrpc":"2.0","id":%d,"method":"%s","params":%s}""".formatted(id, method, paramsJson));
            while (true) {
                String line = pendingLines.poll(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (line == null) {
                    return fail("No response to %s within %ds. stderr:%n%s", method, RESPONSE_TIMEOUT_SECONDS, stderr());
                }
                JsonNode message = parse(line);
                if (message.path("id").asInt(-1) == id) {
                    return message;
                }
            }
        }

        void notify(String method) throws IOException {
            send("""
                    {"jsonrpc":"2.0","method":"%s"}""".formatted(method));
        }

        void closeStdin() throws IOException {
            stdin.close();
        }

        int awaitExit() throws InterruptedException {
            if (!process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                fail("Server did not exit within %ds. stderr:%n%s", EXIT_TIMEOUT_SECONDS, stderr());
            }
            stdoutReader.join();
            stderrReader.join();
            return process.exitValue();
        }

        List<String> stdoutLines() {
            return List.copyOf(allLines);
        }

        String stderr() {
            synchronized (stderr) {
                return stderr.toString(StandardCharsets.UTF_8);
            }
        }

        void destroy() {
            process.destroyForcibly();
        }

        private void send(String json) throws IOException {
            stdin.write(json);
            stdin.write('\n');
            stdin.flush();
        }

        private void readStdout() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    allLines.add(line);
                    pendingLines.add(line);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void drain(InputStream stream) {
            byte[] buffer = new byte[8192];
            try (stream) {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    synchronized (stderr) {
                        stderr.write(buffer, 0, read);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
