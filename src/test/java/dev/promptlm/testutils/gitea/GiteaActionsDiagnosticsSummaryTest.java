package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GiteaActionsDiagnostics#summary()}.
 *
 * <p>The summary string is folded into {@code GiteaWorkflowException}'s message on timeout
 * (see {@link GiteaActions#waitForWorkflowRunBySha}), so it is a security surface: anything
 * that leaks into the summary surfaces in any log line that captures the exception. These
 * tests fix the format and — crucially — prove the redaction discipline: synthetic secret
 * material placed in raw log fields must NOT appear in {@link GiteaActionsDiagnostics#summary()}.
 */
class GiteaActionsDiagnosticsSummaryTest {

    @Test
    void summarizesRunsAndJobs() {
        GiteaActions.ActionRunSummary run = new GiteaActions.ActionRunSummary(
                42L,
                "Deploy",
                "queued",
                null,
                "main",
                "abcdef1234567890",
                "push",
                "http://gitea/run/42",
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:05:00Z"));
        GiteaActions.ActionJobSummary job = new GiteaActions.ActionJobSummary(
                7L,
                "build",
                "running",
                null,
                null,
                null,
                "runner-1");
        GiteaActionsDiagnostics diagnostics = new GiteaActionsDiagnostics(
                "trace-001",
                "owner",
                "repo",
                Instant.parse("2026-01-01T10:05:00Z"),
                List.of("deploy.yml"),
                List.of(),
                List.of(run),
                Map.of(42L, List.of(job)),
                Map.of(),
                Map.of(),
                List.of(),
                "runner log content",
                "gitea log content",
                List.of());

        String summary = diagnostics.summary();

        assertThat(summary)
                .startsWith("trace=trace-001 repo=owner/repo runs=1 jobs=1 warnings=0")
                .contains("workflow_files=[gitea:deploy.yml]")
                .contains("run: id=42 status=queued conclusion=null ref=main sha=abcdef12 event=push name=\"Deploy\"")
                .contains("job: id=7 status=running conclusion=null runner=runner-1 name=\"build\"");
    }

    @Test
    void summaryNeverIncludesRawLogContent() {
        // This is the redaction-discipline proof. We feed synthetic "secret" material into
        // every raw log field that GiteaActionsDiagnostics carries — fields that exist
        // precisely because the diagnostics collector tails runner / Gitea / job container
        // logs, which can echo the runner registration token, HTTP Authorization headers,
        // and caller-supplied workflow secrets. None of that material may leak into the
        // summary string, because the summary is concatenated into the exception message and
        // commonly logged via logger.error(e) by downstream test suites.
        String secret = "GITEA_RUNNER_REGISTRATION_TOKEN=super-secret-token-DO-NOT-LOG";
        byte[] jobLog = ("[step] echo SECRET=" + secret).getBytes();
        GiteaActionsTaskContainerLog containerLog = new GiteaActionsTaskContainerLog(
                "container-1",
                List.of("/build"),
                Instant.parse("2026-01-01T10:00:00Z"),
                "STDOUT secret=" + secret);
        GiteaActionsLogFile logFile = new GiteaActionsLogFile(
                "/var/lib/gitea/actions_log/01.log",
                42L,
                "compressed-bytes contains " + secret);
        GiteaActionsDiagnostics diagnostics = new GiteaActionsDiagnostics(
                "trace-redact",
                "owner",
                "repo",
                Instant.parse("2026-01-01T10:00:00Z"),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(99L, jobLog),
                Map.of(99L, List.of(containerLog)),
                List.of(logFile),
                "runner stdout: " + secret + " --bootstrap",
                "gitea logs: Authorization: token " + secret,
                List.of("warning: see runner logs"));

        String summary = diagnostics.summary();

        assertThat(summary)
                .as("summary must never carry raw log content that can contain secrets")
                .doesNotContain("super-secret-token-DO-NOT-LOG")
                .doesNotContain("Authorization: token")
                .doesNotContain("GITEA_RUNNER_REGISTRATION_TOKEN");
    }

    @Test
    void truncatesRunsAndJobsAtConfiguredCaps() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");
        java.util.List<GiteaActions.ActionRunSummary> manyRuns = new java.util.ArrayList<>();
        java.util.Map<Long, List<GiteaActions.ActionJobSummary>> jobsByRunId = new java.util.HashMap<>();
        for (int i = 1; i <= GiteaActionsDiagnostics.SUMMARY_MAX_RUNS + 3; i++) {
            manyRuns.add(new GiteaActions.ActionRunSummary(
                    i,
                    "run-" + i,
                    "queued",
                    null,
                    "main",
                    "sha" + i,
                    "push",
                    null,
                    base.plusSeconds(i),
                    base.plusSeconds(i + 100)));
        }
        // Build many jobs for the most-recent run.
        java.util.List<GiteaActions.ActionJobSummary> manyJobs = new java.util.ArrayList<>();
        long mostRecentRunId = manyRuns.get(manyRuns.size() - 1).id();
        for (int j = 1; j <= GiteaActionsDiagnostics.SUMMARY_MAX_JOBS_PER_RUN + 2; j++) {
            manyJobs.add(new GiteaActions.ActionJobSummary(
                    j,
                    "job-" + j,
                    "running",
                    null,
                    null,
                    null,
                    "runner-1"));
        }
        jobsByRunId.put(mostRecentRunId, manyJobs);

        GiteaActionsDiagnostics diagnostics = new GiteaActionsDiagnostics(
                "trace-cap",
                "owner",
                "repo",
                base,
                List.of(),
                List.of(),
                manyRuns,
                jobsByRunId,
                Map.of(),
                Map.of(),
                List.of(),
                "",
                "",
                List.of("w1", "w2", "w3", "w4", "w5"));

        String summary = diagnostics.summary();

        long runLines = summary.lines().filter(line -> line.contains("run: id=")).count();
        long jobLines = summary.lines().filter(line -> line.contains("job: id=")).count();
        long warningLines = summary.lines().filter(line -> line.contains("warning:")).count();

        assertThat(runLines).isEqualTo(GiteaActionsDiagnostics.SUMMARY_MAX_RUNS);
        assertThat(jobLines).isEqualTo(GiteaActionsDiagnostics.SUMMARY_MAX_JOBS_PER_RUN);
        assertThat(warningLines).isEqualTo(GiteaActionsDiagnostics.SUMMARY_MAX_WARNINGS);
        assertThat(summary)
                .contains("more jobs)")
                .contains("more warnings)");
    }

    @Test
    void summarizesEmptyDiagnostics() {
        GiteaActionsDiagnostics diagnostics = new GiteaActionsDiagnostics(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        String summary = diagnostics.summary();

        assertThat(summary).isEqualTo("trace=? repo=?/? runs=0 jobs=0 warnings=0");
    }

    @Test
    void preservesShaWhenExactlyAtTheBoundaryLength() {
        // Boundary case for shortSha(): a SHA of exactly SUMMARY_SHA_PREFIX_LEN characters
        // must be returned in full (not truncated and not padded). One shorter and one longer
        // are already exercised by other tests; this pins the equality branch.
        String boundary = "a".repeat(GiteaActionsDiagnostics.SUMMARY_SHA_PREFIX_LEN);
        GiteaActions.ActionRunSummary run = new GiteaActions.ActionRunSummary(
                1L,
                "build",
                "queued",
                null,
                "main",
                boundary,
                "push",
                null,
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:00:00Z"));
        GiteaActionsDiagnostics diagnostics = new GiteaActionsDiagnostics(
                "trace-boundary",
                "owner",
                "repo",
                Instant.parse("2026-01-01T10:00:00Z"),
                List.of(),
                List.of(),
                List.of(run),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                List.of());

        String summary = diagnostics.summary();

        assertThat(summary).contains("sha=" + boundary + " ");
    }

    @Test
    void truncatesLongNames() {
        String longName = "x".repeat(500);
        GiteaActions.ActionRunSummary run = new GiteaActions.ActionRunSummary(
                1L,
                longName,
                "queued",
                null,
                "main",
                "abc",
                "push",
                null,
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:00:00Z"));
        GiteaActionsDiagnostics diagnostics = new GiteaActionsDiagnostics(
                "trace-long",
                "owner",
                "repo",
                Instant.parse("2026-01-01T10:00:00Z"),
                List.of(),
                List.of(),
                List.of(run),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                List.of());

        String summary = diagnostics.summary();

        assertThat(summary)
                .contains("...")
                .doesNotContain("x".repeat(300));
    }

    @Test
    void escapesQuotesAndNewlinesInNames() {
        GiteaActions.ActionRunSummary run = new GiteaActions.ActionRunSummary(
                1L,
                "name with \"quotes\" and\nnewlines",
                "queued",
                null,
                "main",
                "abc",
                "push",
                null,
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:00:00Z"));
        GiteaActionsDiagnostics diagnostics = new GiteaActionsDiagnostics(
                "trace-esc",
                "owner",
                "repo",
                Instant.parse("2026-01-01T10:00:00Z"),
                List.of(),
                List.of(),
                List.of(run),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                List.of());

        String summary = diagnostics.summary();

        // The summary is line-oriented (one run/job/warning per line). Embedded newlines
        // would break the format; embedded double-quotes would confuse readers looking for
        // the closing quote of the name field.
        assertThat(summary)
                .doesNotContain("\nnewlines")
                .doesNotContain("\"quotes\"");
        long runLineCount = summary.lines().filter(line -> line.contains("run: id=")).count();
        assertThat(runLineCount).isEqualTo(1);
    }
}
