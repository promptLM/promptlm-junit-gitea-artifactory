package dev.promptlm.testutils.gitea;

import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Encapsulates REST interactions for configuring Gitea repository Actions.
 */
final class GiteaActionsSupport {

    private static final int ACTION_VARIABLE_CREATE_ATTEMPTS = 12;
    private static final int ACTION_VARIABLE_UPDATE_ATTEMPTS = 10;
    private static final Duration ACTION_VARIABLE_RETRY_DELAY = Duration.ofSeconds(1);
    private static final int ACTION_RUN_DELETE_ATTEMPTS = 12;
    private static final Duration ACTION_RUN_DELETE_RETRY_DELAY = Duration.ofMillis(500);

    private final HttpClient httpClient;
    private final Logger logger;
    private final Supplier<String> apiUrlSupplier;
    private final Supplier<String> adminTokenSupplier;

    GiteaActionsSupport(HttpClient httpClient,
                        Logger logger,
                        Supplier<String> apiUrlSupplier,
                        Supplier<String> adminTokenSupplier) {
        this.httpClient = httpClient;
        this.logger = logger;
        this.apiUrlSupplier = apiUrlSupplier;
        this.adminTokenSupplier = adminTokenSupplier;
    }

    void ensureRepositoryActionsVariable(String repoOwner, String repoName, String variableName, String value) {
        String variablesBase = actionsVariablesBase(repoOwner, repoName);

        if (value == null || value.isBlank()) {
            ensureRepositoryActionsVariableAbsent(variablesBase, variableName);
            return;
        }

        Duration timeout = ACTION_VARIABLE_RETRY_DELAY.multipliedBy(ACTION_VARIABLE_CREATE_ATTEMPTS);
        try {
            Awaitility.await("actions variable " + variableName + " to be created")
                    .pollInterval(ACTION_VARIABLE_RETRY_DELAY)
                    .atMost(timeout)
                    .ignoreExceptions()
                    .until(() -> ensureRepositoryActionsVariableAttempt(variablesBase, variableName, value));
        } catch (ConditionTimeoutException e) {
            throw new IllegalStateException("Timed out configuring repository variable '" + variableName + "'", e);
        }
    }


    private void ensureRepositoryActionsVariableAbsent(String variablesBase, String variableName) {
        Duration timeout = ACTION_VARIABLE_RETRY_DELAY.multipliedBy(ACTION_VARIABLE_CREATE_ATTEMPTS);
        try {
            Awaitility.await("actions variable " + variableName + " to be absent")
                    .pollInterval(ACTION_VARIABLE_RETRY_DELAY)
                    .atMost(timeout)
                    .ignoreExceptions()
                    .until(() -> ensureRepositoryActionsVariableAbsentAttempt(variablesBase, variableName));
        } catch (ConditionTimeoutException e) {
            throw new IllegalStateException("Timed out removing repository variable '" + variableName + "'", e);
        }
    }

    private boolean ensureRepositoryActionsVariableAbsentAttempt(String variablesBase, String variableName) {
        int status = getActionsVariableStatus(variablesBase, variableName);
        if (status == 404 || status == 405 || status == 503) {
            return true;
        }
        if (status == 200) {
            return deleteActionsVariable(variablesBase, variableName);
        }
        throw new IllegalStateException("Unexpected status when probing repository variable '" + variableName + "': HTTP " + status);
    }

    private boolean deleteActionsVariable(String variablesBase, String variableName) {
        URI uri = URI.create(variablesBase + "/" + variableName);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .DELETE()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, "DELETE", uri.toString(), response);
            int status = response.statusCode();
            if (status == 200 || status == 202 || status == 204 || status == 404) {
                return true;
            }
            if (status == 503) {
                return false;
            }
            throw new IllegalStateException("Failed to delete Actions variable '" + variableName + "': HTTP " + status + " - " + response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while deleting Actions variable '" + variableName + "'", e);
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to delete Actions variable '" + variableName + "'", e);
        }
    }

    String readRepositoryActionsVariable(String repoOwner, String repoName, String variableName) {
        String variablesBase = actionsVariablesBase(repoOwner, repoName);
        URI uri = URI.create(variablesBase + "/" + variableName);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, "GET", uri.toString(), response);
            HttpDebugLogger.logCurlCommand(logger, "GET", uri.toString(), null, adminTokenSupplier.get());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("Failed to read Actions variable '" + variableName + "': HTTP " + response.statusCode());
            }

            var json = GiteaContainer.JSON.readTree(response.body());
            var dataNode = json.get("data");
            if (dataNode != null && !dataNode.isNull()) {
                return dataNode.asText();
            }
            var valueNode = json.get("value");
            if (valueNode != null && !valueNode.isNull()) {
                return valueNode.asText();
            }
            throw new IllegalStateException("Expected 'data' or 'value' field when reading Actions variable '" + variableName + "'");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while reading Actions variable '" + variableName + "'", e);
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to read Actions variable '" + variableName + "'", e);
        }
    }

    void enableRepositoryActions(String repoOwner, String repoName) {
        if (repoOwner == null || repoOwner.isBlank()) {
            throw new IllegalArgumentException("repoOwner must not be blank");
        }
        if (repoName == null || repoName.isBlank()) {
            throw new IllegalArgumentException("repoName must not be blank");
        }

        URI uri = URI.create(String.format("%s/repos/%s/%s", apiUrlSupplier.get(), repoOwner, repoName));
        String payload = "{\"actions_enabled\":true,\"default_branch\":\"main\"}";
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("Failed to enable Actions for {}/{}: HTTP {} - {}", repoOwner, repoName, response.statusCode(), response.body());
            } else {
                logger.info("Actions enabled for repository {}/{}", repoOwner, repoName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while enabling Actions for repository", e);
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to enable Actions for repository", e);
        }
    }

    void logRepositoryActionsDiagnostics(String repoOwner, String repoName) {
        List<String> giteaWorkflowNames = getWorkflowFileNames(repoOwner, repoName, ".gitea/workflows");
        List<String> githubWorkflowNames = getWorkflowFileNames(repoOwner, repoName, ".github/workflows");
        int giteaWorkflowFiles = getWorkflowFileCount(repoOwner, repoName, ".gitea/workflows");
        int githubWorkflowFiles = getWorkflowFileCount(repoOwner, repoName, ".github/workflows");
        int runCount = getActionsRunCount(repoOwner, repoName);
        logger.info("Actions diagnostics for {}/{}: .gitea/workflows files={} names={}, .github/workflows files={} names={}, action runs={}",
                repoOwner,
                repoName,
                giteaWorkflowFiles,
                formatWorkflowNames(giteaWorkflowNames),
                githubWorkflowFiles,
                formatWorkflowNames(githubWorkflowNames),
                runCount);
    }

    void waitForActionsRun(String repoOwner, String repoName, Duration timeout, Duration pollInterval) {
        try {
            Awaitility.await("actions run for " + repoOwner + "/" + repoName)
                    .pollInterval(pollInterval)
                    .atMost(timeout)
                    .ignoreExceptions()
                    .until(() -> {
                        int runCount = getActionsRunCount(repoOwner, repoName);
                        logger.info("Actions run probe for {}/{}: runCount={}", repoOwner, repoName, runCount);
                        return runCount > 0;
                    });
        } catch (ConditionTimeoutException e) {
            logRepositoryActionsDiagnostics(repoOwner, repoName);
            throw new IllegalStateException("Timed out waiting for Actions run for " + repoOwner + "/" + repoName, e);
        }
    }

    int clearActionsRuns(String repoOwner, String repoName) {
        List<Long> runIds = getActionsRunIds(repoOwner, repoName);
        if (runIds.isEmpty()) {
            logger.info("No Actions runs to clear for {}/{}.", repoOwner, repoName);
            return 0;
        }

        int removed = 0;
        for (Long runId : runIds) {
            if (runId == null || runId <= 0) {
                continue;
            }
            cancelActionsRun(repoOwner, repoName, runId);
            if (deleteActionsRunWithRetry(repoOwner, repoName, runId)) {
                removed++;
            }
        }

        logger.info("Cleared {} of {} Actions run(s) for {}/{}",
                removed, runIds.size(), repoOwner, repoName);
        return removed;
    }

    private String actionsVariablesBase(String repoOwner, String repoName) {
        return String.format("%s/repos/%s/%s/actions/variables", apiUrlSupplier.get(), repoOwner, repoName);
    }

    private int getActionsRunCount(String repoOwner, String repoName) {
        URI uri = URI.create(String.format("%s/repos/%s/%s/actions/runs", apiUrlSupplier.get(), repoOwner, repoName));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, "GET", uri.toString(), response);
            if (response.statusCode() != 200) {
                throw new GiteaHarnessException("Failed to list Actions runs: HTTP " + response.statusCode() + " - " + response.body());
            }
            JsonNode json = GiteaContainer.JSON.readTree(response.body());
            JsonNode runsNode = json.path("workflow_runs");
            if (runsNode.isArray()) {
                return runsNode.size();
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while reading Actions runs", e);
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to read Actions runs", e);
        }
    }


    private List<Long> getActionsRunIds(String repoOwner, String repoName) {
        URI uri = URI.create(String.format("%s/repos/%s/%s/actions/runs", apiUrlSupplier.get(), repoOwner, repoName));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, "GET", uri.toString(), response);
            if (response.statusCode() == 404) {
                return List.of();
            }
            if (response.statusCode() != 200) {
                logger.warn("Failed to list Actions runs for {}/{}: HTTP {} - {}",
                        repoOwner, repoName, response.statusCode(), response.body());
                return List.of();
            }

            JsonNode json = GiteaContainer.JSON.readTree(response.body());
            JsonNode runsNode = extractRunsArray(json);
            if (runsNode == null || !runsNode.isArray()) {
                return List.of();
            }

            Set<Long> ids = new LinkedHashSet<>();
            for (JsonNode runNode : runsNode) {
                if (runNode == null || runNode.isNull()) {
                    continue;
                }
                long id = runNode.path("id").asLong(-1);
                if (id <= 0) {
                    id = runNode.path("run_id").asLong(-1);
                }
                if (id > 0) {
                    ids.add(id);
                }
            }
            return new ArrayList<>(ids);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while listing Actions runs", e);
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to list Actions runs", e);
        }
    }

    private JsonNode extractRunsArray(JsonNode json) {
        if (json == null || json.isNull()) {
            return null;
        }
        JsonNode workflowRuns = json.path("workflow_runs");
        if (workflowRuns.isArray()) {
            return workflowRuns;
        }
        return null;
    }


    private void cancelActionsRun(String repoOwner, String repoName, long runId) {
        URI uri = URI.create(String.format("%s/repos/%s/%s/actions/runs/%d/cancel",
                apiUrlSupplier.get(), repoOwner, repoName, runId));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, "POST", uri.toString(), response);
            if (response.statusCode() != 200
                    && response.statusCode() != 202
                    && response.statusCode() != 204
                    && response.statusCode() != 404) {
                logger.warn("Failed to cancel Actions run {} for {}/{}: HTTP {} - {}",
                        runId, repoOwner, repoName, response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while cancelling Actions run " + runId, e);
        } catch (Exception e) {
            logger.warn("Failed to cancel Actions run {} for {}/{}: {}", runId, repoOwner, repoName, e.getMessage());
        }
    }

    private boolean deleteActionsRunWithRetry(String repoOwner, String repoName, long runId) {
        for (int attempt = 1; attempt <= ACTION_RUN_DELETE_ATTEMPTS; attempt++) {
            DeleteActionsRunOutcome outcome = deleteActionsRun(repoOwner, repoName, runId);
            if (outcome.deleted()) {
                return true;
            }
            if (!outcome.retryable()) {
                return false;
            }
            if (attempt < ACTION_RUN_DELETE_ATTEMPTS) {
                sleepBeforeDeleteRetry(runId, repoOwner, repoName, attempt);
            }
        }

        logger.warn("Actions run {} for {}/{} remained active after cancellation and could not be deleted",
                runId, repoOwner, repoName);
        return false;
    }

    private void sleepBeforeDeleteRetry(long runId, String repoOwner, String repoName, int attempt) {
        try {
            logger.info("Actions run {} for {}/{} is still active after cancel; retrying delete attempt {}/{}",
                    runId, repoOwner, repoName, attempt + 1, ACTION_RUN_DELETE_ATTEMPTS);
            Thread.sleep(ACTION_RUN_DELETE_RETRY_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while waiting to delete Actions run " + runId, e);
        }
    }

    private DeleteActionsRunOutcome deleteActionsRun(String repoOwner, String repoName, long runId) {
        URI uri = URI.create(String.format("%s/repos/%s/%s/actions/runs/%d",
                apiUrlSupplier.get(), repoOwner, repoName, runId));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .DELETE()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, "DELETE", uri.toString(), response);
            if (response.statusCode() == 200 || response.statusCode() == 202 || response.statusCode() == 204) {
                return DeleteActionsRunOutcome.deletedOutcome();
            }
            if (response.statusCode() == 404) {
                return DeleteActionsRunOutcome.deletedOutcome();
            }
            if (response.statusCode() == 400 && isRunNotDoneResponse(response.body())) {
                return DeleteActionsRunOutcome.retryableOutcome();
            }
            logger.warn("Failed to delete Actions run {} for {}/{}: HTTP {} - {}",
                    runId, repoOwner, repoName, response.statusCode(), response.body());
            return DeleteActionsRunOutcome.notDeletedOutcome();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while deleting Actions run " + runId, e);
        } catch (Exception e) {
            logger.warn("Failed to delete Actions run {} for {}/{}: {}", runId, repoOwner, repoName, e.getMessage());
            return DeleteActionsRunOutcome.notDeletedOutcome();
        }
    }

    private boolean isRunNotDoneResponse(String body) {
        return body != null && body.toLowerCase(java.util.Locale.ROOT).contains("workflow run is not done");
    }

    private record DeleteActionsRunOutcome(boolean deleted, boolean retryable) {
        private static DeleteActionsRunOutcome deletedOutcome() {
            return new DeleteActionsRunOutcome(true, false);
        }

        private static DeleteActionsRunOutcome retryableOutcome() {
            return new DeleteActionsRunOutcome(false, true);
        }

        private static DeleteActionsRunOutcome notDeletedOutcome() {
            return new DeleteActionsRunOutcome(false, false);
        }
    }

    private int getWorkflowFileCount(String repoOwner, String repoName, String workflowDir) {
        URI uri = URI.create(String.format("%s/repos/%s/%s/contents/%s", apiUrlSupplier.get(), repoOwner, repoName, workflowDir));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, "GET", uri.toString(), response);
            if (response.statusCode() == 404) {
                return 0;
            }
            if (response.statusCode() != 200) {
                return -1;
            }
            JsonNode json = GiteaContainer.JSON.readTree(response.body());
            if (!json.isArray()) {
                return 0;
            }
            int count = 0;
            for (JsonNode entry : json) {
                if ("file".equals(entry.path("type").asText())) {
                    count++;
                }
            }
            return count;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while reading workflow directory " + workflowDir, e);
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to read workflow directory " + workflowDir, e);
        }
    }

    private List<String> getWorkflowFileNames(String repoOwner, String repoName, String workflowDir) {
        URI uri = URI.create(String.format("%s/repos/%s/%s/contents/%s", apiUrlSupplier.get(), repoOwner, repoName, workflowDir));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, "GET", uri.toString(), response);
            if (response.statusCode() != 200) {
                return List.of();
            }
            JsonNode json = GiteaContainer.JSON.readTree(response.body());
            if (!json.isArray()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (JsonNode entry : json) {
                if ("file".equals(entry.path("type").asText())) {
                    String name = entry.path("name").asText();
                    if (!name.isBlank()) {
                        names.add(name);
                    }
                }
            }
            return names;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while reading workflow names for " + workflowDir, e);
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to read workflow names for " + workflowDir, e);
        }
    }

    private String formatWorkflowNames(List<String> workflowNames) {
        if (workflowNames == null || workflowNames.isEmpty()) {
            return "-";
        }
        return String.join(",", workflowNames);
    }

    private boolean createActionsVariable(String variablesBase, String variableName, String value) {
        URI uri = URI.create(variablesBase + "/" + variableName);
        String payload = buildActionsVariablePayload(value);

        HttpResponse<String> response = sendActionsVariableRequest("POST", uri, payload);
        int status = response.statusCode();
        if (status == 200 || status == 201 || status == 204) {
            return true;
        }
        if (status == 409) {
            return updateActionsVariable(variablesBase, variableName, value);
        }
        if (status == 405 || status == 503) {
            return false;
        }
        throw new IllegalStateException("Failed to create Actions variable '" + variableName + "': HTTP " + status + " - " + response.body());
    }

    private boolean updateActionsVariable(String variablesBase, String variableName, String value) {
        Duration timeout = ACTION_VARIABLE_RETRY_DELAY.multipliedBy(ACTION_VARIABLE_UPDATE_ATTEMPTS);
        try {
            Awaitility.await("actions variable " + variableName + " to update")
                    .pollInterval(ACTION_VARIABLE_RETRY_DELAY)
                    .atMost(timeout)
                    .ignoreExceptions()
                    .until(() -> attemptUpdateActionsVariable(variablesBase, variableName, value));
            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }

    private boolean ensureRepositoryActionsVariableAttempt(String variablesBase, String variableName, String value) {
        int status = getActionsVariableStatus(variablesBase, variableName);
        if (status == 200) {
            return updateActionsVariable(variablesBase, variableName, value);
        }
        if (status == 404 || status == 405 || status == 503) {
            return createActionsVariable(variablesBase, variableName, value);
        }
        throw new IllegalStateException("Unexpected status when probing repository variable '" + variableName + "': HTTP " + status);
    }

    private boolean attemptUpdateActionsVariable(String variablesBase, String variableName, String value) {
        URI uri = URI.create(variablesBase + "/" + variableName);
        String payload = buildActionsVariablePayload(value);

        HttpResponse<String> response = sendActionsVariableRequest("PUT", uri, payload);
        int status = response.statusCode();
        if (status == 200 || status == 204) {
            return true;
        }
        if (status == 404 || status == 503) {
            return false;
        }
        throw new IllegalStateException("Failed to update Actions variable '" + variableName + "': HTTP " + status + " - " + response.body());
    }

    private int getActionsVariableStatus(String variablesBase, String variableName) {
        URI uri = URI.create(variablesBase + "/" + variableName);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, "GET", uri.toString(), response);
            HttpDebugLogger.logCurlCommand(logger, "GET", uri.toString(), null, adminTokenSupplier.get());
            return response.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while probing Actions variable '" + variableName + "'", e);
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to probe Actions variable '" + variableName + "'", e);
        }
    }

    private HttpResponse<String> sendActionsVariableRequest(String method, URI uri, String payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .header("Content-Type", "application/json");

        if ("POST".equals(method)) {
            builder = builder.POST(HttpRequest.BodyPublishers.ofString(payload));
        } else if ("PUT".equals(method)) {
            builder = builder.PUT(HttpRequest.BodyPublishers.ofString(payload));
        } else {
            throw new IllegalArgumentException("Unsupported method: " + method);
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, method, uri.toString(), response);
            HttpDebugLogger.logCurlCommand(logger, method, uri.toString(), payload, adminTokenSupplier.get());
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while sending Actions variable request", e);
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to send Actions variable request", e);
        }
    }

    private String buildActionsVariablePayload(String value) {
        return GiteaContainer.JSON.createObjectNode()
                .put("value", value)
                .put("data", value)
                .toString();
    }
}
