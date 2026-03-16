package dev.promptlm.testutils.artifactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Testcontainers wrapper for JFrog Artifactory OSS that provides easy setup and management for tests.
 * 
 * Features:
 * - Automatic container lifecycle management
 * - Admin user authentication with configurable credentials
 * - Maven repository creation and management
 * - CI deployer user creation with proper permissions
 * - Spring Boot integration via system properties
 */
public class ArtifactoryContainer {
    
    private static final Logger logger = LoggerFactory.getLogger(ArtifactoryContainer.class);
    private static final String ARTIFACTORY_IMAGE = "releases-docker.jfrog.io/jfrog/artifactory-oss:7.77.5";
    private static final int ARTIFACTORY_PORT = 8081;
    private static final int ARTIFACTORY_ACCESS_PORT = 8082;
    
    private final GenericContainer<?> container;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private boolean loggingEnabled = false;
    
    private String adminUsername = "admin";
    private String adminPassword = "password"; // Default Artifactory OSS password
    private String deployerUsername = "ci-deployer";
    private String deployerPassword = "ci-deployer-password";
    private String deployerEmail = "ci-deployer@example.com";
    private String mavenRepoName = "libs-release-local";
    private String networkAlias = "artifactory";
    
    /**
     * Create a new Artifactory test harness with default users and repository names.
     */
    public ArtifactoryContainer() {
        this.container = new GenericContainer<>(DockerImageName.parse(ARTIFACTORY_IMAGE))
                .withExposedPorts(ARTIFACTORY_PORT, ARTIFACTORY_ACCESS_PORT)
                .withNetworkAliases(networkAlias)
                .withEnv("JF_SHARED_JAVAOPS", "-Xms1024m -Xmx2048m -Xss256k")
                .withEnv("JF_SHARED_DATABASE_TYPE", "derby")
                .waitingFor(Wait.forHttp("/artifactory/api/system/ping")
                        .forPort(ARTIFACTORY_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(10)))
                .withStartupTimeout(Duration.ofMinutes(10));
        
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    ArtifactoryContainer(GenericContainer<?> container) {
        this.container = container;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Enable streaming container logs to SLF4J.
     *
     * @return this container instance
     */
    public ArtifactoryContainer enableLogging() {
        this.loggingEnabled = true;
        return this;
    }
    
    /**
     * Configure the admin user credentials
     *
     * @param username admin username
     * @param password admin password
     * @return this container instance
     */
    public ArtifactoryContainer withAdminUser(String username, String password) {
        this.adminUsername = username;
        this.adminPassword = password;
        return this;
    }
    
    /**
     * Configure the CI deployer user
     *
     * @param username deployer username
     * @param password deployer password
     * @param email deployer email
     * @return this container instance
     */
    public ArtifactoryContainer withDeployerUser(String username, String password, String email) {
        this.deployerUsername = username;
        this.deployerPassword = password;
        this.deployerEmail = email;
        return this;
    }
    
    /**
     * Configure the Maven repository name
     *
     * @param repoName repository key
     * @return this container instance
     */
    public ArtifactoryContainer withMavenRepository(String repoName) {
        this.mavenRepoName = repoName;
        return this;
    }

    /**
     * Configure the container network alias used by sibling containers.
     *
     * @param alias non-blank network alias
     * @return this container instance
     */
    public ArtifactoryContainer withNetworkAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        this.networkAlias = alias;
        this.container.withNetworkAliases(alias);
        return this;
    }
    
    /**
     * Start the Artifactory container and initialize it
     *
     * @throws IOException when initialization HTTP calls fail
     * @throws InterruptedException when the startup flow is interrupted
     */
    public void start() throws IOException, InterruptedException {
        logger.info("Starting Artifactory container...");
        logger.info("  image: {}", ARTIFACTORY_IMAGE);
        logger.info("  exposed ports: http={} access={}", ARTIFACTORY_PORT, ARTIFACTORY_ACCESS_PORT);
        container.start();
        attachLogsIfEnabled();
        logger.info("  mapped port http: {} -> {}", ARTIFACTORY_PORT, container.getMappedPort(ARTIFACTORY_PORT));
        logger.info("  mapped port access: {} -> {}", ARTIFACTORY_ACCESS_PORT, container.getMappedPort(ARTIFACTORY_ACCESS_PORT));

        // Wait for Artifactory to fully initialize
        waitForArtifactoryReady();

        // Make the mapped HTTP port reachable from sibling containers via host.testcontainers.internal
        Testcontainers.exposeHostPorts(container.getMappedPort(ARTIFACTORY_PORT));

        // Detect correct admin password
        detectAdminPassword();
        
        // Initialize repositories and users
        createMavenRepository();
        createDeployerUser();
        createDeployerPermissions();
        
        logger.info("Artifactory container started successfully at: {}", getWebUrl());
        logger.info("Admin user: {} / {}", adminUsername, maskSecret(adminPassword));
        logger.info("Deployer user: {} / {}", deployerUsername, maskSecret(deployerPassword));
        logger.info("API URL: {}", getApiUrl());
        logger.info("Maven repository: {}", getMavenRepositoryUrl());
    }

    void attachLogsIfEnabled() {
        if (!loggingEnabled) {
            return;
        }
        if (container.getContainerId() == null) {
            logger.debug("Artifactory container id not available yet; skipping log attachment.");
            return;
        }
        container.followOutput(new Slf4jLogConsumer(logger));
    }
    
    /**
     * Stop the Artifactory container
     */
    public void stop() {
        if (container != null && container.isRunning()) {
            logger.info("Stopping Artifactory container...");
            container.stop();
        }
    }
    
    /**
     * Get the web URL for accessing Artifactory UI
     *
     * @return browser-facing Artifactory URL
     */
    public String getWebUrl() {
        return "http://" + resolveHost() + ":" + container.getMappedPort(ARTIFACTORY_PORT);
    }
    
    /**
     * Get the API URL for Artifactory API calls
     *
     * @return browser-facing Artifactory API base URL
     */
    public String getApiUrl() {
        return "http://" + resolveHost() + ":" + container.getMappedPort(ARTIFACTORY_PORT) + "/artifactory";
    }

    /**
     * Get the Artifactory API base URL reachable from sibling containers on the same Docker network.
     *
     * @return internal Artifactory API base URL
     */
    public String getInternalApiUrl() {
        return "http://" + networkAlias + ":" + ARTIFACTORY_PORT + "/artifactory";
    }

    /**
     * Get the Artifactory API base URL reachable from a Testcontainers runner via the host bridge.
     *
     * @return runner-accessible Artifactory API base URL
     */
    public String getRunnerAccessibleApiUrl() {
        return "http://host.testcontainers.internal:" + container.getMappedPort(ARTIFACTORY_PORT) + "/artifactory";
    }
    
    /**
     * Get the Maven repository URL for deployment
     *
     * @return Maven deployment repository URL
     */
    public String getMavenRepositoryUrl() {
        return getApiUrl() + "/" + mavenRepoName;
    }
    
    /**
     * Get the admin username
     *
     * @return admin username
     */
    public String getAdminUsername() {
        return adminUsername;
    }
    
    /**
     * Get the admin password
     *
     * @return admin password
     */
    public String getAdminPassword() {
        return adminPassword;
    }
    
    /**
     * Get the deployer username
     *
     * @return deployer username
     */
    public String getDeployerUsername() {
        return deployerUsername;
    }
    
    /**
     * Get the deployer password
     *
     * @return deployer password
     */
    public String getDeployerPassword() {
        return deployerPassword;
    }

    private String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        return "********";
    }
    
    /**
     * Get the Maven repository name
     *
     * @return Maven repository key
     */
    public String getMavenRepositoryName() {
        return mavenRepoName;
    }
    
    /**
     * Get Basic Auth header for deployer user
     *
     * @return HTTP Basic Authorization header for the deployer user
     */
    public String getDeployerAuthHeader() {
        String credentials = deployerUsername + ":" + deployerPassword;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Check if a repository exists
     *
     * @param repoName repository key
     * @return {@code true} when the repository exists
     */
    public boolean repositoryExists(String repoName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getApiUrl() + "/api/repositories/" + repoName))
                    .header("Authorization", getAdminAuthHeader())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            throw new ArtifactoryInitializationException("Failed to check repository existence: " + repoName, e);
        }
    }
    
    private void waitForArtifactoryReady() {
        logger.info("Waiting for Artifactory to be fully ready...");
        try {
            Awaitility.await("artifactory to respond to /api/system/ping")
                    .pollInterval(Duration.ofSeconds(5))
                    .atMost(Duration.ofMinutes(10))
                    .ignoreExceptions()
                    .until(() -> {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(getApiUrl() + "/api/system/ping"))
                                .GET()
                                .build();

                        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                        return response.statusCode() == 200;
                    });
            logger.info("Artifactory is ready!");
        } catch (ConditionTimeoutException e) {
            throw new RuntimeException("Artifactory failed to become ready within timeout", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed while waiting for Artifactory readiness", e);
        }
    }
    
    private void detectAdminPassword() {
        logger.info("Detecting admin password...");
        
        // Try common default passwords
        String[] passwords = {"password", "admin", "admin123"};
        
        for (String password : passwords) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(getApiUrl() + "/api/system/ping"))
                        .header("Authorization", getBasicAuth(adminUsername, password))
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    this.adminPassword = password;
                    logger.info("Admin password detected");
                    return;
                }
            } catch (Exception e) {
                // Continue trying
                logger.debug("Admin password probe failed for a candidate, retrying.", e);
            }
        }
        
        logger.warn("Could not detect admin password, using configured: {}", adminPassword);
    }
    
    private void createMavenRepository() {
        try {
            logger.info("Ensuring Maven repository '{}' is available", mavenRepoName);

            if (repositoryExists(mavenRepoName)) {
                logger.info("Maven repository '{}' already exists", mavenRepoName);
                return;
            }

            String requestBody = String.format("""
                {
                  "key": "%s",
                  "rclass": "local",
                  "packageType": "maven",
                  "description": "Local Maven repository for CI deployments",
                  "repoLayoutRef": "maven-2-default",
                  "handleReleases": true,
                  "handleSnapshots": true,
                  "maxUniqueSnapshots": 0,
                  "suppressPomConsistencyChecks": false,
                  "blackedOut": false,
                  "propertySets": []
                }
                """, mavenRepoName);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getApiUrl() + "/api/repositories/" + mavenRepoName))
                    .header("Authorization", getAdminAuthHeader())
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 200 || status == 201) {
                logger.info("Created Maven repository: {}", mavenRepoName);
                return;
            }

            logger.info("Repository provisioning returned {} ({}). Falling back to discovery of existing local repositories.",
                    status, response.body());
            selectExistingLocalRepositoryWithRetry();
        } catch (Exception e) {
            throw new ArtifactoryInitializationException("Failed to ensure Maven repository: " + mavenRepoName, e);
        }
    }

    private void selectExistingLocalRepositoryWithRetry() {
        try {
            Awaitility.await("local maven repository discovery")
                    .pollInterval(Duration.ofSeconds(2))
                    .atMost(Duration.ofSeconds(30))
                    .until(() -> selectExistingLocalRepository());
        } catch (ConditionTimeoutException e) {
            throw new ArtifactoryInitializationException("Could not discover a local Maven repository. Ensure the Artifactory distribution contains an accessible local repository.", e);
        }
    }

    private boolean selectExistingLocalRepository() {
        try {
            String[] preferredRepositories = {"example-repo-local", "libs-release-local", "libs-snapshot-local", "ext-release-local", "ext-snapshot-local"};
            for (String candidate : preferredRepositories) {
                if (repositoryExists(candidate)) {
                    mavenRepoName = candidate;
                    logger.info("Using preferred existing repository: {}", mavenRepoName);
                    return true;
                }
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getApiUrl() + "/api/repositories"))
                    .header("Authorization", getAdminAuthHeader())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ArtifactoryInitializationException(String.format("Failed to list repositories: %d - %s", response.statusCode(), response.body()));
            }

            JsonNode repositories = objectMapper.readTree(response.body());
            String fallbackLocal = null;
            if (repositories.isArray()) {
                for (JsonNode repo : repositories) {
                    if (!repo.has("key")) {
                        continue;
                    }
                    boolean isLocal = !repo.has("type") || "LOCAL".equalsIgnoreCase(repo.get("type").asText());
                    boolean isMaven = !repo.has("packageType") || "maven".equalsIgnoreCase(repo.get("packageType").asText());
                    if (isLocal && isMaven) {
                        mavenRepoName = repo.get("key").asText();
                        logger.info("Using discovered repository: {}", mavenRepoName);
                        return true;
                    }
                    if (isLocal && fallbackLocal == null) {
                        fallbackLocal = repo.get("key").asText();
                    }
                }
            }
            if (fallbackLocal != null) {
                mavenRepoName = fallbackLocal;
                logger.info("Using fallback local repository: {}", mavenRepoName);
                return true;
            }
        } catch (Exception e) {
            throw new ArtifactoryInitializationException("Unable to discover existing repositories", e);
        }
        return false;
    }

    private void createDeployerUser() {
        try {
            logger.info("Creating deployer user: {}", deployerUsername);
            
            String requestBody = String.format("""
                {
                  "name": "%s",
                  "email": "%s",
                  "password": "%s",
                  "admin": false,
                  "profileUpdatable": true,
                  "disableUIAccess": false,
                  "internalPasswordDisabled": false,
                  "groups": ["readers"]
                }
                """, deployerUsername, deployerEmail, deployerPassword);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getApiUrl() + "/api/security/users/" + deployerUsername))
                    .header("Authorization", getAdminAuthHeader())
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 201) {
                logger.info("Created deployer user: {}", deployerUsername);
            } else if (status == 200) {
                logger.info("Updated deployer user: {}", deployerUsername);
            } else if (status == 400 && response.body() != null && response.body().contains("Artifactory Pro")) {
                logger.info("Artifactory OSS does not support user provisioning via REST API. Falling back to admin credentials for deployments.");
                this.deployerUsername = adminUsername;
                this.deployerPassword = adminPassword;
            } else {
                throw new ArtifactoryInitializationException(String.format("Failed to create deployer user %s: %d - %s", deployerUsername, status, response.body()));
            }
        } catch (Exception e) {
            throw new ArtifactoryInitializationException("Failed to create deployer user: " + deployerUsername, e);
        }
    }

    private void createDeployerPermissions() {
        try {
            String permissionName = "ci-deployer-permissions";
            logger.info("Creating deployer permissions: {}", permissionName);
            
            String requestBody = String.format("""
                {
                  "name": "%s",
                  "repositories": ["%s"],
                  "principals": {
                    "users": {
                      "%s": ["r", "w", "n", "d"]
                    }
                  }
                }
                """, permissionName, mavenRepoName, deployerUsername);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getApiUrl() + "/api/v2/security/permissions/" + permissionName))
                    .header("Authorization", getAdminAuthHeader())
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 200 || status == 201) {
                logger.info("Created deployer permissions: {}", permissionName);
            } else if (status == 400 && response.body() != null && response.body().contains("Artifactory Pro")) {
                logger.info("Artifactory OSS does not support permission provisioning via REST API. Skipping permission setup.");
            } else {
                throw new ArtifactoryInitializationException(String.format("Failed to create deployer permissions %s: %d - %s", permissionName, status, response.body()));
            }
        } catch (Exception e) {
            throw new ArtifactoryInitializationException("Failed to create deployer permissions", e);
        }
    }

    private String getAdminAuthHeader() {
        return getBasicAuth(adminUsername, adminPassword);
    }

    private String getBasicAuth(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String resolveHost() {
        String host = container.getHost();
        if (host == null || host.isBlank()) {
            return "localhost";
        }
        return host;
    }
}
