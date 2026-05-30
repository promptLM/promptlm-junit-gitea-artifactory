package dev.promptlm.testutils.gitea;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /** Maximum number of most-recent runs surfaced by {@link #summary()}. */
    public static final int SUMMARY_MAX_RUNS = 5;

    /** Maximum number of jobs per run surfaced by {@link #summary()}. */
    public static final int SUMMARY_MAX_JOBS_PER_RUN = 5;

    /** Maximum number of warning messages surfaced by {@link #summary()}. */
    public static final int SUMMARY_MAX_WARNINGS = 3;

    /** Length of the SHA prefix shown in the summary. */
    public static final int SUMMARY_SHA_PREFIX_LEN = 8;

    /**
     * Render a redacted, human-readable summary of this diagnostics snapshot suitable for
     * surfacing in exception messages and log lines.
     *
     * <p>The summary selects fields explicitly: trace id, repo identity, workflow file names,
     * and per-run + per-job state. It never includes raw log content
     * ({@link #jobLogsByJobId()}, {@link #runnerLogs()}, {@link #giteaLogs()},
     * {@link #taskContainerLogsByJobId()}, {@link #giteaActionsLogFiles()}), because those can
     * carry caller-supplied secrets, the runner registration token, or HTTP authorization
     * header echoes. Callers that want raw logs must access them via the explicit getters on
     * the record — making secret promotion into log lines an opt-in decision.
     *
     * <p>Output is bounded: at most {@value #SUMMARY_MAX_RUNS} most-recent runs,
     * {@value #SUMMARY_MAX_JOBS_PER_RUN} jobs per run, and
     * {@value #SUMMARY_MAX_WARNINGS} warning messages. SHA values are truncated to
     * {@value #SUMMARY_SHA_PREFIX_LEN} characters.
     *
     * @return multi-line summary string; never {@code null}
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("trace=").append(traceId == null ? "?" : traceId)
                .append(" repo=").append(repoOwner == null ? "?" : repoOwner)
                .append('/').append(repoName == null ? "?" : repoName);
        int runCount = runs == null ? 0 : runs.size();
        int jobCount = countJobs();
        int warningCount = warnings == null ? 0 : warnings.size();
        sb.append(" runs=").append(runCount)
                .append(" jobs=").append(jobCount)
                .append(" warnings=").append(warningCount);
        appendWorkflowFiles(sb);

        for (GiteaActions.ActionRunSummary run : selectRecentRuns()) {
            sb.append('\n').append("  run:")
                    .append(" id=").append(run.id())
                    .append(" status=").append(nullSafe(run.status()))
                    .append(" conclusion=").append(nullSafe(run.conclusion()))
                    .append(" ref=").append(nullSafe(run.headBranch()))
                    .append(" sha=").append(shortSha(run.headSha()))
                    .append(" event=").append(nullSafe(run.event()))
                    .append(" name=\"").append(safeName(run.name())).append('"');
            appendJobs(sb, run.id());
        }

        appendWarnings(sb);
        return sb.toString();
    }

    private int countJobs() {
        if (jobsByRunId == null || jobsByRunId.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (List<GiteaActions.ActionJobSummary> jobs : jobsByRunId.values()) {
            if (jobs != null) {
                total += jobs.size();
            }
        }
        return total;
    }

    private void appendWorkflowFiles(StringBuilder sb) {
        boolean haveGitea = giteaWorkflowFiles != null && !giteaWorkflowFiles.isEmpty();
        boolean haveGithub = githubWorkflowFiles != null && !githubWorkflowFiles.isEmpty();
        if (!haveGitea && !haveGithub) {
            return;
        }
        sb.append(" workflow_files=[");
        boolean first = true;
        if (haveGitea) {
            sb.append("gitea:");
            sb.append(String.join(",", giteaWorkflowFiles));
            first = false;
        }
        if (haveGithub) {
            if (!first) {
                sb.append(' ');
            }
            sb.append("github:");
            sb.append(String.join(",", githubWorkflowFiles));
        }
        sb.append(']');
    }

    private List<GiteaActions.ActionRunSummary> selectRecentRuns() {
        if (runs == null || runs.isEmpty()) {
            return List.of();
        }
        List<GiteaActions.ActionRunSummary> ordered = new ArrayList<>(runs);
        ordered.sort(Comparator.comparing(
                GiteaActionsDiagnostics::runSortKey,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (ordered.size() <= SUMMARY_MAX_RUNS) {
            return ordered;
        }
        return ordered.subList(0, SUMMARY_MAX_RUNS);
    }

    private static Instant runSortKey(GiteaActions.ActionRunSummary run) {
        if (run.updatedAt() != null) {
            return run.updatedAt();
        }
        return run.createdAt();
    }

    private void appendJobs(StringBuilder sb, long runId) {
        if (jobsByRunId == null) {
            return;
        }
        List<GiteaActions.ActionJobSummary> jobs = jobsByRunId.get(runId);
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        int limit = Math.min(jobs.size(), SUMMARY_MAX_JOBS_PER_RUN);
        for (int i = 0; i < limit; i++) {
            GiteaActions.ActionJobSummary job = jobs.get(i);
            sb.append('\n').append("    job:")
                    .append(" id=").append(job.id())
                    .append(" status=").append(nullSafe(job.status()))
                    .append(" conclusion=").append(nullSafe(job.conclusion()))
                    .append(" runner=").append(nullSafe(job.runnerName()))
                    .append(" name=\"").append(safeName(job.name())).append('"');
        }
        if (jobs.size() > limit) {
            sb.append('\n').append("    ... (")
                    .append(jobs.size() - limit)
                    .append(" more jobs)");
        }
    }

    private void appendWarnings(StringBuilder sb) {
        if (warnings == null || warnings.isEmpty()) {
            return;
        }
        int limit = Math.min(warnings.size(), SUMMARY_MAX_WARNINGS);
        for (int i = 0; i < limit; i++) {
            sb.append('\n').append("  warning: ")
                    .append(safeName(Objects.toString(warnings.get(i), "")));
        }
        if (warnings.size() > limit) {
            sb.append('\n').append("  ... (")
                    .append(warnings.size() - limit)
                    .append(" more warnings)");
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "null" : value;
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isEmpty()) {
            return "null";
        }
        if (sha.length() <= SUMMARY_SHA_PREFIX_LEN) {
            return sha;
        }
        return sha.substring(0, SUMMARY_SHA_PREFIX_LEN);
    }

    private static String safeName(String value) {
        if (value == null) {
            return "";
        }
        // Compact whitespace and trim to keep the summary one-entry-per-line. Length is
        // bounded so a runaway run/job/warning name cannot blow up the message.
        String collapsed = value.replace('\n', ' ').replace('\r', ' ').replace('"', '\'');
        if (collapsed.length() > 200) {
            return collapsed.substring(0, 200) + "...";
        }
        return collapsed;
    }
}
