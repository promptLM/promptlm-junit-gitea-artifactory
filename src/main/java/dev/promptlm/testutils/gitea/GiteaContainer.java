package dev.promptlm.testutils.gitea;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.awaitility.Awaitility;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

/**
 * Testcontainers wrapper for Gitea that provides easy setup and management for tests.
 * <p>
 * Features:
 * - Automatic container lifecycle management
 * - Admin user creation with access token
 * - Repository creation helpers
 * - Spring Boot integration via system properties
 */
public class GiteaContainer {

    private static final Logger logger = LoggerFactory.getLogger(GiteaContainer.class);
    private static final String GITEA_IMAGE = resolveImage("gitea.image", "GITEA_IMAGE",
            "docker.gitea.com/gitea:1.25.4-rootless");
    private static final int GITEA_PORT = 3000;
    private static final String GITEA_RUNNER_IMAGE = resolveImage("gitea.runner.image", "GITEA_RUNNER_IMAGE",
            "docker.io/gitea/act_runner:0.2.11");
    private static final String RUNNER_NAME = "gitea-runner";
    private static final String DOCKER_IMAGE_LABEL = resolveImage("gitea.actions.job.image", "GITEA_ACTIONS_JOB_IMAGE",
            "docker://ghcr.io/catthehacker/ubuntu:act-22.04");

    private static final class FixedPortGenericContainer<SELF extends FixedPortGenericContainer<SELF>> extends GenericContainer<SELF> {

        private FixedPortGenericContainer(String dockerImageName) {
            super(dockerImageName);
        }

        private FixedPortGenericContainer(DockerImageName dockerImageName) {
            super(dockerImageName);
        }

        private SELF withFixedExposedPort(int hostPort, int containerPort) {
            // addFixedExposedPort is protected in GenericContainer
            this.addFixedExposedPort(hostPort, containerPort);
            return self();
        }
    }

    private final GenericContainer<?> container;
    private GenericContainer<?> runner;

    private final HttpClient httpClient;
    private final Path runnerDataDir;
    private final Path runnerConfigFile;
    private final int fixedHttpPort;

    static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE_VERSION = "20.17.0";
    private static final Duration DEFAULT_REPO_WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_REPO_WAIT_INTERVAL = Duration.ofMillis(500);

    private String adminUsername = "testuser";
    private String adminPassword = "testpass123";
    private String adminEmail = "test@example.com";
    private String adminToken;
    private boolean actionsEnabled;
    private String runnerRegistrationToken;
    private final GiteaActionsSupport actionsSupport;

    private static String resolveImage(String propertyKey, String envKey, String defaultValue) {
        String value = System.getProperty(propertyKey);
        if (isBlank(value)) {
            value = System.getenv(envKey);
        }
        return isBlank(value) ? defaultValue : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public GiteaContainer() {
        this.fixedHttpPort = reservePort();
        // Use a host alias that resolves to localhost on the host machine, while we
        // remap it to host-gateway inside Actions job containers.
        String internalRootHost = "localhost.localtest.me";
        String rootUrl = "http://" + internalRootHost + ":" + fixedHttpPort + "/";

        this.container = new FixedPortGenericContainer<>(GITEA_IMAGE)
                .withFixedExposedPort(fixedHttpPort, GITEA_PORT)
                .withExposedPorts(GITEA_PORT)
                .withEnv("GITEA__database__DB_TYPE", "sqlite3")
                .withEnv("GITEA__database__PATH", "/var/lib/gitea/data/gitea.db")
                .withEnv("GITEA__security__INSTALL_LOCK", "true")
                .withEnv("GITEA__server__DOMAIN", internalRootHost)
                .withEnv("GITEA__server__SSH_DOMAIN", internalRootHost)
                .withEnv("GITEA__server__ROOT_URL", rootUrl)
                .withEnv("GITEA__server__DISABLE_SSH", "true")
                .withEnv("GITEA__server__START_SSH_SERVER", "false")
                .withEnv("GITEA__server__PROTOCOL", "http")
                .withEnv("GITEA__server__ENABLE_GZIP", "true")
                .withEnv("GITEA__git__ENABLE_AUTO_GIT_WIRE_PROTOCOL", "true")
                .withEnv("GITEA__server__LFS_START_SERVER", "true")
                .withEnv("GITEA__repository__ENABLE_PUSH_CREATE_USER", "true")
                .withEnv("GITEA__repository__ENABLE_PUSH_CREATE_ORG", "true")
                .withEnv("GITEA__queue__TYPE", "channel")
                .withEnv("USER_UID", "1000")
                .withEnv("USER_GID", "1000")
                .waitingFor(Wait.forHttp("/").forPort(GITEA_PORT).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(2));

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();

        this.actionsSupport = new GiteaActionsSupport(httpClient, logger, this::getApiUrl, () -> adminToken);

        try {
            this.runnerDataDir = Files.createTempDirectory("gitea-runner-data-");
            this.runnerConfigFile = runnerDataDir.resolve("config.yaml");
            Files.createDirectories(runnerDataDir.resolve("_work"));
        } catch (IOException e) {
            throw new RuntimeException("Could not create runner data directory", e);
        }
    }

    private int reservePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to reserve port", e);
        }
    }

    /**
     * Wait for a repository to become available in Gitea using default timeouts.
     */
    public void waitForRepository(String repoName) {
        waitForRepository(repoName, DEFAULT_REPO_WAIT_TIMEOUT, DEFAULT_REPO_WAIT_INTERVAL);
    }

    /**
     * Wait for a repository to become available in Gitea.
     */
    public void waitForRepository(String repoName, Duration timeout, Duration pollInterval) {
        Awaitility.await("repository " + repoName + " to exist")
                .pollInterval(pollInterval)
                .atMost(timeout)
                .ignoreExceptions()
                .until(() -> repositoryExists(repoName));
    }

    /**
     * Create or update a repository Actions variable to the desired value.
     */
    public void ensureRepositoryActionsVariable(String repoOwner, String repoName, String variableName, String value) {
        actionsSupport.ensureRepositoryActionsVariable(repoOwner, repoName, variableName, value);
    }

    /**
     * Read the value of a repository Actions variable.
     */
    public String readRepositoryActionsVariable(String repoOwner, String repoName, String variableName) {
        return actionsSupport.readRepositoryActionsVariable(repoOwner, repoName, variableName);
    }

    private void installNodeInRunner() {
        try {
            var check = runner.execInContainer("sh", "-lc", "command -v node >/dev/null 2>&1 && node --version");
            if (check.getExitCode() == 0) {
                logger.info("Actions runner already has Node.js: {}", check.getStdout().trim());
                return;
            }
        } catch (Exception e) {
            logger.debug("Node.js check failed, proceeding with installation", e);
        }

        try {
            var result = runner.execInContainer("sh", "-lc", buildNodeInstallScript());
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Node.js installation failed. exit=" + result.getExitCode()
                        + " stdout=" + result.getStdout() + " stderr=" + result.getStderr());
            }

            ensureNodeAvailable();
            logger.info("Installed Node.js in Actions runner: {}",
                    runner.execInContainer("sh", "-lc", "node --version").getStdout().trim());
        } catch (Exception e) {
            throw new RuntimeException("Failed to provision Node.js inside Actions runner", e);
        }
    }

    private void ensureNodeAvailable() throws Exception {
        var check = runner.execInContainer("sh", "-lc", "node --version");
        if (check.getExitCode() != 0) {
            throw new IllegalStateException("Node.js not available after installation attempts. exit=" + check.getExitCode()
                    + " stdout=" + check.getStdout() + " stderr=" + check.getStderr());
        }
    }

    private String buildNodeInstallScript() {
        return """
                set -euo pipefail
                if command -v node >/dev/null 2>&1; then
                  node --version
                  exit 0
                fi

                install_with_pkg() {
                  if command -v apk >/dev/null 2>&1; then
                    apk update
                    apk add --no-cache nodejs npm sudo
                    return 0
                  fi
                  if command -v apt-get >/dev/null 2>&1; then
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get update
                    apt-get install -y nodejs npm curl ca-certificates gnupg sudo
                    return 0
                  fi
                  return 1
                }

                download_to() {
                  url="$1"
                  out="$2"
                  if command -v curl >/dev/null 2>&1; then
                    curl -fsSL "$url" -o "$out"
                  elif command -v wget >/dev/null 2>&1; then
                    wget -q -O "$out" "$url"
                  else
                    echo 'Neither curl nor wget available for Node download' >&2
                    exit 1
                  fi
                }

                install_with_tarball() {
                  NODE_VERSION="%s"
                  case "$(uname -m)" in
                    x86_64) NODE_ARCH=linux-x64 ;;
                    aarch64|arm64) NODE_ARCH=linux-arm64 ;;
                    *) echo 'Unsupported architecture for Node install' >&2; exit 1 ;;
                  esac
                  TMP_DIR=$(mktemp -d)
                  download_to "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-${NODE_ARCH}.tar.xz" "$TMP_DIR/node.tar.xz"
                  mkdir -p /usr/local/lib/nodejs
                  tar -xJf "$TMP_DIR/node.tar.xz" -C /usr/local/lib/nodejs
                  NODE_DIR=/usr/local/lib/nodejs/node-v${NODE_VERSION}-${NODE_ARCH}
                  ln -sf "$NODE_DIR/bin/node" /usr/local/bin/node
                  ln -sf "$NODE_DIR/bin/npm" /usr/local/bin/npm
                  ln -sf "$NODE_DIR/bin/npx" /usr/local/bin/npx
                  rm -rf "$TMP_DIR"
                  if ! command -v sudo >/dev/null 2>&1; then
                    printf '#!/bin/sh\\nif [ "$1" = "--version" ]; then\\n  echo "sudo stub 1.0"\\n  exit 0\\nfi\\nexec "$@"\\n' > /usr/local/bin/sudo
                    chmod +x /usr/local/bin/sudo
                  fi
                }

                if ! install_with_pkg; then
                  install_with_tarball
                fi

                if ! command -v sudo >/dev/null 2>&1; then
                  if command -v apk >/dev/null 2>&1; then
                    apk add --no-cache sudo || true
                  elif command -v apt-get >/dev/null 2>&1; then
                    apt-get install -y sudo || true
                  fi
                fi

                node --version
                """.formatted(NODE_VERSION);
    }

    /**
     * Generate an access token for the Actions runner using Gitea CLI
     */
    private String provisionActionsRunnerToken() {
        logger.info("Requesting Actions runner registration token via CLI");
        String token = tryGenerateRunnerTokenViaCli();

        if (token == null || token.isBlank()) {
            logger.warn("CLI runner token generation failed, attempting REST API fallback");
            token = tryGenerateRunnerTokenViaApi();
        }

        if (token == null || token.isBlank()) {
            throw new RuntimeException("Could not obtain Actions runner registration token");
        }

        logger.info("Obtained Actions runner registration token");
        return token;
    }

    private String tryGenerateRunnerTokenViaCli() {
        try {
            var result = container.execInContainer("gitea", "actions", "generate-runner-token");
            String stdout = result.getStdout().trim();
            if (!stdout.isBlank()) {
                logger.debug("Runner token CLI output: {}", stdout);
                String token = findToken(stdout);
                if (token != null) {
                    return token;
                }
            }
            if (!result.getStderr().isBlank()) {
                logger.warn("Runner token CLI stderr: {}", result.getStderr().trim());
            }
        } catch (Exception e) {
            logger.warn("Runner token CLI command failed", e);
        }
        return null;
    }

    private String tryGenerateRunnerTokenViaApi() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getApiUrl() + "/actions/runners/registration-token"))
                    .header("Authorization", "token " + adminToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                logger.debug("Runner token API response: {}", body);
                return extractTokenFromResponse(body);
            }

            logger.warn("Runner token API request failed: {} - {}", response.statusCode(), response.body());
        } catch (Exception e) {
            logger.warn("Runner token API call failed", e);
        }
        return null;
    }

    private String extractTokenFromResponse(String body) {
        if (body == null) {
            return null;
        }
        return findToken(body);
    }

    private String findToken(String content) {
        if (content == null) {
            return null;
        }
        for (int i = 0; i < content.length(); i++) {
            int end = i;
            while (end < content.length() && Character.isLetterOrDigit(content.charAt(end))) {
                end++;
            }
            if (end - i >= 20) { // tokens are 40 chars but accept >=20 to be safe
                return content.substring(i, end);
            }
            i = end;
        }
        return null;
    }

    /**
     * Configure the admin user that will be created inside the Gitea instance.
     */
    public GiteaContainer withAdminUser(String username, String password, String email) {
        this.adminUsername = username;
        this.adminPassword = password;
        this.adminEmail = email;
        return this;
    }

    /**
     * Enable or disable Actions support for this container. Must be called before {@link #start()}.
     */
    public GiteaContainer withActionsEnabled(boolean enabled) {
        this.actionsEnabled = enabled;

        if (enabled) {
            container.withEnv("GITEA__actions__ENABLED", "true")
                    .withEnv("GITEA__actions__DEFAULT_ACTIONS_URL", "github")
                    .withEnv("GITEA__actions__DEFAULT_ACTIONS_URL_GITHUB", "https://github.com");
        }

        return this;
    }

    private String buildInternalInstanceUrl() {
        return "http://host.docker.internal:" + fixedHttpPort;
    }

    private void logRunnerDiagnostics(String reason) {
        logger.warn("Runner diagnostics requested: {}", reason);
        try {
            logger.warn("Gitea webUrl={} apiUrl={} httpPort={}", getWebUrl(), getApiUrl(), fixedHttpPort);
        } catch (Exception e) {
            logger.warn("Failed to compute Gitea URLs during diagnostics", e);
        }

        logGiteaActionsConfig();
        logGiteaActionsDatabaseState();

        try {
            boolean rest = isRunnerRegisteredViaRest(RUNNER_NAME);
            boolean sqlite = isRunnerRegisteredViaDatabase(RUNNER_NAME);
            logger.warn("Runner registration checks: rest={} sqlite={}", rest, sqlite);
        } catch (Exception e) {
            logger.warn("Runner registration checks failed", e);
        }

        if (runner != null) {
            try {
                logger.warn("Runner container isRunning={}", runner.isRunning());
            } catch (Exception e) {
                logger.warn("Failed to query runner container state", e);
            }
            try {
                String logs = runner.getLogs();
                if (logs != null && !logs.isBlank()) {
                    logger.warn("Runner container logs:\n{}", logs);
                } else {
                    logger.warn("Runner container logs were empty");
                }
            } catch (Exception e) {
                logger.warn("Failed to read runner container logs", e);
            }
        } else {
            logger.warn("Runner container not initialised");
        }
    }

    private void logGiteaActionsConfig() {
        try {
            var result = container.execInContainer(
                    "sh",
                    "-lc",
                    "if [ -f /data/gitea/conf/app.ini ]; then awk '/^\\[actions\\]/{flag=1} flag{print} /^\\[/{if (NR!=1 && $0!~\"\\[actions\\]\") exit}' /data/gitea/conf/app.ini; fi");
            if (!result.getStdout().isBlank()) {
                logger.warn("Gitea app.ini [actions] section:\n{}", result.getStdout().trim());
            } else if (!result.getStderr().isBlank()) {
                logger.warn("Gitea app.ini [actions] section read stderr: {}", result.getStderr().trim());
            } else {
                logger.warn("Gitea app.ini [actions] section not found or empty");
            }
        } catch (Exception e) {
            logger.warn("Failed to read Gitea actions config", e);
        }
    }

    private void logGiteaActionsDatabaseState() {
        try {
            var tables = container.execInContainer(
                    "sqlite3",
                    "/var/lib/gitea/data/gitea.db",
                    "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'action_%' ORDER BY name;");
            String tableList = tables.getStdout().trim();
            if (!tableList.isBlank()) {
                logger.warn("Gitea action tables: {}", tableList.replace("\n", ", "));
            } else if (!tables.getStderr().isBlank()) {
                logger.warn("Gitea action table discovery stderr: {}", tables.getStderr().trim());
            } else {
                logger.warn("No action_* tables found in Gitea database");
            }

            logGiteaActionsTableCount("action_run");
            logGiteaActionsTableCount("action_task");
            logGiteaActionsTableCount("action_runner");
        } catch (Exception e) {
            logger.warn("Failed to inspect Gitea actions database", e);
        }
    }

    private void logGiteaActionsTableCount(String table) {
        try {
            var result = container.execInContainer(
                    "sqlite3",
                    "/var/lib/gitea/data/gitea.db",
                    "SELECT COUNT(1) FROM " + table + ";");
            String output = result.getStdout().trim();
            if (!output.isBlank()) {
                logger.warn("Gitea {} row count: {}", table, output);
            } else if (!result.getStderr().isBlank()) {
                logger.warn("Gitea {} count stderr: {}", table, result.getStderr().trim());
            }
        } catch (Exception e) {
            logger.warn("Failed to count Gitea table {}", table, e);
        }
    }

    /**
     * Start the Gitea container and initialize it
     */
    public void start() {
        logger.info("Starting Gitea container...");
        cleanupActionsTaskContainers(null);
        container.start();

        logger.info("Gitea container.start() returned successfully");
        logger.info("Using fixed HTTP port mapping: {} -> container port {}", fixedHttpPort, GITEA_PORT);

        logger.info("Waiting for Gitea API readiness...");
        waitForGiteaReady();

        logger.info("Creating admin user...");
        createAdminUser();

        logger.info("Generating admin token...");
        generateAccessToken();

        if (actionsEnabled) {
            String token = provisionActionsRunnerToken();
            this.runnerRegistrationToken = token;

            logger.info("Actions enabled. Registration token obtained (length={})", token == null ? 0 : token.length());
            logger.info("Runner will connect to Gitea instance URL: {}", buildInternalInstanceUrl());

            try {
                Files.createDirectories(runnerDataDir);
            } catch (IOException e) {
                throw new GiteaHarnessException("Could not prepare runner data directory", e);
            }

            writeRunnerConfig(RUNNER_NAME);

            this.runner = new GenericContainer<>(DockerImageName.parse(GITEA_RUNNER_IMAGE))
                    .withEnv("CONFIG_FILE", "/data/config.yaml")
                    .withEnv("GITEA_INSTANCE_URL", buildInternalInstanceUrl())
                    .withEnv("GITEA_RUNNER_REGISTRATION_TOKEN", token)
                    .withEnv("GITEA_RUNNER_NAME", RUNNER_NAME)
                    .withEnv("GITEA_RUNNER_INSECURE", "true")
                    .withEnv("GITEA_RUNNER_REGISTRATION_FILE", "/data/.runner")
                    .withFileSystemBind(runnerDataDir.toAbsolutePath().toString(), "/data", BindMode.READ_WRITE)
                    .withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock")
                    .withCreateContainerCmdModifier(cmd -> cmd.withUser("0:0"));

            try {
                runner.start();
                logger.info("Actions runner container started (id={})", runner.getContainerId());
            } catch (Exception e) {
                throw new GiteaHarnessException("Failed to start Actions runner", e);
            }

            installNodeInRunner();
            waitForRunnerRegistration();
        }

        logger.info("Gitea container started successfully at: {}", getWebUrl());
        logger.info("Admin user: {}", adminUsername);
        logger.info("API URL: {}", getApiUrl());
    }

    /**
     * Force-remove stale Gitea Actions task containers from previous runs.
     * <p>
     * This keeps test runs deterministic when older action tasks are still running.
     *
     * @param nameContains optional case-insensitive substring to scope cleanup
     * @return number of removed containers
     */
    public int cleanupActionsTaskContainers(String nameContains) {
        String normalizedFilter = nameContains == null ? "" : nameContains.trim().toLowerCase(Locale.ROOT);
        int removed = 0;

        try {
            var dockerClient = DockerClientFactory.instance().client();
            for (Container dockerContainer : dockerClient.listContainersCmd().withShowAll(true).exec()) {
                String[] names = dockerContainer.getNames();
                if (names == null || names.length == 0) {
                    continue;
                }

                String joinedNames = String.join(",", names).toLowerCase(Locale.ROOT);
                if (!joinedNames.contains("gitea-actions-task-")) {
                    continue;
                }
                if (!normalizedFilter.isBlank() && !joinedNames.contains(normalizedFilter)) {
                    continue;
                }

                try {
                    dockerClient.removeContainerCmd(dockerContainer.getId()).withForce(true).exec();
                    removed++;
                    logger.info("Removed stale Actions task container id={} names={}",
                            dockerContainer.getId(),
                            Arrays.toString(names));
                } catch (Exception removeException) {
                    logger.warn("Failed to remove stale Actions task container id={} names={}: {}",
                            dockerContainer.getId(),
                            Arrays.toString(names),
                            removeException.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to cleanup stale Actions task containers: {}", e.getMessage());
        }

        if (removed > 0) {
            logger.info("Removed {} stale Actions task container(s)", removed);
        }
        return removed;
    }

    /**
     * Stop the Gitea container
     */
    public void stop() {
        if (runner != null) {
            try {
                if (runner.isRunning()) {
                    logger.info("Stopping Gitea Actions runner...");
                }
                runner.stop();
            } catch (Exception e) {
                logger.warn("Failed to stop Gitea Actions runner cleanly", e);
            }
        }

        if (container != null && container.isRunning()) {
            logger.info("Stopping Gitea container...");
            container.stop();
        }
    }

    public String getWebUrl() {
        return "http://localhost:" + fixedHttpPort;
    }

    /**
     * Get the API URL for Gitea API calls
     */
    public String getApiUrl() {
        return getWebUrl() + "/api/v1";
    }

    /**
     * Get the admin access token
     */
    public String getAdminToken() {
        return adminToken;
    }

    /**
     * Get the registration token generated for the Actions runner. May be {@code null} if Actions were disabled.
     */
    public String getRunnerRegistrationToken() {
        return runnerRegistrationToken;
    }

    /**
     * Get the admin username
     */
    public String getAdminUsername() {
        return adminUsername;
    }

    /**
     * Get the admin password
     */
    public String getAdminPassword() {
        return adminPassword;
    }

    /**
     * Ensure Actions are enabled for a repository. Requires Gitea 1.21+.
     */
    public void enableRepositoryActions(String repoOwner, String repoName) {
        actionsSupport.enableRepositoryActions(repoOwner, repoName);
    }

    /**
     * Logs actionable diagnostics for repository Actions setup and runs.
     */
    public void logRepositoryActionsDiagnostics(String repoOwner, String repoName) {
        actionsSupport.logRepositoryActionsDiagnostics(repoOwner, repoName);
    }

    /**
     * Wait until at least one Actions run exists for the repository.
     */
    public void waitForRepositoryActionsRun(String repoOwner, String repoName, Duration timeout, Duration pollInterval) {
        actionsSupport.waitForActionsRun(repoOwner, repoName, timeout, pollInterval);
    }

    /**
     * Best-effort reset of repository-specific Actions state (runs + stale task containers).
     */
    public void resetRepositoryActionsState(String repoOwner, String repoName) {
        int clearedRuns = clearRepositoryActionsRuns(repoOwner, repoName);
        int removedTasks = cleanupActionsTaskContainers(null);
        logger.info("Reset Actions state for {}/{}: clearedRuns={}, removedTaskContainers={} ",
                repoOwner, repoName, clearedRuns, removedTasks);
    }

    /**
     * Logs runner/container diagnostics useful for failed Actions runs.
     */
    public void logActionsRunnerDiagnostics(String reason) {
        logRunnerDiagnostics(reason);
    }

    /**
     * Clear persisted Actions runs for the repository.
     * <p>
     * Useful to keep acceptance runs deterministic by ensuring only freshly-triggered
     * runs are visible in the UI/API.
     */
    public int clearRepositoryActionsRuns(String repoOwner, String repoName) {
        return actionsSupport.clearActionsRuns(repoOwner, repoName);
    }

    public WorkflowTaskResult waitForWorkflowTaskResult(String repoOwner,
                                                        String repoName,
                                                        String workflowFile,
                                                        Duration timeout,
                                                        Duration pollInterval) {
        try {
            var result = actionsSupport.waitForWorkflowTaskResult(repoOwner, repoName, workflowFile, timeout, pollInterval);
            return new WorkflowTaskResult(
                    result.id(),
                    result.state(),
                    result.success(),
                    result.workflow(),
                    result.job(),
                    result.ref(),
                    result.sha(),
                    result.summary());
        } catch (RuntimeException e) {
            logRunnerDiagnostics("workflow task wait failed for '" + workflowFile + "': " + e.getMessage());
            throw e;
        }
    }

    public record WorkflowTaskResult(long id,
                                     String state,
                                     boolean success,
                                     String workflow,
                                     String job,
                                     String ref,
                                     String sha,
                                     String summary) {
    }

    /**
     * Create a repository via the Gitea API
     */
    public void createRepository(String repoName) {
        createRepository(repoName, "Test repository created by GiteaContainer");
    }

    /**
     * Create a repository with description via the Gitea API
     */
    public void createRepository(String repoName, String description) {
        try {
            String requestBody = JSON.createObjectNode()
                    .put("name", repoName)
                    .put("description", description)
                    .put("private", false)
                    .toString();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getApiUrl() + "/user/repos"))
                    .header("Authorization", "token " + adminToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201) {
                logger.info("Created repository: {}", repoName);
            } else {
                logger.warn("Failed to create repository {}: {} - {}", repoName, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            logger.error("Error creating repository: " + repoName, e);
        }
    }

    /**
     * Check if a repository exists
     */
    public boolean repositoryExists(String repoName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getApiUrl() + "/repos/" + adminUsername + "/" + repoName))
                    .header("Authorization", "token " + adminToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            logger.error("Error checking repository existence: " + repoName, e);
            return false;
        }
    }

    private void createAdminUser() {
        try {
            logger.info("Creating admin user: {}", adminUsername);
            container.execInContainer("gitea", "admin", "user", "create",
                    "--username", adminUsername,
                    "--password", adminPassword,
                    "--email", adminEmail,
                    "--admin");
            logger.info("Admin user created successfully");
        } catch (Exception e) {
            // User might already exist, which is fine
            logger.debug("Admin user creation result: {}", e.getMessage());
        }
    }

    private void generateAccessToken() {
        try {
            logger.info("Generating access token for admin user");
            var result = container.execInContainer("gitea", "admin", "user", "generate-access-token",
                    "--username", adminUsername,
                    "--scopes", "write:repository,read:user,write:user",
                    "--token-name", "test-token",
                    "--raw");

            this.adminToken = result.getStdout().trim();
            logger.info("Access token generated successfully");
        } catch (Exception e) {
            logger.error("Failed to generate access token", e);
            throw new RuntimeException("Could not generate Gitea access token", e);
        }
    }

    /**
     * Build a clone URL that is reachable from within the Actions runner network.
     * The runner communicates with the Gitea container via the {@code gitea-actions}
     * network alias, so this helper produces a URL that workflows can use when
     * pushing artifacts back to the repository.
     */
    public String buildRunnerAccessibleCloneUrl(String owner, String repositoryName) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalArgumentException("repositoryName must not be blank");
        }
        return "http://gitea-actions:" + GITEA_PORT + "/" + owner + "/" + repositoryName + ".git";
    }

    private void waitForRunnerRegistration() {
        try {
            Awaitility.await("actions runner to appear")
                    .pollInterval(Duration.ofSeconds(2))
                    .atMost(Duration.ofMinutes(1))
                    .ignoreExceptions()
                    .until(() -> isRunnerRegisteredViaRest(RUNNER_NAME) || isRunnerRegisteredViaDatabase(RUNNER_NAME));
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            logger.warn("Actions runner '{}' was not detected after waiting. Workflows may remain queued.", RUNNER_NAME);
            logRunnerDiagnostics("runner registration timeout");
        }
    }

    private boolean isRunnerRegisteredViaRest(String runnerName) {
        String[] endpoints = {
                getApiUrl() + "/actions/runners",
                getApiUrl() + "/admin/runners",
                getApiUrl() + "/admin/actions/runners"
        };

        for (String endpoint : endpoints) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Authorization", "token " + adminToken)
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200 && response.body().contains(runnerName)) {
                    logger.info("Actions runner '{}' registered successfully via {}", runnerName, endpoint);
                    return true;
                }

                if (response.statusCode() != 200) {
                    logger.debug("Runner poll via {} returned {} body: {}", endpoint, response.statusCode(), response.body());
                }
            } catch (Exception e) {
                logger.debug("Runner poll via {} failed", endpoint, e);
            }
        }
        return false;
    }

    private boolean isRunnerRegisteredViaDatabase(String runnerName) {
        try {
            var result = container.execInContainer("sqlite3", "/var/lib/gitea/data/gitea.db",
                    "SELECT COUNT(1) FROM action_runner WHERE name='" + runnerName + "' AND online=1;");
            String output = result.getStdout().trim();
            if (!output.isBlank() && !"0".equals(output)) {
                logger.info("Actions runner '{}' reported online via sqlite check", runnerName);
                return true;
            }
            if (!result.getStderr().isBlank()) {
                logger.debug("Runner sqlite check stderr: {}", result.getStderr().trim());
            }
        } catch (Exception e) {
            logger.debug("Runner sqlite check failed", e);
        }
        return false;
    }

    private void waitForGiteaReady() {
        String versionEndpoint = "http://localhost:" + container.getMappedPort(GITEA_PORT) + "/api/v1/version";

        try {
            Awaitility.await("gitea api ready")
                    .pollInterval(Duration.ofSeconds(2))
                    .atMost(Duration.ofMinutes(2))
                    .ignoreExceptions()
                    .until(() -> {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(versionEndpoint))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build();

                        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                        return response.statusCode() == 200;
                    });
            logger.info("Gitea API is ready");
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            logger.warn("Timed out waiting for Gitea API readiness. Subsequent operations may fail.");
        } catch (Exception e) {
            logger.warn("Gitea readiness check failed", e);
        }
    }

    private void writeRunnerConfig(String runnerName) {
        String config = """
                log:
                  level: info

                runner:
                  name: %s
                  file: /data/.runner
                  capacity: 3
                  insecure: true
                  fetch_timeout: 5s
                  fetch_interval: 2s
                  timeout: 3h
                  labels:
                    - "self-hosted"
                    - "ubuntu-latest:%s"
                    - "ubuntu-22.04:%s"

                cache:
                  enabled: false

                container:
                  network: bridge
                  valid_volumes:
                    - "**"
                  docker_host: unix:///var/run/docker.sock
                  options: "--user 0:0 --add-host host.testcontainers.internal:host-gateway --add-host host.docker.internal:host-gateway --add-host localhost.localtest.me:host-gateway"
                  force_pull: true
                  require_docker: true

                host:
                  workdir_parent: /data/_work
                """.formatted(runnerName, DOCKER_IMAGE_LABEL, DOCKER_IMAGE_LABEL);

        try {
            Files.writeString(runnerConfigFile, config, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write Actions runner configuration", e);
        }
    }
}
