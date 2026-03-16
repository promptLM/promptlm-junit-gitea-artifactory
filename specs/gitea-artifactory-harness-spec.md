# Gitea + Artifactory Test Harness – Agent Spec

## Purpose
This spec equips the agent that maintains the [`promptlm-junit-gitea-artifactory`](https://github.com/promptLM/promptlm-junit-gitea-artifactory) project with the context it needs to extend the library. The immediate goal is to add first-class APIs so downstream projects (e.g. `prompteval` acceptance tests) no longer need bespoke helpers such as `GiteaRepositoryHelper` to fetch raw repository files.

## Repository Overview
- **Artifact**: `dev.promptlm.test:junit-gitea-artifactory`
- **Language**: Java 17
- **Primary modules**:
  - `dev.promptlm.testutils.gitea` – Testcontainers-based Gitea harness (container lifecycle, Actions tooling, annotations)
  - `dev.promptlm.testutils.artifactory` – Testcontainers-based Artifactory harness
  - JUnit 5 extensions (`@WithGitea`, `@WithArtifactory`) plus field/parameter injection helpers (`@Gitea`, `@Artifactory`)

## Existing Gitea API Surface (non-exhaustive)
| Area | Key Types / Methods | Notes |
| --- | --- | --- |
| Container lifecycle | `GiteaContainer`, `WithGitea`, `GiteaTestExtension` | Spins up Gitea, provisions admin user/token, optional Actions runner. Provides accessors such as `getWebUrl()`, `getApiUrl()`, `getAdminUsername()`, `getAdminToken()`, `buildRunnerAccessibleCloneUrl(...)`, `waitForRepository(...)`. |
| Actions support | `GiteaActionsSupport` (internal), `GiteaActions` facade | Methods like `ensureRepositoryActionsVariable`, `waitForWorkflowRunBySha`, diagnostics collection. |
| Configuration helpers | `GiteaEnvironmentProperties`, `GiteaConfigManager` | Map container state into system properties for downstream services. |

## Existing Artifactory API Surface (non-exhaustive)
| Area | Key Types / Methods | Notes |
| --- | --- | --- |
| Container lifecycle | `ArtifactoryContainer`, `WithArtifactory`, `ArtifactoryTestExtension` | Provision Artifactory OSS, set up deployer credentials, ensure repository, expose URLs (`getApiUrl()`, `getMavenRepositoryUrl()`, etc.). |
| Maven deploy smoke test utilities | `ArtifactoryMavenDeploySmokeTest` | Demonstrates storage polling helpers similar to `ArtifactoryStorageHelper`. |

## Gap to Address
`prompteval` acceptance tests still ship `GiteaRepositoryHelper.fetchRawFile(...)` to poll prompt specs from a repository branch. The harness should own this functionality so downstream projects can delete their ad-hoc HTTP utilities.

### Desired API Additions (Gitea)
1. **Raw file download helper**
   - Method on `GiteaContainer` (and/or dedicated helper) to fetch raw file contents via the Gitea HTTP API.
   - Suggested signature(s):
     ```java
     Optional<String> fetchRawFile(String owner, String repo, String branch, String relativePath);
     Optional<byte[]> fetchRawFileBytes(String owner, String repo, String branch, String relativePath);
     ```
   - Should internally construct the canonical raw URL (`{webUrl}/{owner}/{repo}/raw/branch/{branch}/{relativePath}`) and reuse the container’s configured `HttpClient` plus admin token for authentication.
   - Handle 200 → body, 404 → `Optional.empty()`, other status codes → throw informative exception with response snippet.
   - Reuse existing logging conventions (`logger.debug/info`) for visibility.

2. **Convenience polling utility** (optional but helpful)
   - Provide `waitForRawFile(...)` that repeatedly calls `fetchRawFile` until content is present or timeout elapses (mirroring `waitForRepository`).
   - Parameters: branch, relative path, timeout, poll interval.
   - This abstracts the polling loop currently embedded in `HappyPathUserJourneyTest`.

### Acceptance Criteria for the Agent
- API surface is fully unit-tested (e.g., using Testcontainers against the harness itself or WireMock to simulate Gitea raw responses).
- JavaDoc documents authentication and error semantics.
- README section updated to mention the new capability and show a short snippet.
- Version bumped (per the repo’s release workflow) if required by maintainers.

## Downstream Migration Plan (for later)
Once the new API exists and is published:
1. Update `prompteval` to depend on the new harness version.
2. Replace `GiteaRepositoryHelper.fetchRawFile(...)` usages with `gitea.fetchRawFile(...)` (or equivalent) inside acceptance tests.
3. Delete `acceptance-tests/src/test/java/dev/promptlm/test/support/GiteaRepositoryHelper.java`.

The agent working on the harness should focus on implementing the API additions above; the migration and cleanup will happen afterward once the user confirms the new release is available.
