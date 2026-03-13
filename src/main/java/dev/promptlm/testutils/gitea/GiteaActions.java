package dev.promptlm.testutils.gitea;

import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class GiteaActions {

    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of(
            "completed",
            "success",
            "failure",
            "skipped",
            "cancelled",
            "canceled");

    private static final Set<String> TERMINAL_JOB_STATUSES = Set.of(
            "success",
            "failure",
            "skipped",
            "cancelled",
            "canceled");

    private final GiteaApiClient apiClient;
    private final Logger logger;

    GiteaActions(GiteaApiClient apiClient, Logger logger) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public List<ActionRunSummary> listWorkflowRuns(String repoOwner, String repoName) {
        validateRepo(repoOwner, repoName);
        JsonNode response = apiClient.getJson("/repos/" + repoOwner + "/" + repoName + "/actions/runs");
        JsonNode runsNode = response.path("workflow_runs");
        if (!runsNode.isArray()) {
            throw new GiteaHarnessException("Expected workflow_runs array when listing Actions runs for "
                    + repoOwner + "/" + repoName + ". Response: " + response);
        }
        List<ActionRunSummary> runs = new ArrayList<>();
        for (JsonNode runNode : runsNode) {
            runs.add(toRunSummary(runNode));
        }
        return runs;
    }

    public List<ActionJobSummary> listWorkflowJobs(String repoOwner, String repoName, long runId) {
        validateRepo(repoOwner, repoName);
        if (runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        JsonNode response = apiClient.getJson("/repos/" + repoOwner + "/" + repoName
                + "/actions/runs/" + runId + "/jobs");
        JsonNode jobsNode = response.path("workflow_jobs");
        if (!jobsNode.isArray()) {
            throw new GiteaHarnessException("Expected workflow_jobs array when listing Actions jobs for "
                    + repoOwner + "/" + repoName + " run " + runId + ". Response: " + response);
        }
        List<ActionJobSummary> jobs = new ArrayList<>();
        for (JsonNode jobNode : jobsNode) {
            jobs.add(toJobSummary(jobNode));
        }
        return jobs;
    }

    public byte[] downloadWorkflowJobLogs(String repoOwner, String repoName, long runId, long jobId) {
        validateRepo(repoOwner, repoName);
        if (runId <= 0 || jobId <= 0) {
            throw new IllegalArgumentException("runId and jobId must be positive");
        }
        return apiClient.getBytesResponse("/repos/" + repoOwner + "/" + repoName
                + "/actions/runs/" + runId + "/jobs/" + jobId + "/logs").body();
    }

    public ActionExecutionReport waitForWorkflowRunBySha(String repoOwner,
                                                         String repoName,
                                                         String commitSha,
                                                         Duration timeout,
                                                         Duration pollInterval) {
        validateRepo(repoOwner, repoName);
        if (commitSha == null || commitSha.isBlank()) {
            throw new IllegalArgumentException("commitSha must not be blank");
        }
        String normalizedSha = commitSha.trim().toLowerCase(Locale.ROOT);
        try {
            return Awaitility.await("workflow run for " + repoOwner + "/" + repoName + " sha=" + normalizedSha)
                    .pollInterval(pollInterval)
                    .atMost(timeout)
                    .ignoreExceptions()
                    .until(() -> findTerminalRun(repoOwner, repoName, normalizedSha), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            throw new GiteaWorkflowException("Timed out waiting for workflow run for " + repoOwner + "/" + repoName
                    + " sha=" + normalizedSha, e);
        }
    }

    private ActionExecutionReport findTerminalRun(String repoOwner, String repoName, String sha) {
        List<ActionRunSummary> runs = listWorkflowRuns(repoOwner, repoName);
        if (runs.isEmpty()) {
            throw new IllegalStateException("No workflow runs found for " + repoOwner + "/" + repoName);
        }

        List<ActionRunSummary> matches = runs.stream()
                .filter(run -> matchesSha(run.headSha(), sha))
                .sorted(Comparator.comparing(ActionRunSummary::createdAt).reversed())
                .collect(Collectors.toList());

        if (matches.isEmpty()) {
            throw new IllegalStateException("No workflow run found for sha " + sha + " in " + repoOwner + "/" + repoName);
        }

        ActionRunSummary selected = matches.get(0);
        if (!isTerminalRun(selected)) {
            throw new IllegalStateException("Workflow run " + selected.id() + " not in terminal state yet");
        }

        List<ActionJobSummary> jobs = listWorkflowJobs(repoOwner, repoName, selected.id());
        return new ActionExecutionReport(selected, jobs, summarizeJobStates(jobs));
    }

    private boolean isTerminalRun(ActionRunSummary run) {
        if (run.status() != null && TERMINAL_RUN_STATUSES.contains(run.status().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return run.conclusion() != null && TERMINAL_RUN_STATUSES.contains(run.conclusion().toLowerCase(Locale.ROOT));
    }

    private Map<String, Long> summarizeJobStates(List<ActionJobSummary> jobs) {
        return jobs.stream()
                .collect(Collectors.groupingBy(job -> normalize(job.status()), Collectors.counting()));
    }

    private String normalize(String value) {
        return value == null ? "unknown" : value.toLowerCase(Locale.ROOT);
    }

    private boolean matchesSha(String runSha, String expectedSha) {
        if (runSha == null || runSha.isBlank()) {
            return false;
        }
        String normalizedRunSha = runSha.trim().toLowerCase(Locale.ROOT);
        return normalizedRunSha.equals(expectedSha) || normalizedRunSha.startsWith(expectedSha);
    }

    private ActionRunSummary toRunSummary(JsonNode runNode) {
        long id = runNode.path("id").asLong(-1);
        return new ActionRunSummary(
                id,
                text(runNode, "name"),
                text(runNode, "status"),
                text(runNode, "conclusion"),
                text(runNode, "head_branch"),
                text(runNode, "head_sha"),
                text(runNode, "event"),
                text(runNode, "html_url"),
                parseInstant(text(runNode, "created_at")),
                parseInstant(text(runNode, "updated_at")));
    }

    private ActionJobSummary toJobSummary(JsonNode jobNode) {
        long id = jobNode.path("id").asLong(-1);
        return new ActionJobSummary(
                id,
                text(jobNode, "name"),
                text(jobNode, "status"),
                text(jobNode, "conclusion"),
                text(jobNode, "started_at"),
                text(jobNode, "completed_at"),
                text(jobNode, "runner_name"));
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            logger.debug("Failed to parse instant '{}'", value, e);
            return Instant.EPOCH;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private void validateRepo(String repoOwner, String repoName) {
        if (repoOwner == null || repoOwner.isBlank()) {
            throw new IllegalArgumentException("repoOwner must not be blank");
        }
        if (repoName == null || repoName.isBlank()) {
            throw new IllegalArgumentException("repoName must not be blank");
        }
    }

    public record ActionRunSummary(long id,
                                   String name,
                                   String status,
                                   String conclusion,
                                   String headBranch,
                                   String headSha,
                                   String event,
                                   String htmlUrl,
                                   Instant createdAt,
                                   Instant updatedAt) {
    }

    public record ActionJobSummary(long id,
                                   String name,
                                   String status,
                                   String conclusion,
                                   String startedAt,
                                   String completedAt,
                                   String runnerName) {
    }

    public record ActionExecutionReport(ActionRunSummary run,
                                        List<ActionJobSummary> jobs,
                                        Map<String, Long> jobStateSummary) {
        public boolean allJobsTerminal() {
            return jobs.stream().allMatch(job -> job.status() != null
                    && TERMINAL_JOB_STATUSES.contains(job.status().toLowerCase(Locale.ROOT)));
        }
    }
}
