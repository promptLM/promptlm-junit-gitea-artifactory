# PromptLM Gitea + Artifactory Test Support
![Release](https://img.shields.io/github/v/release/promptLM/promptlm-junit-gitea-artifactory?sort=semver)

JUnit 5 test support for spinning up Gitea and Artifactory via Testcontainers. Provides annotations and container helpers to manage containers and common setup for integration tests.

## Requirements

- Java 17+
- Docker (Testcontainers will start Gitea and Artifactory)

## Dependency

```xml
<dependency>
  <groupId>dev.promptlm.test</groupId>
  <artifactId>gitea-artifactory</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

Use the latest release shown in the badge above.

## Quickstart

Use the JUnit 5 extensions to provision containers for your tests.

Gitea (class-level annotation recommended):

```java
import dev.promptlm.testutils.gitea.GiteaContainer;
import dev.promptlm.testutils.gitea.WithGitea;
import org.junit.jupiter.api.Test;

@WithGitea(createTestRepos = true, testRepoNames = {"demo-repo"})
class GiteaIntegrationTest {

    @Test
    void usesGitea(GiteaContainer gitea) {
        gitea.createRepository("extra-repo");
    }
}
```

Note: `@WithGitea` is supported on test classes only (method-level use is not supported).
Enable the Actions runner when you need workflow support:

```java
@WithGitea(actionsEnabled = true)
class GiteaActionsTest {
}
```

Field/parameter injection options:

```java
import dev.promptlm.testutils.gitea.Gitea;
import dev.promptlm.testutils.gitea.GiteaUrl;

@WithGitea
class GiteaInjectedTest {

    @Gitea
    private GiteaContainer gitea;

    @GiteaUrl
    private String webUrl;

    @GiteaUrl(api = true)
    private URI apiUrl;
}
```

Artifactory:

```java
import dev.promptlm.testutils.artifactory.ArtifactoryContainer;
import dev.promptlm.testutils.artifactory.WithArtifactory;
import org.junit.jupiter.api.Test;

@WithArtifactory
class ArtifactoryIntegrationTest {

    @Test
    void usesArtifactory(ArtifactoryContainer artifactory) {
        String repoUrl = artifactory.getMavenRepositoryUrl();
    }
}
```

Field injection is also supported via `@Artifactory`.

## Annotations And Configuration

`@WithGitea` starts a Gitea container per test class and sets system properties like `REPO_REMOTE_URL`, `REPO_REMOTE_USERNAME`, and `REPO_REMOTE_TOKEN` for convenience.

`@WithGitea` attributes:
- `adminUsername`: Admin user name to create in Gitea. Default `testuser`.
- `username`: Alias for `adminUsername`. If set, it takes precedence.
- `adminPassword`: Admin password to create in Gitea. Default `testpass123`.
- `password`: Alias for `adminPassword`. If set, it takes precedence.
- `adminEmail`: Admin email for the user. Default `test@example.com`.
- `createTestRepos`: If `true`, test repositories are created at startup.
- `testRepoNames`: Repository names to create when `createTestRepos` is `true`.
- `actionsEnabled`: If `true`, starts a dedicated Actions runner and enables Actions support.

`@WithArtifactory` starts a shared Artifactory container (one per test suite) and sets Artifactory system properties like `artifactory.url` and `artifactory.maven.repository.url`.

`@WithArtifactory` attributes:
- `adminUsername`: Admin username for Artifactory. Default `admin`.
- `adminPassword`: Admin password. Default `password`.
- `deployerUsername`: CI deployer username. Default `ci-deployer`.
- `deployerPassword`: CI deployer password. Default `ci-deployer-password`.
- `deployerEmail`: CI deployer email. Default `ci-deployer@example.com`.
- `mavenRepository`: Local Maven repo name. Default `ci-maven-local`.
- `logContainer`: If `true`, streams Artifactory logs to SLF4J during tests.

`@GiteaUrl(api = true)` injects the API URL instead of the web URL. Supported target types are `String` and `URI`.
`@Gitea` and `@Artifactory` inject the corresponding container into fields or parameters.

## Using The Container API

Common Gitea container operations:
- `getWebUrl()` and `getApiUrl()` to build HTTP calls to Gitea.
- `getAdminToken()` for authenticated API calls.
- `createRepository(...)` and `repositoryExists(...)` for repo setup.
- `ensureRepositoryActionsVariable(...)` and `waitForRepositoryActionsRun(...)` for Actions workflows.

Common Artifactory container operations:
- `getMavenRepositoryUrl()` to configure deploys.
- `getAdminUsername()`/`getAdminPassword()` or `getDeployerUsername()`/`getDeployerPassword()` for credentials.
- `getInternalApiUrl()` and `getRunnerAccessibleApiUrl()` for runner or container-to-container calls.
- `repositoryExists(...)` to verify repo provisioning.

Example API usage:

```java
@WithGitea(createTestRepos = true, testRepoNames = {"demo"}, actionsEnabled = true)
class ApiUsageTest {

    @Test
    void usesApi(GiteaContainer gitea) {
        String apiUrl = gitea.getApiUrl();
        String token = gitea.getAdminToken();
        gitea.ensureRepositoryActionsVariable("testuser", "demo", "CI", "true");
    }
}
```

## Public API

- Annotations: `@WithGitea`, `@Gitea`, `@GiteaUrl`, `@WithArtifactory`, `@Artifactory`
- Parameter types: `GiteaContainer`, `ArtifactoryContainer`
- Exceptions: `GiteaHarnessException`, `GiteaWorkflowException`, `ArtifactoryInitializationException`

Everything else is internal and not intended for direct use.

## Integration Scenarios (Covered By Tests)

1. Gitea alone (no Actions runner)
2. Gitea + Actions runner
3. Artifactory alone
4. Gitea + Actions runner + Artifactory

## System Properties

The extensions populate these properties for convenience:

- `REPO_REMOTE_URL`
- `REPO_REMOTE_USERNAME`
- `REPO_REMOTE_TOKEN`
- `artifactory.url`
- `artifactory.maven.repository.url`
- `artifactory.admin.username`
- `artifactory.admin.password`
- `artifactory.deployer.username`
- `artifactory.deployer.password`
- `artifactory.maven.repository.name`
- `artifactory.internal.api.url`
- `artifactory.runner.api.url`

## Notes

- Containers require Docker and can take a few minutes to start on first run.
- The extensions set system properties like `REPO_REMOTE_URL` and `artifactory.url` for convenience.
- Container images can be overridden via system properties or environment variables:
  `gitea.image`/`GITEA_IMAGE`, `gitea.runner.image`/`GITEA_RUNNER_IMAGE`, `gitea.actions.job.image`/`GITEA_ACTIONS_JOB_IMAGE`.
- By default, the Actions runner image uses the upstream `latest` tag; override it if you need a specific version.
- To stream Artifactory logs during tests, set `@WithArtifactory(logContainer = true)`.

## CI/CD Workflows

- `ci.yml`: Runs `./mvnw test` on pushes and pull requests to `main`.
- `release.yml`: Runs on tag pushes (`v*`) or manual dispatch. Publishes artifacts, generates release notes from a milestone via `spring-io/github-changelog-generator`, and creates the GitHub release.

## Consuming From GitHub Packages

Add the repository to your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/promptLM/promptlm-junit-gitea-artifactory</url>
  </repository>
</repositories>
```

Provide credentials via `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
```

## Install (local)

```bash
mvn install
```

## Release (GitHub Actions + Changelog Generator)

Releases are automated via GitHub Actions and published to GitHub Packages when a release tag (`vX.Y.Z`) is created.
Release notes are generated from the GitHub milestone with the same version number.

Release checklist:

1. Create a milestone named after the version (e.g. `1.1.7`).
2. Assign PRs/issues to the milestone and apply labels for sections.
3. Create and push a tag: `git tag v1.1.7 && git push origin v1.1.7`.
4. `release.yml` publishes the package and creates the GitHub release with generated notes.

To rerun a release for an existing tag, use the manual workflow dispatch and provide the tag and milestone.

## Commit Message Conventions (Recommended)

Conventional Commits are recommended for clarity in history, but release notes are generated from milestones and labels.
Use one of the following prefixes:

- `feat:` for new features
- `fix:` for bug fixes
- `perf:` for performance improvements
- `refactor:` for refactoring
- `docs:` for documentation
- `test:` for tests
- `build:` for build changes
- `ci:` for CI changes
- `chore:` for maintenance

Example:

```
feat: add Artifactory container flags integration test
```

If you need breaking changes, use `feat!:` or include `BREAKING CHANGE:` in the footer.
Prefer squash merges so the Conventional Commit title is preserved in the commit history.

Package repository:
```
https://maven.pkg.github.com/promptLM/promptlm-junit-gitea-artifactory
```

## License

MIT. See `LICENSE`.
