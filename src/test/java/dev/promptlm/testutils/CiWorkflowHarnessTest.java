package dev.promptlm.testutils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.promptlm.testutils.artifactory.Artifactory;
import dev.promptlm.testutils.artifactory.ArtifactoryContainer;
import dev.promptlm.testutils.artifactory.WithArtifactory;
import dev.promptlm.testutils.gitea.GiteaActions;
import dev.promptlm.testutils.gitea.Gitea;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@WithGitea(actionsEnabled = true, createTestRepos = true, testRepoNames = {CiWorkflowHarnessTest.REPO_NAME})
@WithArtifactory
class CiWorkflowHarnessTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    static final String REPO_NAME = "ci-workflow-repo";
    private static final String GROUP_ID = "dev.promptlm.workflow";
    private static final String ARTIFACT_ID = "workflow-smoke";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Runs a minimal Gitea Actions Maven deploy to Artifactory")
    void shouldBuildAndDeployJarViaGiteaActions(@Gitea GiteaContainer gitea,
                                                @Artifactory ArtifactoryContainer artifactory) throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git CLI is required for CI workflow harness test");

        String version = "1.0.0-" + System.currentTimeMillis();
        String owner = gitea.getAdminUsername();
        String deployRepositoryUrl = artifactory.getRunnerAccessibleApiUrl() + "/" + artifactory.getMavenRepositoryName();
        String runnerCloneUrl = "http://localhost.localtest.me:%d/%s/%s.git".formatted(
                URI.create(gitea.getWebUrl()).getPort(),
                owner,
                REPO_NAME);

        gitea.waitForRepository(REPO_NAME);
        gitea.enableRepositoryActions(owner, REPO_NAME);
        gitea.resetRepositoryActionsState(owner, REPO_NAME);

        Path repositoryDir = tempDir.resolve(REPO_NAME);
        Files.createDirectories(repositoryDir);
        writeWorkflowProject(repositoryDir, version, artifactory, deployRepositoryUrl, runnerCloneUrl, gitea);

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

        String relativeStoragePath = GROUP_ID.replace('.', '/') + "/" + ARTIFACT_ID + "/" + version;
        JsonNode storage = waitForArtifactoryStorage(artifactory, relativeStoragePath);
        assertThat(storage.path("children").isArray()).isTrue();
        assertThat(storage.path("children").size()).isGreaterThan(0);
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

    private void writeWorkflowProject(Path repositoryDir,
                                      String version,
                                      ArtifactoryContainer artifactory,
                                      String deployRepositoryUrl,
                                      String runnerCloneUrl,
                                      GiteaContainer gitea) throws IOException {
        Files.createDirectories(repositoryDir.resolve(".gitea/workflows"));
        Files.createDirectories(repositoryDir.resolve("src/main/java/dev/promptlm/workflow"));

        Map<String, String> placeholders = Map.of(
                "GROUP_ID", GROUP_ID,
                "ARTIFACT_ID", ARTIFACT_ID,
                "VERSION", version,
                "DEPLOY_REPOSITORY_URL", deployRepositoryUrl,
                "REPO_CLONE_URL", runnerCloneUrl,
                "REPO_CLONE_USERNAME", gitea.getAdminUsername(),
                "REPO_CLONE_TOKEN", gitea.getAdminToken(),
                "ARTIFACTORY_USERNAME", artifactory.getDeployerUsername(),
                "ARTIFACTORY_PASSWORD", artifactory.getDeployerPassword());

        writeTemplate("dev/promptlm/testutils/ciworkflow/pom.xml.template",
                repositoryDir.resolve("pom.xml"),
                placeholders);
        writeTemplate("dev/promptlm/testutils/ciworkflow/WorkflowSmoke.java.template",
                repositoryDir.resolve("src/main/java/dev/promptlm/workflow/WorkflowSmoke.java"),
                placeholders);
        writeTemplate("dev/promptlm/testutils/ciworkflow/deploy-artifactory.yml.template",
                repositoryDir.resolve(".gitea/workflows/deploy-artifactory.yml"),
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
        runCommand(repositoryDir, "git", "commit", "-m", "Seed minimal workflow project");
        String commitSha = runCommand(repositoryDir, "git", "rev-parse", "HEAD").trim();
        runCommand(repositoryDir, "git", "push", "--set-upstream", "origin", "main");
        return commitSha;
    }

    private JsonNode waitForArtifactoryStorage(ArtifactoryContainer artifactory, String relativePath) throws Exception {
        Duration timeout = Duration.ofMinutes(3);
        Duration pollInterval = Duration.ofSeconds(5);
        long deadline = System.nanoTime() + timeout.toNanos();

        RuntimeException lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                JsonNode storage = fetchArtifactoryStorage(artifactory, relativePath);
                JsonNode children = storage.path("children");
                if (children.isArray() && children.size() > 0) {
                    return storage;
                }
            } catch (RuntimeException e) {
                lastError = e;
            }
            Thread.sleep(pollInterval.toMillis());
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("Timed out waiting for Artifactory storage path '" + relativePath + "'");
    }

    private JsonNode fetchArtifactoryStorage(ArtifactoryContainer artifactory, String relativePath) {
        try {
            String repo = artifactory.getMavenRepositoryName();
            String normalizedPath = relativePath.startsWith("/") ? relativePath : "/" + relativePath;
            String url = artifactory.getApiUrl() + "/api/storage/" + repo + normalizedPath;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", basicAuth(artifactory.getAdminUsername(), artifactory.getAdminPassword()))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Failed to fetch Artifactory storage: status="
                        + response.statusCode() + " body=" + response.body());
            }

            return OBJECT_MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch Artifactory storage for '" + relativePath + "'", e);
        }
    }

    private boolean isGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String basicAuth(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String runCommand(Path workingDirectory, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        Process process = builder.start();
        String stdout = readFully(process.getInputStream());
        String stderr = readFully(process.getErrorStream());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed (" + String.join(" ", command) + ") exit=" + exitCode
                    + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr);
        }
        return stdout;
    }

    private String readFully(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
