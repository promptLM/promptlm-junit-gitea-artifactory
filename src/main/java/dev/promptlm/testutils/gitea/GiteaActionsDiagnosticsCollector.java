package dev.promptlm.testutils.gitea;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

final class GiteaActionsDiagnosticsCollector {

    private final GiteaApiClient apiClient;
    private final GiteaActions actions;
    private final Supplier<String> traceIdSupplier;
    private final Supplier<String> runnerLogsSupplier;
    private final Supplier<String> giteaLogsSupplier;

    GiteaActionsDiagnosticsCollector(GiteaApiClient apiClient,
                                     GiteaActions actions,
                                     Supplier<String> traceIdSupplier,
                                     Supplier<String> runnerLogsSupplier,
                                     Supplier<String> giteaLogsSupplier) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
        this.runnerLogsSupplier = Objects.requireNonNull(runnerLogsSupplier, "runnerLogsSupplier");
        this.giteaLogsSupplier = Objects.requireNonNull(giteaLogsSupplier, "giteaLogsSupplier");
    }

    GiteaActionsDiagnostics collect(String repoOwner, String repoName, Long runId) {
        List<String> warnings = new ArrayList<>();
        List<String> giteaWorkflowFiles = listWorkflowFiles(repoOwner, repoName, ".gitea/workflows", warnings);
        List<String> githubWorkflowFiles = listWorkflowFiles(repoOwner, repoName, ".github/workflows", warnings);
        List<GiteaActions.ActionRunSummary> runs = List.of();

        try {
            runs = actions.listWorkflowRuns(repoOwner, repoName);
        } catch (RuntimeException e) {
            warnings.add("Failed to list workflow runs: " + e.getMessage());
        }

        Long selectedRunId = runId;
        if (selectedRunId == null && !runs.isEmpty()) {
            selectedRunId = runs.stream()
                    .max(Comparator.comparing(GiteaActions.ActionRunSummary::createdAt))
                    .map(GiteaActions.ActionRunSummary::id)
                    .orElse(null);
        }

        Map<Long, List<GiteaActions.ActionJobSummary>> jobsByRunId = new HashMap<>();
        Map<Long, byte[]> jobLogsByJobId = new HashMap<>();
        if (selectedRunId != null) {
            try {
                List<GiteaActions.ActionJobSummary> jobs = actions.listWorkflowJobs(repoOwner, repoName, selectedRunId);
                jobsByRunId.put(selectedRunId, jobs);
                for (GiteaActions.ActionJobSummary job : jobs) {
                    if (shouldCaptureJobLogs(job)) {
                        try {
                            byte[] logs = actions.downloadWorkflowJobLogs(repoOwner, repoName, selectedRunId, job.id());
                            jobLogsByJobId.put(job.id(), logs);
                        } catch (RuntimeException e) {
                            warnings.add("Failed to download logs for job " + job.id() + ": " + e.getMessage());
                        }
                    }
                }
            } catch (RuntimeException e) {
                warnings.add("Failed to list workflow jobs for run " + selectedRunId + ": " + e.getMessage());
            }
        } else {
            warnings.add("No workflow run selected for job diagnostics");
        }

        String runnerLogs = tailLogs(runnerLogsSupplier.get(), 300);
        String giteaLogs = tailLogs(giteaLogsSupplier.get(), 300);

        return new GiteaActionsDiagnostics(
                traceIdSupplier.get(),
                repoOwner,
                repoName,
                Instant.now(),
                giteaWorkflowFiles,
                githubWorkflowFiles,
                runs,
                jobsByRunId,
                jobLogsByJobId,
                runnerLogs,
                giteaLogs,
                warnings);
    }

    private boolean shouldCaptureJobLogs(GiteaActions.ActionJobSummary job) {
        String conclusion = job.conclusion() == null ? "" : job.conclusion().toLowerCase(Locale.ROOT);
        String status = job.status() == null ? "" : job.status().toLowerCase(Locale.ROOT);
        return !("success".equals(conclusion) || "success".equals(status));
    }

    private List<String> listWorkflowFiles(String repoOwner, String repoName, String workflowDir, List<String> warnings) {
        JsonNode response = apiClient.getJsonOptional("/repos/" + repoOwner + "/" + repoName + "/contents/" + workflowDir);
        if (response == null || response.isNull()) {
            return List.of();
        }
        if (!response.isArray()) {
            warnings.add("Unexpected workflow contents response for " + workflowDir + ": " + response);
            return List.of();
        }
        List<String> files = new ArrayList<>();
        for (JsonNode entry : response) {
            if (entry == null || entry.isNull()) {
                continue;
            }
            if ("file".equals(entry.path("type").asText())) {
                files.add(entry.path("name").asText());
            }
        }
        return files;
    }

    private String tailLogs(String logs, int maxLines) {
        if (logs == null || logs.isBlank()) {
            return logs;
        }
        String[] lines = logs.split("\\R");
        if (lines.length <= maxLines) {
            return logs;
        }
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            builder.append(lines[i]);
            if (i < lines.length - 1) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }
}
