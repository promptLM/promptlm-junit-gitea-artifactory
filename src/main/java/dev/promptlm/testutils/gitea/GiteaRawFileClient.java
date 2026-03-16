package dev.promptlm.testutils.gitea;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

final class GiteaRawFileClient {

    private static final int ERROR_SNIPPET_LIMIT = 256;

    private final HttpClient httpClient;
    private final Logger logger;
    private final Supplier<String> webUrlSupplier;
    private final Supplier<String> tokenSupplier;

    GiteaRawFileClient(HttpClient httpClient,
                       Logger logger,
                       Supplier<String> webUrlSupplier,
                       Supplier<String> tokenSupplier) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.webUrlSupplier = Objects.requireNonNull(webUrlSupplier, "webUrlSupplier");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
    }

    Optional<byte[]> fetchRawFileBytes(String owner,
                                       String repo,
                                       String branch,
                                       String relativePath) {
        validateRepoInputs(owner, repo, branch, relativePath);
        URI uri = URI.create(buildRawFileUrl(owner.trim(), repo.trim(), branch.trim(), relativePath));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "token " + tokenSupplier.get())
                .header("Accept", "*/*")
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            logger.debug("GET {} -> {}", uri, response.statusCode());
            if (response.statusCode() == 200) {
                return Optional.ofNullable(response.body()).map(body -> body.clone());
            }
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            throw error(uri.toString(), response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while fetching raw file from " + uri, e);
        } catch (IOException e) {
            throw new GiteaHarnessException("Failed to fetch raw file from " + uri, e);
        }
    }

    Optional<String> fetchRawFile(String owner,
                                  String repo,
                                  String branch,
                                  String relativePath) {
        return fetchRawFileBytes(owner, repo, branch, relativePath)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    String waitForRawFile(String owner,
                          String repo,
                          String branch,
                          String relativePath,
                          Duration timeout,
                          Duration pollInterval) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(pollInterval, "pollInterval");
        try {
            return Awaitility.await("raw file " + relativePath + " in " + repo)
                    .pollInterval(pollInterval)
                    .atMost(timeout)
                    .until(() -> fetchRawFile(owner, repo, branch, relativePath).orElse(null), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            throw new GiteaHarnessException("Timed out waiting for raw file '" + relativePath
                    + "' in repo " + owner + "/" + repo, e);
        }
    }

    private String buildRawFileUrl(String owner,
                                   String repo,
                                   String branch,
                                   String relativePath) {
        String base = trimTrailingSlash(webUrlSupplier.get());
        String encodedRelative = encodeRelativePath(relativePath);
        return base + "/" + encodeSegment(owner)
                + "/" + encodeSegment(repo)
                + "/raw/branch/" + encodeSegment(branch)
                + "/" + encodedRelative;
    }

    private static void validateRepoInputs(String owner,
                                           String repo,
                                           String branch,
                                           String relativePath) {
        if (isBlank(owner)) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (isBlank(repo)) {
            throw new IllegalArgumentException("repo must not be blank");
        }
        if (isBlank(branch)) {
            throw new IllegalArgumentException("branch must not be blank");
        }
        if (isBlank(relativePath)) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String encodeRelativePath(String relativePath) {
        String normalized = relativePath.trim().replaceAll("^/+", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        return Arrays.stream(normalized.split("/"))
                .map(GiteaRawFileClient::encodeSegment)
                .collect(Collectors.joining("/"));
    }

    private static String encodeSegment(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8)
                    .replace("+", "%20");
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to encode path segment", e);
        }
    }

    private static String trimTrailingSlash(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalStateException("Gitea web URL is not configured");
        }
        if (input.endsWith("/")) {
            return input.substring(0, input.length() - 1);
        }
        return input;
    }

    private static GiteaHarnessException error(String url, int status, byte[] body) {
        String snippet = body == null ? "<empty>"
                : new String(body, 0, Math.min(body.length, ERROR_SNIPPET_LIMIT), StandardCharsets.UTF_8);
        return new GiteaHarnessException("Failed to fetch raw file from " + url
                + ": status=" + status + " body=" + snippet);
    }
}
