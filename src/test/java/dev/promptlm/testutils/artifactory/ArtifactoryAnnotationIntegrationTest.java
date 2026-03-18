package dev.promptlm.testutils.artifactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test exercising the {@link WithArtifactory} annotation support.
 *
 * The test verifies that the annotation spins up an Artifactory Testcontainers
 * instance, wires credentials through system properties, and allows uploading
 * artifacts to the configured repository.
 */
@WithArtifactory
class ArtifactoryAnnotationIntegrationTest {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final HttpClient redirectClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Test
    void shouldProvideFullyOperationalArtifactoryHarness(ArtifactoryContainer artifactory) throws Exception {
        assertThat(artifactory).isNotNull();

        // Verify system properties were wired from the harness
        String repositoryUrl = System.getProperty("artifactory.maven.repository.url");
        String deployerUser = System.getProperty("artifactory.deployer.username");
        String deployerPassword = System.getProperty("artifactory.deployer.password");
        Map<String, String> actionsVariables = artifactory.standardActionsVariables();

        assertThat(repositoryUrl).isNotBlank();
        assertThat(deployerUser).isNotBlank();
        assertThat(deployerPassword).isNotBlank();
        assertThat(actionsVariables)
                .containsEntry(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_URL, artifactory.getRunnerAccessibleApiUrl())
                .containsEntry(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_REPOSITORY, artifactory.getMavenRepositoryName())
                .containsEntry(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_USERNAME, deployerUser)
                .containsEntry(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_PASSWORD, deployerPassword);
        assertThat(System.getProperty(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_URL))
                .isEqualTo(artifactory.getRunnerAccessibleApiUrl());
        assertThat(System.getProperty(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_REPOSITORY))
                .isEqualTo(artifactory.getMavenRepositoryName());
        assertThat(System.getProperty(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_USERNAME))
                .isEqualTo(deployerUser);
        assertThat(System.getProperty(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_PASSWORD))
                .isEqualTo(deployerPassword);

        // Artifactory should report healthy via REST API when using admin credentials
        HttpResponse<String> pingResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(artifactory.getApiUrl() + "/api/system/ping"))
                        .header("Authorization", buildBasicAuthHeader(artifactory.getAdminUsername(), artifactory.getAdminPassword()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(pingResponse.statusCode()).isEqualTo(200);
        assertThat(pingResponse.body()).containsIgnoringCase("ok");

        // Web UI should respond (redirect or auth challenge are acceptable)
        HttpResponse<Void> uiResponse = redirectClient.send(
                HttpRequest.newBuilder(URI.create(artifactory.getWebUrl()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(uiResponse.statusCode()).isEqualTo(200);

        // Repository configured by the harness must exist (when provisioning fails, ensure fallback works)
        assertThat(artifactory.getMavenRepositoryUrl()).isNotBlank();

        // Prepare unique coordinates for the uploaded artifact
        long timestamp = Instant.now().toEpochMilli();
        String groupPath = "dev/promptlm/testutils";
        String artifactId = "artifactory-annotation-it";
        String version = "1.0." + timestamp;
        String jarFileName = artifactId + "-" + version + ".jar";
        String artifactPath = String.join("/", groupPath, artifactId, version, jarFileName);
        String uploadUrl = repositoryUrl.endsWith("/") ? repositoryUrl + artifactPath : repositoryUrl + "/" + artifactPath;

        byte[] dummyJarContent = ("dummy-jar-content-" + timestamp).getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> putResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .header("Content-Type", "application/octet-stream")
                        .header("Authorization", buildBasicAuthHeader(deployerUser, deployerPassword))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(dummyJarContent))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(putResponse.statusCode())
                .withFailMessage("Expected 201 Created when uploading artifact but received %s:%n%s", putResponse.statusCode(), putResponse.body())
                .isEqualTo(201);

        // Fetch the artifact to ensure it is retrievable
        HttpResponse<byte[]> getResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .header("Authorization", buildBasicAuthHeader(deployerUser, deployerPassword))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(getResponse.statusCode()).isEqualTo(200);
        assertThat(getResponse.body()).contains(dummyJarContent);

        // Artifactory should no longer publish unrelated repo-remote properties
        assertThat(System.getProperty("REPO_REMOTE_URL")).isNull();
        assertThat(System.getProperty("REPO_REMOTE_USERNAME")).isNull();
        assertThat(System.getProperty("REPO_REMOTE_TOKEN")).isNull();

        // Deployer credentials must authenticate against REST API as well
        HttpResponse<String> deployerPing = httpClient.send(
                HttpRequest.newBuilder(URI.create(artifactory.getApiUrl() + "/api/system/ping"))
                        .header("Authorization", buildBasicAuthHeader(deployerUser, deployerPassword))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(deployerPing.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Artifactory should accept and serve deployed artifacts")
    void artifactoryShouldServeDeployedArtifacts() throws Exception {
        String repositoryUrl = System.getProperty("artifactory.maven.repository.url");
        assertThat(repositoryUrl).isNotBlank();

        String username = System.getProperty("artifactory.deployer.username");
        String password = System.getProperty("artifactory.deployer.password");
        assertThat(username).isNotBlank();
        assertThat(password).isNotBlank();

        long timestamp = System.currentTimeMillis();
        String artifactPath = "dev/promptlm/acceptance/happy-path-test/happy-path-test-" + timestamp + ".txt";
        String uploadUrl = repositoryUrl.endsWith("/") ? repositoryUrl + artifactPath : repositoryUrl + "/" + artifactPath;
        byte[] payload = ("happy-path-artifact-" + timestamp).getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> putResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .header("Authorization", basicAuth(username, password))
                        .header("Content-Type", "text/plain")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(putResponse.statusCode())
                .withFailMessage("Expected Artifactory to accept artifact upload (201) but received %s:%n%s", putResponse.statusCode(), putResponse.body())
                .isEqualTo(201);

        HttpResponse<byte[]> getResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .header("Authorization", basicAuth(username, password))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(getResponse.statusCode())
                .withFailMessage("Expected Artifactory to serve uploaded artifact (200) but received %s", getResponse.statusCode())
                .isEqualTo(200);
        assertThat(getResponse.body()).contains(payload);
    }


    private String basicAuth(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }


    private String buildBasicAuthHeader(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
