package dev.promptlm.testutils.gitea;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;

import java.util.Objects;

final class GiteaRunnerRegistry {

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
        JsonNode response = apiClient.getJson("/actions/runners");
        JsonNode runners = response.path("runners");
        if (!runners.isArray()) {
            logger.warn("Unexpected runners payload: {}", response);
            return false;
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
                return true;
            }
            return onlineNode.asBoolean(false);
        }
        return false;
    }
}
