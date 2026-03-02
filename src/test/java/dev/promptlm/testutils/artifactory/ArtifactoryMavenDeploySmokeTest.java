package dev.promptlm.testutils.artifactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@WithArtifactory
class ArtifactoryMavenDeploySmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @TempDir
    Path tempDir;

    @Test
    void shouldDeployMavenArtifactToArtifactoryViaAltDeploymentRepository(ArtifactoryContainer artifactory) throws Exception {
        String groupId = "dev.promptlm.deploysmoke";
        String artifactId = "deploy-smoke";
        String version = "1.0.0-" + System.currentTimeMillis() + "-SNAPSHOT";

        Path projectDir = tempDir.resolve("deploy-project");
        Files.createDirectories(projectDir);

        writeMavenProject(projectDir, groupId, artifactId, version);

        String artifactoryUrl = artifactory.getRunnerAccessibleApiUrl();
        String repositoryName = artifactory.getMavenRepositoryName();

        try (GenericContainer<?> maven = new GenericContainer<>(DockerImageName.parse("maven:3.9.6-eclipse-temurin-17"))) {
            maven.withFileSystemBind(projectDir.toAbsolutePath().toString(), "/workspace", BindMode.READ_WRITE)
                    .withWorkingDirectory("/workspace")
                    .withEnv("ARTIFACTORY_URL", artifactoryUrl)
                    .withEnv("ARTIFACTORY_REPOSITORY", repositoryName)
                    .withEnv("ARTIFACTORY_USERNAME", artifactory.getDeployerUsername())
                    .withEnv("ARTIFACTORY_PASSWORD", artifactory.getDeployerPassword())
                    .withCommand("sleep", "3600")
                    .withStartupTimeout(Duration.ofMinutes(5));

            maven.start();

            String script = "set -euo pipefail\n" +
                    "export DEBIAN_FRONTEND=noninteractive\n" +
                    "apt-get update\n" +
                    "apt-get install -y curl\n" +
                    "PING_URL=\"${ARTIFACTORY_URL%/}/api/system/ping\"\n" +
                    "curl --fail --silent --show-error --max-time 20 \"$PING_URL\" > /dev/null\n" +
                    "DEPLOY_REPO_URL=\"${ARTIFACTORY_URL%/}/${ARTIFACTORY_REPOSITORY}\"\n" +
                    "mkdir -p ~/.m2\n" +
                    "cat > ~/.m2/settings.xml <<'EOF'\n" +
                    "<settings>\n" +
                    "  <servers>\n" +
                    "    <server>\n" +
                    "      <id>artifactory</id>\n" +
                    "      <username>${ARTIFACTORY_USERNAME}</username>\n" +
                    "      <password>${ARTIFACTORY_PASSWORD}</password>\n" +
                    "    </server>\n" +
                    "  </servers>\n" +
                    "</settings>\n" +
                    "EOF\n" +
                    "mvn -B -ntp deploy -DskipTests " +
                    "-DaltDeploymentRepository=artifactory::default::${DEPLOY_REPO_URL} " +
                    "-Dartifactory.url=$ARTIFACTORY_URL " +
                    "-Dartifactory.repository=$ARTIFACTORY_REPOSITORY " +
                    "-Dartifactory.username=$ARTIFACTORY_USERNAME " +
                    "-Dartifactory.password=$ARTIFACTORY_PASSWORD\n";

            Container.ExecResult result = maven.execInContainer("bash", "-lc", script);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("mvn deploy failed (exit=" + result.getExitCode() + ")\nstdout:\n" + result.getStdout() + "\nstderr:\n" + result.getStderr());
            }

            resolveCurrentUser().ifPresent(user -> {
                try {
                    maven.execInContainer("bash", "-lc", "chown -R " + user + " /workspace");
                } catch (Exception ignored) {
                    // Cleanup best-effort; fall back to default permissions if chown fails.
                }
            });
        }

        String relativeStoragePath = groupId.replace('.', '/') + "/" + artifactId + "/" + version;
        JsonNode storage = waitForArtifactoryStorage(artifactory, relativeStoragePath);
        assertNotNull(storage.path("children"), "Artifactory storage response should include children");
    }

    private Optional<String> resolveCurrentUser() {
        try {
            Process uidProc = new ProcessBuilder("id", "-u").start();
            Process gidProc = new ProcessBuilder("id", "-g").start();
            String uid = new String(uidProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String gid = new String(gidProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!uid.isEmpty() && !gid.isEmpty()) {
                return Optional.of(uid + ":" + gid);
            }
        } catch (Exception ignored) {
            // Fallback to default container user when uid/gid resolution fails (e.g., non-POSIX platforms).
        }
        return Optional.empty();
    }

    private void writeMavenProject(Path projectDir, String groupId, String artifactId, String version) throws Exception {
        String pom = """
                <project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
                         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>jar</packaging>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                  </properties>
                </project>
                """.formatted(groupId, artifactId, version);

        Files.writeString(projectDir.resolve("pom.xml"), pom);

        Path srcDir = projectDir.resolve("src/main/java/dev/promptlm/deploysmoke");
        Files.createDirectories(srcDir);

        String source = """
                package dev.promptlm.deploysmoke;

                public class DeploySmoke {
                    public static String hello() {
                        return \"ok\";
                    }
                }
                """;
        Files.writeString(srcDir.resolve("DeploySmoke.java"), source);
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
            String normalizedPath = relativePath.startsWith("/") ? relativePath : (relativePath.isEmpty() ? "" : "/" + relativePath);
            String url = artifactory.getApiUrl() + "/api/storage/" + repo + normalizedPath;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", artifactory.getDeployerAuthHeader())
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Failed to fetch Artifactory storage: status=" + response.statusCode() + " body=" + response.body());
            }

            return OBJECT_MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch Artifactory storage for '" + relativePath + "'", e);
        }
    }
}
