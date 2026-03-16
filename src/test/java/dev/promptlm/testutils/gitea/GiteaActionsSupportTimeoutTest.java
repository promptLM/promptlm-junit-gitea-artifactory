package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GiteaActionsSupportTimeoutTest {

    @Test
    void retriesDeletingRunAfterCancellationWhenGiteaReportsRunStillActive() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        Logger logger = mock(Logger.class);
        AtomicInteger deleteAttempts = new AtomicInteger();

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    URI uri = request.uri();
                    String path = uri == null ? "" : uri.getPath();
                    String method = request.method();

                    if ("GET".equals(method) && path.endsWith("/repos/owner/repo/actions/runs")) {
                        return stubStringResponse(200, "{\"workflow_runs\":[{\"id\":7}]}", uri);
                    }
                    if ("POST".equals(method) && path.endsWith("/repos/owner/repo/actions/runs/7/cancel")) {
                        return stubStringResponse(202, "", uri);
                    }
                    if ("DELETE".equals(method) && path.endsWith("/repos/owner/repo/actions/runs/7")) {
                        if (deleteAttempts.getAndIncrement() == 0) {
                            return stubStringResponse(400, "{\"message\":\"this workflow run is not done\"}", uri);
                        }
                        return stubStringResponse(204, "", uri);
                    }

                    return stubStringResponse(404, "{\"message\":\"not found\"}", uri);
                });

        GiteaActionsSupport support = new GiteaActionsSupport(
                httpClient,
                logger,
                () -> "http://localhost:3000/api/v1",
                () -> "token");

        assertThat(support.clearActionsRuns("owner", "repo")).isEqualTo(1);
        assertThat(deleteAttempts.get()).isEqualTo(2);
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
}
