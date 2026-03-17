package dev.promptlm.testutils;

import dev.promptlm.testutils.gitea.Gitea;
import dev.promptlm.testutils.gitea.GiteaActions;
import dev.promptlm.testutils.gitea.GiteaContainer;
import dev.promptlm.testutils.gitea.WithGitea;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@WithGitea(actionsEnabled = true, createTestRepos = true, testRepoNames = {GithubWorkflowsContainerJobsIntegrationTest.REPO_NAME})
class GithubWorkflowsContainerJobsIntegrationTest {

    static final String REPO_NAME = "github-container-jobs-repo";
    private static final String GROUP_ID = "dev.promptlm.workflow";
    private static final String ARTIFACT_ID = "workflow-smoke";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Runs a top-level .github/workflows workflow with job-level container jobs")
    void shouldRunGithubWorkflowContainerJobs(@Gitea GiteaContainer gitea) throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git CLI is required for workflow integration test");

        String version = "1.0.0-" + System.currentTimeMillis();
        String owner = gitea.getAdminUsername();
        String runnerCloneUrl = "http://localhost.localtest.me:%d/%s/%s.git".formatted(
                URI.create(gitea.getWebUrl()).getPort(),
                owner,
                REPO_NAME);

        gitea.waitForRepository(REPO_NAME);
        gitea.enableRepositoryActions(owner, REPO_NAME);
        gitea.resetRepositoryActionsState(owner, REPO_NAME);

        Path repositoryDir = tempDir.resolve(REPO_NAME);
        Files.createDirectories(repositoryDir);
        writeWorkflowProject(repositoryDir, version, runnerCloneUrl, gitea);

        String commitSha = seedRepository(repositoryDir, gitea, owner);

        var report = waitForWorkflowReport(gitea, owner, commitSha);
        if (report.run().conclusion() == null || !"success".equalsIgnoreCase(report.run().conclusion())) {
            printDiagnostics(gitea.collectActionsDiagnostics(owner, REPO_NAME, report.run().id()));
        }
        assertThat(report.run().conclusion()).isEqualToIgnoringCase("success");
        assertThat(report.allJobsTerminal()).isTrue();
        assertThat(report.jobs())
                .extracting(GiteaActions.ActionJobSummary::conclusion)
                .contains("success");
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

    private void writeWorkflowProject(Path repositoryDir,
                                      String version,
                                      String runnerCloneUrl,
                                      GiteaContainer gitea) throws IOException {
        Files.createDirectories(repositoryDir.resolve(".github/workflows"));
        Files.createDirectories(repositoryDir.resolve("src/main/java/dev/promptlm/workflow"));

        Map<String, String> placeholders = Map.of(
                "GROUP_ID", GROUP_ID,
                "ARTIFACT_ID", ARTIFACT_ID,
                "VERSION", version,
                "REPO_CLONE_URL", runnerCloneUrl,
                "REPO_CLONE_USERNAME", gitea.getAdminUsername(),
                "REPO_CLONE_TOKEN", gitea.getAdminToken());

        writeTemplate("dev/promptlm/testutils/ciworkflow/pom.xml.template",
                repositoryDir.resolve("pom.xml"),
                placeholders);
        writeTemplate("dev/promptlm/testutils/ciworkflow/WorkflowSmoke.java.template",
                repositoryDir.resolve("src/main/java/dev/promptlm/workflow/WorkflowSmoke.java"),
                placeholders);
        writeTemplate("dev/promptlm/testutils/ciworkflow/github-container-jobs.yml.template",
                repositoryDir.resolve(".github/workflows/github-container-jobs.yml"),
                placeholders);
    }

    private void writeTemplate(String resourceName, Path target, Map<String, String> placeholders) throws IOException {
        String template = loadResource(resourceName);
        Files.createDirectories(target.getParent());
        Files.writeString(target, applyPlaceholders(template, placeholders));
    }

    private String loadResource(String resourceName) throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing test resource template: " + resourceName);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String applyPlaceholders(String template, Map<String, String> placeholders) {
        String result = template;
        for (var entry : placeholders.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
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
        runCommand(repositoryDir, "git", "commit", "-m", "Seed workflow project");
        String commitSha = runCommand(repositoryDir, "git", "rev-parse", "HEAD").trim();
        runCommand(repositoryDir, "git", "push", "--set-upstream", "origin", "main");
        return commitSha;
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

    private boolean isGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void printDiagnostics(dev.promptlm.testutils.gitea.GiteaActionsDiagnostics diagnostics) {
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
