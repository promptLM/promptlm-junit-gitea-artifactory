package dev.promptlm.testutils.gitea;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GiteaActionsTest {

    @Test
    void waitsForWorkflowRunByShaPrefix() throws Exception {
        GiteaActions actions = buildActionsClient();

        GiteaActions.ActionExecutionReport report = actions.waitForWorkflowRunBySha(
                "owner",
                "repo",
                "abcd",
                Duration.ofSeconds(1),
                Duration.ofMillis(10));

        assertThat(report.run().id()).isEqualTo(42L);
        assertThat(report.run().headSha()).startsWith("abcd");
        assertThat(report.jobs()).hasSize(1);
        assertThat(report.jobStateSummary()).containsEntry("completed", 1L);
        assertThat(report.allJobsTerminal()).isTrue();
    }

    @Test
    void downloadsJobLogs() throws Exception {
        GiteaActions actions = buildActionsClient();

        byte[] logs = actions.downloadWorkflowJobLogs("owner", "repo", 42L, 7L);
        assertThat(new String(logs)).contains("hello-job");
    }

    @Test
    void rejectsBlankCommitSha() {
        GiteaActions actions = buildActionsClient();

        assertThatThrownBy(() -> actions.waitForWorkflowRunBySha(
                "owner",
                "repo",
                " ",
                Duration.ofSeconds(1),
                Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commitSha");
    }

    @Test
    void timesOutWhenNoRunsAppear() {
        GiteaActions actions = buildActionsClientWithRuns("{\"workflow_runs\": []}");

        assertThatThrownBy(() -> actions.waitForWorkflowRunBySha(
                "owner",
                "repo",
                "abcd",
                Duration.ofMillis(200),
                Duration.ofMillis(50)))
                .isInstanceOf(GiteaWorkflowException.class)
                .hasMessageContaining("Timed out waiting for workflow run");
    }

    @Test
    void acceptsLegacyWorkflowJobsArray() throws Exception {
        GiteaActions actions = buildActionsClientWithJobs("""
                {
                  "workflow_jobs": [
                    {
                      "id": 7,
                      "name": "build",
                      "status": "success",
                      "conclusion": "success",
                      "runner_name": "runner-1"
                    }
                  ]
                }
                """);

        assertThat(actions.listWorkflowJobs("owner", "repo", 42L))
                .singleElement()
                .extracting(GiteaActions.ActionJobSummary::name)
                .isEqualTo("build");
    }

    private GiteaActions buildActionsClient() {
        HttpClient httpClient = mock(HttpClient.class);
        Logger logger = mock(Logger.class);

        try {
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenAnswer(invocation -> {
                        HttpRequest request = invocation.getArgument(0);
                        URI uri = request.uri();
                        String path = uri == null ? "" : uri.getPath();
                        if (path.contains("/logs")) {
                            return stubByteResponse(200, "hello-job".getBytes(), uri);
                        }

                        return stubStringResponse(200, responseBodyForPath(path), uri);
                    });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        GiteaApiClient apiClient = new GiteaApiClient(
                httpClient,
                logger,
                () -> "http://localhost:3000/api/v1",
                () -> "token",
                new ObjectMapper());

        return new GiteaActions(apiClient, logger);
    }

    private GiteaActions buildActionsClientWithRuns(String runsBody) {
        HttpClient httpClient = mock(HttpClient.class);
        Logger logger = mock(Logger.class);

        try {
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenAnswer(invocation -> {
                        HttpRequest request = invocation.getArgument(0);
                        URI uri = request.uri();
                        String path = uri == null ? "" : uri.getPath();
                        if (path.contains("/logs")) {
                            return stubByteResponse(200, "hello-job".getBytes(), uri);
                        }
                        if (path.contains("/actions/runs")) {
                            return stubStringResponse(200, runsBody, uri);
                        }
                        return stubStringResponse(200, responseBodyForPath(path), uri);
                    });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        GiteaApiClient apiClient = new GiteaApiClient(
                httpClient,
                logger,
                () -> "http://localhost:3000/api/v1",
                () -> "token",
                new ObjectMapper());

        return new GiteaActions(apiClient, logger);
    }

    private GiteaActions buildActionsClientWithJobs(String jobsBody) throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        Logger logger = mock(Logger.class);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    URI uri = request.uri();
                    String path = uri == null ? "" : uri.getPath();
                    if (path.contains("/logs")) {
                        return stubByteResponse(200, "hello-job".getBytes(), uri);
                    }
                    if (path.contains("/actions/runs/42/jobs")) {
                        return stubStringResponse(200, jobsBody, uri);
                    }
                    return stubStringResponse(200, responseBodyForPath(path), uri);
                });

        GiteaApiClient apiClient = new GiteaApiClient(
                httpClient,
                logger,
                () -> "http://localhost:3000/api/v1",
                () -> "token",
                new ObjectMapper());

        return new GiteaActions(apiClient, logger);
    }

    private String responseBodyForPath(String path) {
        if (path.contains("/actions/runs/42/jobs")) {
            return "{" +
                    "\"jobs\":[{" +
                    "\"id\":7," +
                    "\"name\":\"build\"," +
                    "\"status\":\"completed\"," +
                    "\"conclusion\":\"success\"," +
                    "\"started_at\":\"2024-01-01T10:00:00Z\"," +
                    "\"completed_at\":\"2024-01-01T10:01:00Z\"," +
                    "\"runner_name\":\"runner-1\"" +
                    "}]" +
                    "}";
        }
        if (path.contains("/actions/runs")) {
            return "{" +
                    "\"workflow_runs\":[{" +
                    "\"id\":42," +
                    "\"name\":\"ci\"," +
                    "\"status\":\"completed\"," +
                    "\"conclusion\":\"success\"," +
                    "\"head_branch\":\"main\"," +
                    "\"head_sha\":\"abcd1234deadbeef\"," +
                    "\"event\":\"push\"," +
                    "\"html_url\":\"http://localhost/run/42\"," +
                    "\"created_at\":\"2024-01-01T10:00:00Z\"," +
                    "\"updated_at\":\"2024-01-01T10:02:00Z\"" +
                    "}]" +
                    "}";
        }
        return "{}";
    }

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
