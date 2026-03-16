package dev.promptlm.testutils.gitea;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;

import java.util.Objects;

final class GiteaRunnerRegistry {

    private static final String[] RUNNER_ENDPOINTS = {
            "/admin/actions/runners",
            "/user/actions/runners",
            "/actions/runners"
    };

    private final GiteaApiClient apiClient;
    private final Logger logger;

    GiteaRunnerRegistry(GiteaApiClient apiClient, Logger logger) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    boolean isRunnerRegistered(String runnerName) {
        if (runnerName == null || runnerName.isBlank()) {
            throw new IllegalArgumentException("runnerName must not be blank");
        }

        for (String endpoint : RUNNER_ENDPOINTS) {
            JsonNode response = getRunnersPayload(endpoint);
            if (response == null) {
                continue;
            }

            Boolean registered = findRunnerRegistrationState(response, runnerName);
            if (registered != null) {
                return registered;
            }
        }

        return false;
    }

    private JsonNode getRunnersPayload(String endpoint) {
        try {
            return apiClient.getJson(endpoint);
        } catch (GiteaApiException e) {
            if (e.getStatusCode() == 403 || e.getStatusCode() == 404) {
                logger.debug("Runner endpoint {} unavailable: HTTP {}", endpoint, e.getStatusCode());
                return null;
            }
            throw e;
        }
    }

    private Boolean findRunnerRegistrationState(JsonNode response, String runnerName) {
        JsonNode runners = response.path("runners");
        if (!runners.isArray()) {
            logger.warn("Unexpected runners payload: {}", response);
            return null;
        }

        for (JsonNode runner : runners) {
            if (runner == null || runner.isNull()) {
                continue;
            }
            String name = runner.path("name").asText("");
            if (!runnerName.equals(name)) {
                continue;
            }
            JsonNode onlineNode = runner.get("online");
            if (onlineNode == null || onlineNode.isNull()) {
                return Boolean.TRUE;
            }
            return onlineNode.asBoolean(false) ? Boolean.TRUE : Boolean.FALSE;
        }

        return null;
    }
}
