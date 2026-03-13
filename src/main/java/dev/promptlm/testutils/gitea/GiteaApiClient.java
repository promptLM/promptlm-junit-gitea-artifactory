package dev.promptlm.testutils.gitea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

final class GiteaApiClient {

    private final HttpClient httpClient;
    private final Logger logger;
    private final Supplier<String> apiUrlSupplier;
    private final Supplier<String> tokenSupplier;
    private final ObjectMapper objectMapper;

    GiteaApiClient(HttpClient httpClient,
                   Logger logger,
                   Supplier<String> apiUrlSupplier,
                   Supplier<String> tokenSupplier,
                   ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.apiUrlSupplier = Objects.requireNonNull(apiUrlSupplier, "apiUrlSupplier");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    JsonNode getJson(String path) {
        return sendJson("GET", path, null);
    }

    JsonNode getJsonOptional(String path) {
        try {
            return getJson(path);
        } catch (GiteaApiException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    JsonNode postJson(String path, String body) {
        return sendJson("POST", path, body);
    }

    JsonNode deleteJson(String path) {
        return sendJson("DELETE", path, null);
    }

    HttpResponse<byte[]> getBytesResponse(String path) {
        URI uri = URI.create(apiUrlSupplier.get() + path);
        HttpRequest request = baseRequest(uri)
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            logResponse("GET", uri.toString(), response.statusCode(), response.headers().map().toString());
            if (response.statusCode() / 100 != 2) {
                throw new GiteaApiException(response.statusCode(), "GET", uri.toString(), "<binary response>");
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while calling Gitea API GET " + uri, e);
        } catch (IOException e) {
            throw new GiteaHarnessException("Failed to call Gitea API GET " + uri, e);
        }
    }

    private JsonNode sendJson(String method, String path, String body) {
        URI uri = URI.create(apiUrlSupplier.get() + path);

        HttpRequest.Builder builder = baseRequest(uri);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }

        HttpRequest request;
        if ("GET".equals(method)) {
            request = builder.GET().build();
        } else if ("POST".equals(method)) {
            request = builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build();
        } else if ("DELETE".equals(method)) {
            request = builder.DELETE().build();
        } else {
            request = builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build();
        }

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, method, uri.toString(), response);

            if (response.statusCode() / 100 != 2) {
                throw new GiteaApiException(response.statusCode(), method, uri.toString(), response.body());
            }

            if (response.body() == null || response.body().isBlank()) {
                return objectMapper.nullNode();
            }

            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while calling Gitea API " + method + " " + uri, e);
        } catch (IOException e) {
            throw new GiteaHarnessException("Failed to call Gitea API " + method + " " + uri, e);
        }
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "token " + tokenSupplier.get())
                .header("Accept", "application/json");
    }

    private void logResponse(String method, String url, int status, String headers) {
        logger.info("HTTP {} {} -> {}", method, url, status);
        if (logger.isDebugEnabled()) {
            logger.debug("Headers: {}", headers);
        }
    }
}
