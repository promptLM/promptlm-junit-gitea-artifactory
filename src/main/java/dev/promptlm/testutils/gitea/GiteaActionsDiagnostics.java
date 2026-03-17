package dev.promptlm.testutils.gitea;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Structured snapshot of Actions-related diagnostics collected from a Gitea test harness.
 *
 * @param traceId correlation id for this diagnostics capture
 * @param repoOwner repository owner
 * @param repoName repository name
 * @param capturedAt capture timestamp
 * @param giteaWorkflowFiles workflow files found under {@code .gitea/workflows}
 * @param githubWorkflowFiles workflow files found under {@code .github/workflows}
 * @param runs workflow runs returned by Gitea
 * @param jobsByRunId workflow jobs grouped by run id
 * @param jobLogsByJobId raw job logs grouped by job id
 * @param taskContainerLogsByJobId fallback task container logs grouped by job id
 * @param giteaActionsLogFiles fallback Actions log files captured from inside the Gitea container
 * @param runnerLogs runner container logs
 * @param giteaLogs Gitea container logs
 * @param warnings warnings emitted while collecting diagnostics
 */
public record GiteaActionsDiagnostics(String traceId,
                                     String repoOwner,
                                     String repoName,
                                     Instant capturedAt,
                                     List<String> giteaWorkflowFiles,
                                     List<String> githubWorkflowFiles,
                                     List<GiteaActions.ActionRunSummary> runs,
                                     Map<Long, List<GiteaActions.ActionJobSummary>> jobsByRunId,
                                     Map<Long, byte[]> jobLogsByJobId,
                                     Map<Long, List<GiteaActionsTaskContainerLog>> taskContainerLogsByJobId,
                                     List<GiteaActionsLogFile> giteaActionsLogFiles,
                                     String runnerLogs,
                                     String giteaLogs,
                                     List<String> warnings) {
}
