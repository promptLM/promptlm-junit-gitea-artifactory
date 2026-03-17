package dev.promptlm.testutils.gitea;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GiteaActionsDiagnosticsCollectorTest {

    @Test
    void collectsRunsJobsAndLogs() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        Logger logger = mock(Logger.class);
        AtomicInteger logCalls = new AtomicInteger();

        when(httpClient.send(argThat(request -> requestPath(request).contains("/logs")),
                any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    URI uri = request.uri();
                    String path = requestPath(request);
                    logCalls.incrementAndGet();
                    return stubByteResponse(200, ("log-" + path).getBytes(), uri);
                });

        when(httpClient.send(argThat(request -> !requestPath(request).contains("/logs")),
                any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    URI uri = request.uri();
                    String path = requestPath(request);
                    return stubStringResponse(200, responseBodyForPath(path), uri);
                });

        GiteaApiClient apiClient = new GiteaApiClient(
                httpClient,
                logger,
                () -> "http://localhost:3000/api/v1",
                () -> "token",
                new ObjectMapper());

        GiteaActions actions = new GiteaActions(apiClient, logger);
        GiteaActionsDiagnosticsCollector collector = new GiteaActionsDiagnosticsCollector(
                apiClient,
                actions,
                () -> "trace-123",
                () -> "runner-logs",
                () -> "gitea-logs",
                List::of,
                List::of);

        GiteaActionsDiagnostics diagnostics = collector.collect("owner", "repo", null);

        assertThat(diagnostics.traceId()).isEqualTo("trace-123");
        assertThat(diagnostics.giteaWorkflowFiles()).containsExactly("build.yml");
        assertThat(diagnostics.githubWorkflowFiles()).containsExactly("deploy.yml");
        assertThat(diagnostics.runs()).hasSize(2);
        assertThat(diagnostics.jobsByRunId()).containsKey(44L);
        assertThat(diagnostics.jobLogsByJobId()).containsKey(8L);
        assertThat(new String(diagnostics.jobLogsByJobId().get(8L))).contains("logs");
        assertThat(diagnostics.taskContainerLogsByJobId()).isEmpty();
        assertThat(diagnostics.giteaActionsLogFiles()).isEmpty();
        assertThat(diagnostics.runnerLogs()).contains("runner-logs");
        assertThat(diagnostics.giteaLogs()).contains("gitea-logs");
        assertThat(logCalls.get()).isEqualTo(1);
    }

    @Test
    void fallsBackToTaskContainerLogsWhenJobLogEndpointReturns404() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        Logger logger = mock(Logger.class);

        when(httpClient.send(argThat(request -> requestPath(request).contains("/jobs/8/logs")),
                any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    URI uri = request.uri();
                    return stubByteResponse(404, "missing".getBytes(), uri);
                });

        when(httpClient.send(argThat(request -> !requestPath(request).contains("/jobs/8/logs")),
                any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    URI uri = request.uri();
                    String path = requestPath(request);
                    return stubStringResponse(200, responseBodyForPath(path), uri);
                });

        GiteaApiClient apiClient = new GiteaApiClient(
                httpClient,
                logger,
                () -> "http://localhost:3000/api/v1",
                () -> "token",
                new ObjectMapper());

        GiteaActions actions = new GiteaActions(apiClient, logger);
        GiteaActionsDiagnosticsCollector collector = new GiteaActionsDiagnosticsCollector(
                apiClient,
                actions,
                () -> "trace-123",
                () -> "runner-logs",
                () -> "gitea-logs",
                () -> List.of(new GiteaActionsTaskContainerLog("cid", List.of("/gitea-actions-task-1"), null, "task-log")),
                () -> List.of(new GiteaActionsLogFile("/var/lib/gitea/actions_log/build.log", 42L, "gitea-actions-log")));

        GiteaActionsDiagnostics diagnostics = collector.collect("owner", "repo", null);

        assertThat(diagnostics.jobLogsByJobId()).doesNotContainKey(8L);
        assertThat(diagnostics.taskContainerLogsByJobId()).containsKey(8L);
        assertThat(diagnostics.taskContainerLogsByJobId().get(8L))
                .extracting(GiteaActionsTaskContainerLog::logs)
                .contains("task-log");
        assertThat(diagnostics.giteaActionsLogFiles())
                .extracting(GiteaActionsLogFile::contents)
                .contains("gitea-actions-log");
        assertThat(diagnostics.warnings().stream().anyMatch(warning -> warning.contains("404"))).isTrue();
    }

    private static String requestPath(HttpRequest request) {
        URI uri = request == null ? null : request.uri();
        return uri == null ? "" : uri.getPath();
    }

    private String responseBodyForPath(String path) {
        return RESPONSE_BODIES.stream()
                .filter(entry -> path.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("{}");
    }

    private static final java.util.List<Map.Entry<String, String>> RESPONSE_BODIES = java.util.List.of(
            Map.entry("/contents/.gitea/workflows", """
                    [{"type":"file","name":"build.yml"}]
                    """),
            Map.entry("/contents/.github/workflows", """
                    [{"type":"file","name":"deploy.yml"}]
                    """),
            Map.entry("/actions/runs/44/jobs", """
                    {
                      "workflow_jobs": [
                        {
                          "id": 7,
                          "name": "build",
                          "status": "success",
                          "conclusion": "success"
                        },
                        {
                          "id": 8,
                          "name": "deploy",
                          "status": "failure",
                          "conclusion": "failure"
                        }
                      ]
                    }
                    """),
            Map.entry("/actions/runs", """
                    {
                      "workflow_runs": [
                        {
                          "id": 44,
                          "name": "ci",
                          "status": "completed",
                          "conclusion": "failure",
                          "head_branch": "main",
                          "head_sha": "abcd1234",
                          "event": "push",
                          "html_url": "http://localhost/run/44",
                          "created_at": "2024-01-02T10:00:00Z",
                          "updated_at": "2024-01-02T10:02:00Z"
                        },
                        {
                          "id": 43,
                          "name": "ci",
                          "status": "completed",
                          "conclusion": "success",
                          "head_branch": "main",
                          "head_sha": "abcd0000",
                          "event": "push",
                          "html_url": "http://localhost/run/43",
                          "created_at": "2024-01-01T10:00:00Z",
                          "updated_at": "2024-01-01T10:02:00Z"
                        }
                      ]
                    }
                    """));

    private HttpResponse<String> stubStringResponse(int status, String body, URI uri) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.uri()).thenReturn(uri == null ? URI.create("http://localhost") : uri);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (left, right) -> true));
        when(response.previousResponse()).thenReturn(Optional.empty());
        when(response.sslSession()).thenReturn(Optional.empty());
        when(response.version()).thenReturn(HttpClient.Version.HTTP_1_1);
        when(response.request()).thenReturn(null);
        return response;
    }

    private HttpResponse<byte[]> stubByteResponse(int status, byte[] body, URI uri) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.uri()).thenReturn(uri == null ? URI.create("http://localhost") : uri);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (left, right) -> true));
        when(response.previousResponse()).thenReturn(Optional.empty());
        when(response.sslSession()).thenReturn(Optional.empty());
        when(response.version()).thenReturn(HttpClient.Version.HTTP_1_1);
        when(response.request()).thenReturn(null);
        return response;
    }
}
