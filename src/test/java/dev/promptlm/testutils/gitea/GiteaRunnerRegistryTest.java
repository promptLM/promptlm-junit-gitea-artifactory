package dev.promptlm.testutils.gitea;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GiteaRunnerRegistryTest {

    @Test
    void findsOnlineRunnerByName() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        Logger logger = mock(Logger.class);
        List<String> requestedPaths = new ArrayList<>();

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    URI uri = request.uri();
                    String path = uri == null ? "" : uri.getPath();
                    requestedPaths.add(path);
                    return stubStringResponse(200, responseBodyForPath(path), uri);
                });

        GiteaApiClient apiClient = new GiteaApiClient(
                httpClient,
                logger,
                () -> "http://localhost:3000/api/v1",
                () -> "token",
                new ObjectMapper());

        GiteaRunnerRegistry registry = new GiteaRunnerRegistry(apiClient, logger);

        assertThat(registry.isRunnerRegistered("gitea-runner")).isTrue();
        assertThat(registry.isRunnerRegistered("missing")).isFalse();
        assertThat(requestedPaths).isNotEmpty();
        assertThat(requestedPaths.get(0)).endsWith("/admin/actions/runners");
    }

    @Test
    void fallsBackToUserRunnerEndpointWhenAdminEndpointUnavailable() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        Logger logger = mock(Logger.class);
        List<String> requestedPaths = new ArrayList<>();

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    URI uri = request.uri();
                    String path = uri == null ? "" : uri.getPath();
                    requestedPaths.add(path);
                    if (path.endsWith("/admin/actions/runners")) {
                        return stubStringResponse(404, "{\"message\":\"not found\"}", uri);
                    }
                    return stubStringResponse(200, responseBodyForPath(path), uri);
                });

        GiteaApiClient apiClient = new GiteaApiClient(
                httpClient,
                logger,
                () -> "http://localhost:3000/api/v1",
                () -> "token",
                new ObjectMapper());

        GiteaRunnerRegistry registry = new GiteaRunnerRegistry(apiClient, logger);

        assertThat(registry.isRunnerRegistered("gitea-runner")).isTrue();
        assertThat(requestedPaths).containsExactly(
                "/api/v1/admin/actions/runners",
                "/api/v1/user/actions/runners");
    }

    private String responseBodyForPath(String path) {
        if (path.contains("/actions/runners")) {
            return "{" +
                    "\"runners\":[{" +
                    "\"id\":1," +
                    "\"name\":\"gitea-runner\"," +
                    "\"online\":true" +
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
}
