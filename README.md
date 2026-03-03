# PromptLM Gitea + Artifactory Test Support
![Maven Central](https://img.shields.io/maven-central/v/dev.promptlm.test/junit-gitea-artifactory)
![CI](https://img.shields.io/github/actions/workflow/status/promptLM/promptlm-junit-gitea-artifactory/ci.yml?branch=main)
![License](https://img.shields.io/github/license/promptLM/promptlm-junit-gitea-artifactory)

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

## License

MIT. See `LICENSE`.
