# PromptLM Gitea + Artifactory Test Support
![Maven Central](https://img.shields.io/maven-central/v/dev.promptlm.test/junit-gitea-artifactory)
![CI](https://img.shields.io/github/actions/workflow/status/promptLM/promptlm-junit-gitea-artifactory/ci.yml?branch=main)
![License](https://img.shields.io/github/license/promptLM/promptlm-junit-gitea-artifactory)
![Static Badge](https://img.shields.io/badge/Windsurf_IDE-purple?style=flat&label=AI%20generated)
![Static Badge](https://img.shields.io/badge/%E2%9D%A4%EF%B8%8F-white?style=flat&label=Engineered%20with)





**JUnit 5 test support for spinning up Gitea and Artifactory via Testcontainers.**

PromptLM Gitea + Artifactory Test Support is a JUnit 5 library that provisions Gitea and Artifactory containers for integration tests using Testcontainers. It provides annotations and container helpers so your tests can boot real services with minimal setup. Ideal for CI‑friendly end‑to‑end workflows that need Git and Maven repository behavior.


## Setup

- Java 17+
- Docker (required by Testcontainers)

## Dependency

```xml
<dependency>
  <groupId>dev.promptlm.test</groupId>
  <artifactId>junit-gitea-artifactory</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

Use the latest release shown in the badge above.

## Usage

```java
import dev.promptlm.testutils.artifactory.ArtifactoryContainer;
import dev.promptlm.testutils.artifactory.WithArtifactory;
import dev.promptlm.testutils.gitea.GiteaContainer;
import dev.promptlm.testutils.gitea.WithGitea;
import org.junit.jupiter.api.Test;

@WithGitea(createTestRepos = true, testRepoNames = {"demo"})
@WithArtifactory
class IntegrationTest {

    @Test
    void usesServices(GiteaContainer gitea, ArtifactoryContainer artifactory) {
        String repoUrl = gitea.getWebUrl();
        String mavenRepo = artifactory.getMavenRepositoryUrl();
    }
}
```

## Artifactory workflow contract

When `@WithArtifactory` starts the harness, it publishes a default workflow-facing
contract for downstream Actions-based tests:

- `ARTIFACTORY_URL`
- `ARTIFACTORY_REPOSITORY`
- `ARTIFACTORY_USERNAME`
- `ARTIFACTORY_PASSWORD`

These values are available as system properties under the same names, and
`ArtifactoryContainer` exposes helpers to use them directly:

```java
Map<String, String> variables = artifactory.standardActionsVariables();
artifactory.configureRepositoryActionsVariables(gitea, gitea.getAdminUsername(), "demo");
```

`ARTIFACTORY_URL` defaults to the runner-accessible API URL so Gitea Actions job
containers can reach the harness. If you need custom values, start from the
default map, override the keys you want, and apply that map explicitly:

```java
Map<String, String> variables = artifactory.standardActionsVariables();
variables.put(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_URL, artifactory.getApiUrl());
artifactory.configureRepositoryActionsVariables(gitea, gitea.getAdminUsername(), "demo", variables);
```

The existing `artifactory.*` system properties remain available for host-side
test code that talks to Artifactory directly.

## Gitea Actions support

`GiteaContainer` exposes a lightweight Actions facade for inspecting workflow runs and logs:

```java
GiteaActions actions = gitea.actions();
var report = actions.waitForWorkflowRunBySha("owner", "repo", "commitSha",
        Duration.ofMinutes(2), Duration.ofSeconds(2));
```

When Actions workflows time out, the container can collect diagnostics (runs, jobs, logs)
to aid troubleshooting.

## Fetching repository files

Use the new raw-file helper to read workflow inputs or specs from a repository branch without
rolling your own HTTP client:

```java
Optional<String> spec = gitea.fetchRawFile("owner", "repo", "main", "prompts/my-spec.json");
String content = gitea.waitForRawFile("owner", "repo", "main", "prompts/my-spec.json",
        Duration.ofSeconds(30), Duration.ofSeconds(1));
```

## License

MIT. See `LICENSE`.
