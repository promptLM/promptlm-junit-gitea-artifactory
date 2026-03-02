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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Encapsulates REST interactions for configuring Gitea repository Actions.
 */
final class GiteaActionsSupport {

    private static final int ACTION_VARIABLE_CREATE_ATTEMPTS = 12;
    private static final int ACTION_VARIABLE_UPDATE_ATTEMPTS = 10;
    private static final Duration ACTION_VARIABLE_RETRY_DELAY = Duration.ofSeconds(1);
    private static final int ACTIONS_ENDPOINT_NOT_AVAILABLE = -2;
    private static final Set<String> TERMINAL_TASK_STATES = Set.of(
            "success", "succeeded", "completed", "failed", "failure", "error", "cancelled", "canceled", "skipped");

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

    WorkflowTaskResult waitForWorkflowTaskResult(String repoOwner,
                                                 String repoName,
                                                 String workflowFile,
                                                 Duration timeout,
                                                 Duration pollInterval) {
        if (workflowFile == null || workflowFile.isBlank()) {
            throw new IllegalArgumentException("workflowFile must not be blank");
        }

        AtomicReference<WorkflowTaskResult> terminalResult = new AtomicReference<>();
        try {
            Awaitility.await("actions workflow task for " + repoOwner + "/" + repoName + "/" + workflowFile)
                    .pollInterval(pollInterval)
                    .atMost(timeout)
                    .ignoreExceptions()
                    .untilAsserted(() -> {
                        List<ActionTaskRef> tasks = listActionsTasks(repoOwner, repoName);
                        if (tasks.isEmpty()) {
                            throw new AssertionError("No Actions tasks available yet");
                        }

                        List<ActionTaskRef> workflowTasks = filterTasksForWorkflow(tasks, workflowFile);
                        if (workflowTasks.isEmpty()) {
                            throw new AssertionError("No Actions task found for workflow '" + workflowFile + "'");
                        }

                        if (workflowTasks.stream().anyMatch(task -> !task.isTerminal())) {
                            throw new AssertionError("Actions tasks for workflow '" + workflowFile + "' are not in a terminal state yet");
                        }

                        ActionTaskRef representative = workflowTasks.stream()
                                .filter(task -> !task.isSuccess())
                                .findFirst()
                                .orElse(workflowTasks.get(0));
                        terminalResult.set(WorkflowTaskResult.from(representative));
                    });

            WorkflowTaskResult result = terminalResult.get();
            if (result == null) {
                throw new IllegalStateException("Workflow result was not captured for '" + workflowFile + "'");
            }
            return result;
        } catch (ConditionTimeoutException e) {
            logRepositoryActionsDiagnostics(repoOwner, repoName);
            throw new IllegalStateException("Timed out waiting for workflow '" + workflowFile + "' to finish for "
                    + repoOwner + "/" + repoName + ". Current tasks: " + summarizeActionsTasks(repoOwner, repoName), e);
        }
    }

    private List<ActionTaskRef> filterTasksForWorkflow(List<ActionTaskRef> tasks, String workflowFile) {
        String normalized = workflowFile.trim().toLowerCase(Locale.ROOT);
        List<ActionTaskRef> matching = new ArrayList<>();
        for (ActionTaskRef task : tasks) {
            String workflow = task.workflow == null ? "" : task.workflow.trim().toLowerCase(Locale.ROOT);
            String job = task.job == null ? "" : task.job.trim().toLowerCase(Locale.ROOT);
            if (workflow.contains(normalized) || job.contains(normalized)) {
                matching.add(task);
            }
        }

        List<ActionTaskRef> candidates = matching.isEmpty() ? tasks : matching;
        if (candidates.isEmpty()) {
            return candidates;
        }

        String latestSha = candidates.get(0).sha;
        if (latestSha == null || latestSha.isBlank()) {
            return candidates;
        }

        List<ActionTaskRef> latestShaTasks = new ArrayList<>();
        for (ActionTaskRef task : candidates) {
            if (latestSha.equals(task.sha)) {
                latestShaTasks.add(task);
            }
        }

        return latestShaTasks.isEmpty() ? candidates : latestShaTasks;
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
        int taskCount = getActionsTaskCount(repoOwner, repoName);
        String taskSummary = summarizeActionsTasks(repoOwner, repoName);
        logger.info("Actions diagnostics for {}/{}: .gitea/workflows files={} names={}, .github/workflows files={} names={}, action runs={}, action tasks={}, task-summary={}",
                repoOwner,
                repoName,
                giteaWorkflowFiles,
                formatWorkflowNames(giteaWorkflowNames),
                githubWorkflowFiles,
                formatWorkflowNames(githubWorkflowNames),
                runCount,
                taskCount,
                taskSummary);
    }

    void waitForActionsRun(String repoOwner, String repoName, Duration timeout, Duration pollInterval) {
        int initialCount = getActionsRunCount(repoOwner, repoName);
        if (initialCount == ACTIONS_ENDPOINT_NOT_AVAILABLE) {
            int taskCount = getActionsTaskCount(repoOwner, repoName);
            logger.info("Actions run API endpoint is not available for {}/{} (HTTP 404). actions/tasks currently reports {} item(s); continuing with UI-based workflow discovery.",
                    repoOwner, repoName, taskCount);
            return;
        }
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
            int clearedTasks = clearActionsTasks(repoOwner, repoName);
            logger.info("No Actions runs to clear for {}/{}. Cleared {} Actions task(s).",
                    repoOwner, repoName, clearedTasks);
            return clearedTasks;
        }

        int removed = 0;
        for (Long runId : runIds) {
            if (runId == null || runId <= 0) {
                continue;
            }
            cancelActionsRun(repoOwner, repoName, runId);
            if (deleteActionsRun(repoOwner, repoName, runId)) {
                removed++;
            }
        }

        int clearedTasks = clearActionsTasks(repoOwner, repoName);
        logger.info("Cleared {} of {} Actions run(s) and {} Actions task(s) for {}/{}",
                removed, runIds.size(), clearedTasks, repoOwner, repoName);
        return removed + clearedTasks;
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
            if (response.statusCode() == 404) {
                return ACTIONS_ENDPOINT_NOT_AVAILABLE;
            }
            if (response.statusCode() != 200) {
                return -1;
            }
            JsonNode json = GiteaContainer.JSON.readTree(response.body());
            JsonNode runsNode = json.path("workflow_runs");
            if (runsNode.isArray()) {
                return runsNode.size();
            }
            JsonNode dataNode = json.path("data");
            if (dataNode.isArray()) {
                return dataNode.size();
            }
            if (dataNode.isObject()) {
                JsonNode nestedRuns = dataNode.path("workflow_runs");
                if (nestedRuns.isArray()) {
                    return nestedRuns.size();
                }
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while reading Actions runs", e);
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to read Actions runs", e);
        }
    }

    private int getActionsTaskCount(String repoOwner, String repoName) {
        URI uri = URI.create(String.format("%s/repos/%s/%s/actions/tasks", apiUrlSupplier.get(), repoOwner, repoName));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, "GET", uri.toString(), response);
            if (response.statusCode() == 404) {
                return ACTIONS_ENDPOINT_NOT_AVAILABLE;
            }
            if (response.statusCode() != 200) {
                return -1;
            }
            JsonNode json = GiteaContainer.JSON.readTree(response.body());
            JsonNode tasksNode = extractTasksArray(json);
            if (tasksNode == null || !tasksNode.isArray()) {
                return 0;
            }
            return tasksNode.size();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while reading Actions tasks", e);
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to read Actions tasks", e);
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

    private int clearActionsTasks(String repoOwner, String repoName) {
        List<ActionTaskRef> tasks = listActionsTasks(repoOwner, repoName);
        if (tasks.isEmpty()) {
            return 0;
        }

        int changed = 0;
        for (ActionTaskRef task : tasks) {
            if (task.id() <= 0) {
                continue;
            }

            boolean terminal = task.isTerminal();
            if (!terminal) {
                terminal = cancelActionsTask(repoOwner, repoName, task.id());
            }
            if (deleteActionsTask(repoOwner, repoName, task.id())) {
                changed++;
                continue;
            }
            if (terminal) {
                changed++;
            }
        }
        return changed;
    }

    private String summarizeActionsTasks(String repoOwner, String repoName) {
        List<ActionTaskRef> tasks = listActionsTasks(repoOwner, repoName);
        if (tasks.isEmpty()) {
            return "-";
        }
        int max = Math.min(6, tasks.size());
        List<String> summary = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            ActionTaskRef task = tasks.get(i);
            summary.add(task.summary());
        }
        if (tasks.size() > max) {
            summary.add("...+" + (tasks.size() - max));
        }
        return String.join(" | ", summary);
    }

    private List<ActionTaskRef> listActionsTasks(String repoOwner, String repoName) {
        URI uri = URI.create(String.format("%s/repos/%s/%s/actions/tasks", apiUrlSupplier.get(), repoOwner, repoName));
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
                logger.warn("Failed to list Actions tasks for {}/{}: HTTP {} - {}",
                        repoOwner, repoName, response.statusCode(), response.body());
                return List.of();
            }

            JsonNode json = GiteaContainer.JSON.readTree(response.body());
            JsonNode tasksNode = extractTasksArray(json);
            if (tasksNode == null || !tasksNode.isArray()) {
                return List.of();
            }

            List<ActionTaskRef> tasks = new ArrayList<>();
            for (JsonNode taskNode : tasksNode) {
                if (taskNode == null || taskNode.isNull()) {
                    continue;
                }
                tasks.add(ActionTaskRef.from(taskNode));
            }
            tasks.sort(Comparator.comparingLong(ActionTaskRef::id).reversed());
            return tasks;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while listing Actions tasks", e);
        } catch (Exception e) {
            throw new GiteaHarnessException("Failed to list Actions tasks", e);
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
        JsonNode data = json.path("data");
        if (data.isArray()) {
            return data;
        }
        if (data.isObject()) {
            JsonNode nestedRuns = data.path("workflow_runs");
            if (nestedRuns.isArray()) {
                return nestedRuns;
            }
        }
        return null;
    }

    private JsonNode extractTasksArray(JsonNode json) {
        if (json == null || json.isNull()) {
            return null;
        }
        if (json.isArray()) {
            return json;
        }
        JsonNode workflowRuns = json.path("workflow_runs");
        if (workflowRuns.isArray()) {
            return workflowRuns;
        }
        JsonNode tasks = json.path("tasks");
        if (tasks.isArray()) {
            return tasks;
        }
        JsonNode data = json.path("data");
        if (data.isArray()) {
            return data;
        }
        if (data.isObject()) {
            JsonNode nestedRuns = data.path("workflow_runs");
            if (nestedRuns.isArray()) {
                return nestedRuns;
            }
            JsonNode nestedTasks = data.path("tasks");
            if (nestedTasks.isArray()) {
                return nestedTasks;
            }
            JsonNode workflowTasks = data.path("workflow_tasks");
            if (workflowTasks.isArray()) {
                return workflowTasks;
            }
        }
        JsonNode workflowTasks = json.path("workflow_tasks");
        if (workflowTasks.isArray()) {
            return workflowTasks;
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

    private boolean deleteActionsRun(String repoOwner, String repoName, long runId) {
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
                return true;
            }
            if (response.statusCode() == 404) {
                return true;
            }
            logger.warn("Failed to delete Actions run {} for {}/{}: HTTP {} - {}",
                    runId, repoOwner, repoName, response.statusCode(), response.body());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while deleting Actions run " + runId, e);
        } catch (Exception e) {
            logger.warn("Failed to delete Actions run {} for {}/{}: {}", runId, repoOwner, repoName, e.getMessage());
            return false;
        }
    }

    private boolean cancelActionsTask(String repoOwner, String repoName, long taskId) {
        List<String> cancelPaths = List.of(
                String.format("%s/repos/%s/%s/actions/tasks/%d/cancel", apiUrlSupplier.get(), repoOwner, repoName, taskId),
                String.format("%s/repos/%s/%s/actions/tasks/%d/stop", apiUrlSupplier.get(), repoOwner, repoName, taskId));
        for (String path : cancelPaths) {
            URI uri = URI.create(path);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "token " + adminTokenSupplier.get())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                HttpDebugLogger.logHttpResponse(logger, "POST", uri.toString(), response);
                if (response.statusCode() == 200 || response.statusCode() == 202 || response.statusCode() == 204) {
                    return true;
                }
                if (response.statusCode() == 404 || response.statusCode() == 405) {
                    continue;
                }
                logger.warn("Failed to cancel Actions task {} for {}/{} via {}: HTTP {} - {}",
                        taskId, repoOwner, repoName, path, response.statusCode(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GiteaHarnessException("Interrupted while cancelling Actions task " + taskId, e);
            } catch (Exception e) {
                logger.warn("Failed to cancel Actions task {} for {}/{} via {}: {}",
                        taskId, repoOwner, repoName, path, e.getMessage());
            }
        }
        return false;
    }

    private boolean deleteActionsTask(String repoOwner, String repoName, long taskId) {
        URI uri = URI.create(String.format("%s/repos/%s/%s/actions/tasks/%d",
                apiUrlSupplier.get(), repoOwner, repoName, taskId));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "token " + adminTokenSupplier.get())
                .DELETE()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            HttpDebugLogger.logHttpResponse(logger, "DELETE", uri.toString(), response);
            if (response.statusCode() == 200 || response.statusCode() == 202 || response.statusCode() == 204) {
                return true;
            }
            if (response.statusCode() == 404 || response.statusCode() == 405) {
                return false;
            }
            logger.warn("Failed to delete Actions task {} for {}/{}: HTTP {} - {}",
                    taskId, repoOwner, repoName, response.statusCode(), response.body());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GiteaHarnessException("Interrupted while deleting Actions task " + taskId, e);
        } catch (Exception e) {
            logger.warn("Failed to delete Actions task {} for {}/{}: {}",
                    taskId, repoOwner, repoName, e.getMessage());
            return false;
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

    private record ActionTaskRef(long id,
                                 String status,
                                 String conclusion,
                                 String workflow,
                                 String job,
                                 String ref,
                                 String sha) {

        private static ActionTaskRef from(JsonNode node) {
            long id = readLong(node, "id", "task_id", "run_id");
            String status = readString(node, "status", "state", "result");
            String conclusion = readString(node, "conclusion", "conclusion_state");
            String workflow = readString(node, "workflow_name", "workflow", "workflow_id", "name");
            String job = readString(node, "job_name", "job", "job_id");
            String ref = readString(node, "ref", "head_branch", "branch");
            String sha = readString(node, "sha", "head_sha", "commit_sha");
            return new ActionTaskRef(id, status, conclusion, workflow, job, ref, sha);
        }

        private static long readLong(JsonNode node, String... fields) {
            for (String field : fields) {
                JsonNode value = node.path(field);
                if (value.isIntegralNumber()) {
                    return value.asLong();
                }
                if (value.isTextual()) {
                    try {
                        return Long.parseLong(value.asText().trim());
                    } catch (NumberFormatException ignored) {
                        // Continue with the next candidate field.
                    }
                }
            }
            return -1L;
        }

        private static String readString(JsonNode node, String... fields) {
            for (String field : fields) {
                JsonNode value = node.path(field);
                if (value.isMissingNode() || value.isNull()) {
                    continue;
                }

                if (value.isObject()) {
                    String nested = readNestedString(value, fields);
                    if (!nested.isBlank()) {
                        return nested;
                    }
                }

                String text = value.asText().trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
            return "";
        }

        private static String readNestedString(JsonNode node, String... fields) {
            for (String field : fields) {
                JsonNode value = node.path(field);
                if (!value.isMissingNode() && !value.isNull()) {
                    String text = value.asText().trim();
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            }
            return "";
        }

        private boolean isTerminal() {
            String state = terminalState();
            return !state.isBlank() && TERMINAL_TASK_STATES.contains(state.toLowerCase(Locale.ROOT));
        }

        private boolean isSuccess() {
            String state = terminalState();
            return "success".equalsIgnoreCase(state)
                    || "succeeded".equalsIgnoreCase(state)
                    || "completed".equalsIgnoreCase(state);
        }

        private String summary() {
            String state = terminalState();
            String workflowToken = workflow.isBlank() ? "workflow=?" : "workflow=" + workflow;
            String jobToken = job.isBlank() ? "job=?" : "job=" + job;
            String refToken = ref.isBlank() ? "" : ", ref=" + ref;
            String shaToken = sha.isBlank() ? "" : ", sha=" + abbreviate(sha);
            return "id=" + id + ", state=" + (state.isBlank() ? "unknown" : state) +
                    ", " + workflowToken + ", " + jobToken + refToken + shaToken;
        }

        private String terminalState() {
            if (!conclusion.isBlank()) {
                return conclusion;
            }
            return status == null ? "" : status;
        }

        private static String abbreviate(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            String normalized = value.trim();
            if (normalized.length() <= 12) {
                return normalized;
            }
            return normalized.substring(0, 12);
        }
    }

    record WorkflowTaskResult(long id,
                              String state,
                              boolean success,
                              String workflow,
                              String job,
                              String ref,
                              String sha) {

        static WorkflowTaskResult from(ActionTaskRef task) {
            String state = task.terminalState();
            boolean success = "success".equalsIgnoreCase(state)
                    || "succeeded".equalsIgnoreCase(state)
                    || "completed".equalsIgnoreCase(state);
            return new WorkflowTaskResult(
                    task.id,
                    state,
                    success,
                    task.workflow,
                    task.job,
                    task.ref,
                    task.sha);
        }

        String summary() {
            String workflowToken = workflow == null || workflow.isBlank() ? "workflow=?" : "workflow=" + workflow;
            String jobToken = job == null || job.isBlank() ? "job=?" : "job=" + job;
            String refToken = ref == null || ref.isBlank() ? "" : ", ref=" + ref;
            String shaToken = sha == null || sha.isBlank() ? "" : ", sha=" + sha;
            return "id=" + id + ", state=" + (state == null || state.isBlank() ? "unknown" : state)
                    + ", " + workflowToken + ", " + jobToken + refToken + shaToken;
        }
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
