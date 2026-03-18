package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@WithGitea(actionsEnabled = true, createTestRepos = true, testRepoNames = {GiteaRunnerCloneUrlIntegrationTest.REPO_NAME})
class GiteaRunnerCloneUrlIntegrationTest {

    static final String REPO_NAME = "runner-clone-url-repo";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("buildRunnerAccessibleCloneUrl should be reachable from Actions job containers")
    void buildRunnerAccessibleCloneUrlShouldBeReachableFromActionsJobContainers(GiteaContainer gitea) throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git CLI is required for workflow integration test");

        String owner = gitea.getAdminUsername();
        String runnerCloneUrl = gitea.buildRunnerAccessibleCloneUrl(owner, REPO_NAME);

        gitea.waitForRepository(REPO_NAME);
        gitea.enableRepositoryActions(owner, REPO_NAME);
        gitea.resetRepositoryActionsState(owner, REPO_NAME);
        gitea.ensureRepositoryActionsVariable(owner, REPO_NAME, GiteaEnvironmentProperties.REPO_REMOTE_URL, runnerCloneUrl);
        gitea.ensureRepositoryActionsVariable(owner, REPO_NAME,
                GiteaEnvironmentProperties.REPO_REMOTE_USERNAME,
                gitea.getAdminUsername());
        gitea.ensureRepositoryActionsVariable(owner, REPO_NAME,
                GiteaEnvironmentProperties.REPO_REMOTE_TOKEN,
                gitea.getAdminToken());

        Path repositoryDir = tempDir.resolve(REPO_NAME);
        Files.createDirectories(repositoryDir);
        writeWorkflowProject(repositoryDir);

        String commitSha = seedRepository(repositoryDir, gitea, owner);
        GiteaActions.ActionExecutionReport report = waitForWorkflowReport(gitea, owner, commitSha);

        if (report.run().conclusion() == null || !"success".equalsIgnoreCase(report.run().conclusion())) {
            printDiagnostics(gitea.collectActionsDiagnostics(owner, REPO_NAME, report.run().id()));
        }

        assertThat(report.run().conclusion()).isEqualToIgnoringCase("success");
        assertThat(report.allJobsTerminal()).isTrue();
        assertThat(report.jobs())
                .extracting(GiteaActions.ActionJobSummary::conclusion)
                .contains("success");
    }

    private void writeWorkflowProject(Path repositoryDir) throws IOException {
        Files.createDirectories(repositoryDir.resolve(".gitea/workflows"));
        Files.writeString(repositoryDir.resolve(".gitea/workflows/clone-url-reachability.yml"), """
                name: clone-url-reachability
                on:
                  push:
                    branches:
                      - main

                jobs:
                  ls-remote:
                    runs-on: ubuntu-latest
                    container: alpine/git:2.49.1
                    steps:
                      - name: Verify runner clone URL
                        env:
                          REPO_REMOTE_URL: ${{ vars.REPO_REMOTE_URL }}
                          REPO_REMOTE_USERNAME: ${{ vars.REPO_REMOTE_USERNAME }}
                          REPO_REMOTE_TOKEN: ${{ vars.REPO_REMOTE_TOKEN }}
                        run: |
                          test -n "$REPO_REMOTE_URL"
                          test -n "$REPO_REMOTE_USERNAME"
                          test -n "$REPO_REMOTE_TOKEN"
                          git ls-remote "http://${REPO_REMOTE_USERNAME}:${REPO_REMOTE_TOKEN}@${REPO_REMOTE_URL#http://}" | tee /tmp/ls-remote.txt
                          grep 'refs/heads/main' /tmp/ls-remote.txt
                """);
        Files.writeString(repositoryDir.resolve("README.md"), "# Runner clone URL regression\n");
    }

    private GiteaActions.ActionExecutionReport waitForWorkflowReport(GiteaContainer gitea,
                                                                     String owner,
                                                                     String commitSha) {
        try {
            return gitea.actions().waitForWorkflowRunBySha(
                    owner,
                    REPO_NAME,
                    commitSha,
                    Duration.ofMinutes(6),
                    Duration.ofSeconds(2));
        } catch (RuntimeException e) {
            printDiagnostics(gitea.collectActionsDiagnostics(owner, REPO_NAME, null));
            throw e;
        }
    }

    private String seedRepository(Path repositoryDir, GiteaContainer gitea, String owner) throws Exception {
        String remoteUrl = "http://%s:%s@localhost:%d/%s/%s.git".formatted(
                gitea.getAdminUsername(),
                gitea.getAdminToken(),
                URI.create(gitea.getWebUrl()).getPort(),
                owner,
                REPO_NAME);

        runCommand(repositoryDir, "git", "init", "--initial-branch=main");
        runCommand(repositoryDir, "git", "config", "user.name", gitea.getAdminUsername());
        runCommand(repositoryDir, "git", "config", "user.email", gitea.getAdminUsername() + "@example.com");
        runCommand(repositoryDir, "git", "remote", "add", "origin", remoteUrl);
        runCommand(repositoryDir, "git", "add", ".");
        runCommand(repositoryDir, "git", "commit", "-m", "Add clone URL regression workflow");
        String commitSha = runCommand(repositoryDir, "git", "rev-parse", "HEAD").trim();
        runCommand(repositoryDir, "git", "push", "--set-upstream", "origin", "main");
        return commitSha;
    }

    private boolean isGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String runCommand(Path directory, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            input.transferTo(output);
        }
        int exitCode = process.waitFor();
        String result = output.toString(StandardCharsets.UTF_8);
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command) + "\n" + result);
        }
        return result;
    }

    private void printDiagnostics(GiteaActionsDiagnostics diagnostics) {
        if (diagnostics == null) {
            System.err.println("No Gitea Actions diagnostics available.");
            return;
        }

        System.err.println("=== Gitea Actions Diagnostics ===");
        System.err.println("traceId=" + diagnostics.traceId());
        System.err.println("repo=" + diagnostics.repoOwner() + "/" + diagnostics.repoName());
        System.err.println("capturedAt=" + diagnostics.capturedAt());
        System.err.println("giteaWorkflowFiles=" + diagnostics.giteaWorkflowFiles());
        System.err.println("githubWorkflowFiles=" + diagnostics.githubWorkflowFiles());
        System.err.println("runs=" + diagnostics.runs());
        System.err.println("jobsByRunId=" + diagnostics.jobsByRunId());
        if (!diagnostics.warnings().isEmpty()) {
            System.err.println("warnings=" + diagnostics.warnings());
        }
        diagnostics.jobLogsByJobId().forEach((jobId, bytes) -> {
            System.err.println("--- job log " + jobId + " ---");
            System.err.println(new String(bytes, StandardCharsets.UTF_8));
        });
        diagnostics.taskContainerLogsByJobId().forEach((jobId, logs) -> {
            System.err.println("--- task container logs for job " + jobId + " ---");
            logs.forEach(log -> {
                System.err.println("--- task container " + log.containerId() + " " + log.containerNames() + " ---");
                System.err.println(log.logs());
            });
        });
        diagnostics.giteaActionsLogFiles().forEach(logFile -> {
            System.err.println("--- gitea actions log file " + logFile.path() + " (" + logFile.sizeBytes() + " bytes) ---");
            System.err.println(logFile.contents());
        });
        if (diagnostics.runnerLogs() != null && !diagnostics.runnerLogs().isBlank()) {
            System.err.println("--- runner logs ---");
            System.err.println(diagnostics.runnerLogs());
        }
        if (diagnostics.giteaLogs() != null && !diagnostics.giteaLogs().isBlank()) {
            System.err.println("--- gitea logs ---");
            System.err.println(diagnostics.giteaLogs());
        }
        System.err.println("=== End Gitea Actions Diagnostics ===");
    }
}
