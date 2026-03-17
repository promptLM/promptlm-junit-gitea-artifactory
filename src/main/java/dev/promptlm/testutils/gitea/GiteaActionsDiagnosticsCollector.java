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
    private final Supplier<List<GiteaActionsTaskContainerLog>> taskContainerLogsSupplier;
    private final Supplier<List<GiteaActionsLogFile>> giteaActionsLogFilesSupplier;

    GiteaActionsDiagnosticsCollector(GiteaApiClient apiClient,
                                     GiteaActions actions,
                                     Supplier<String> traceIdSupplier,
                                     Supplier<String> runnerLogsSupplier,
                                     Supplier<String> giteaLogsSupplier,
                                     Supplier<List<GiteaActionsTaskContainerLog>> taskContainerLogsSupplier,
                                     Supplier<List<GiteaActionsLogFile>> giteaActionsLogFilesSupplier) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
        this.runnerLogsSupplier = Objects.requireNonNull(runnerLogsSupplier, "runnerLogsSupplier");
        this.giteaLogsSupplier = Objects.requireNonNull(giteaLogsSupplier, "giteaLogsSupplier");
        this.taskContainerLogsSupplier = Objects.requireNonNull(taskContainerLogsSupplier, "taskContainerLogsSupplier");
        this.giteaActionsLogFilesSupplier = Objects.requireNonNull(giteaActionsLogFilesSupplier,
                "giteaActionsLogFilesSupplier");
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
        Map<Long, List<GiteaActionsTaskContainerLog>> taskContainerLogsByJobId = new HashMap<>();
        List<GiteaActionsLogFile> giteaActionsLogFiles = List.of();
        boolean giteaActionsLogsCaptured = false;
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
                            if (isNotFound(e)) {
                                warnings.add("Job log endpoint returned 404 for job " + job.id()
                                        + "; falling back to runner task container logs and Gitea Actions log files");
                                try {
                                    List<GiteaActionsTaskContainerLog> taskLogs = taskContainerLogsSupplier.get();
                                    if (taskLogs != null && !taskLogs.isEmpty()) {
                                        taskContainerLogsByJobId.put(job.id(), taskLogs);
                                        warnings.add("Captured " + taskLogs.size()
                                                + " runner task container log(s) for job " + job.id());
                                    } else {
                                        warnings.add("No runner task container logs found for job " + job.id());
                                    }
                                } catch (RuntimeException fallbackError) {
                                    warnings.add("Failed to capture runner task container logs for job " + job.id()
                                            + ": " + fallbackError.getMessage());
                                }
                                if (!giteaActionsLogsCaptured) {
                                    giteaActionsLogsCaptured = true;
                                    try {
                                        List<GiteaActionsLogFile> logFiles = giteaActionsLogFilesSupplier.get();
                                        if (logFiles != null && !logFiles.isEmpty()) {
                                            giteaActionsLogFiles = List.copyOf(logFiles);
                                            warnings.add("Captured " + giteaActionsLogFiles.size()
                                                    + " Gitea Actions log file(s) from the container filesystem");
                                        } else {
                                            warnings.add("No Gitea Actions log files found inside the Gitea container");
                                        }
                                    } catch (RuntimeException fallbackError) {
                                        warnings.add("Failed to capture Gitea Actions log files: "
                                                + fallbackError.getMessage());
                                    }
                                }
                            } else {
                                warnings.add("Failed to download logs for job " + job.id() + ": " + e.getMessage());
                            }
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
                taskContainerLogsByJobId,
                giteaActionsLogFiles,
                runnerLogs,
                giteaLogs,
                warnings);
    }

    private boolean isNotFound(RuntimeException e) {
        if (e instanceof GiteaApiException apiException) {
            return apiException.getStatusCode() == 404;
        }
        return false;
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
