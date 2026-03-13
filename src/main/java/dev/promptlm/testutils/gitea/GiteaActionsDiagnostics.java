package dev.promptlm.testutils.gitea;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record GiteaActionsDiagnostics(String traceId,
                                     String repoOwner,
                                     String repoName,
                                     Instant capturedAt,
                                     List<String> giteaWorkflowFiles,
                                     List<String> githubWorkflowFiles,
                                     List<GiteaActions.ActionRunSummary> runs,
                                     Map<Long, List<GiteaActions.ActionJobSummary>> jobsByRunId,
                                     Map<Long, byte[]> jobLogsByJobId,
                                     String runnerLogs,
                                     String giteaLogs,
                                     List<String> warnings) {
}
