package dev.promptlm.testutils.gitea;

import dev.promptlm.testutils.artifactory.ArtifactoryContainer;
import dev.promptlm.testutils.artifactory.WithArtifactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@WithGitea(actionsEnabled = true, createTestRepos = true, testRepoNames = {"integration-repo"})
@WithArtifactory
class GiteaWithArtifactoryAnnotationIntegrationTest {

    @Test
    @DisplayName("Annotations should start Gitea (with Actions runner) and Artifactory")
    void shouldStartGiteaAndArtifactory(GiteaContainer gitea, ArtifactoryContainer artifactory) {
        assertThat(gitea.getWebUrl()).isNotBlank();
        assertThat(gitea.getApiUrl()).isNotBlank();
        assertThat(gitea.getRunnerRegistrationToken()).isNotBlank();

        assertThat(artifactory.getApiUrl()).isNotBlank();
        assertThat(System.getProperty("artifactory.url")).isEqualTo(artifactory.getApiUrl());
    }

    @Test
    @DisplayName("Artifactory should configure the standard Actions variable contract in Gitea")
    void shouldConfigureRepositoryActionsVariables(GiteaContainer gitea, ArtifactoryContainer artifactory) {
        String repoOwner = gitea.getAdminUsername();
        String repoName = "manual-artifactory-vars-repo";

        gitea.createRepository(repoName);
        gitea.waitForRepository(repoName);
        artifactory.configureRepositoryActionsVariables(gitea, repoOwner, repoName);

        assertThat(gitea.readRepositoryActionsVariable(repoOwner, repoName,
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_URL))
                .isEqualTo(artifactory.getRunnerAccessibleApiUrl());
        assertThat(gitea.readRepositoryActionsVariable(repoOwner, repoName,
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_REPOSITORY))
                .isEqualTo(artifactory.getMavenRepositoryName());
        assertThat(gitea.readRepositoryActionsVariable(repoOwner, repoName,
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_USERNAME))
                .isEqualTo(artifactory.getDeployerUsername());
        assertThat(gitea.readRepositoryActionsVariable(repoOwner, repoName,
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_PASSWORD))
                .isEqualTo(artifactory.getDeployerPassword());
    }

    @Test
    @DisplayName("Enabling repository Actions should provision default Artifactory workflow variables")
    void shouldProvisionDefaultArtifactoryVariablesWhenEnablingActions(GiteaContainer gitea,
                                                                       ArtifactoryContainer artifactory) {
        String repoOwner = gitea.getAdminUsername();
        String repoName = "default-artifactory-vars-repo";

        gitea.createRepository(repoName);
        gitea.waitForRepository(repoName);
        gitea.enableRepositoryActions(repoOwner, repoName);

        assertThat(gitea.readRepositoryActionsVariable(repoOwner, repoName,
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_URL))
                .isEqualTo(artifactory.getRunnerAccessibleApiUrl());
        assertThat(gitea.readRepositoryActionsVariable(repoOwner, repoName,
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_REPOSITORY))
                .isEqualTo(artifactory.getMavenRepositoryName());
        assertThat(gitea.readRepositoryActionsVariable(repoOwner, repoName,
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_USERNAME))
                .isEqualTo(artifactory.getDeployerUsername());
        assertThat(gitea.readRepositoryActionsVariable(repoOwner, repoName,
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_PASSWORD))
                .isEqualTo(artifactory.getDeployerPassword());
    }

    @Test
    @DisplayName("Explicit Artifactory workflow variable overrides should win over defaults")
    void explicitOverridesShouldWinOverDefaultProvisioning(GiteaContainer gitea) {
        String repoOwner = gitea.getAdminUsername();
        String repoName = "override-artifactory-vars-repo";

        gitea.createRepository(repoName);
        gitea.waitForRepository(repoName);
        gitea.enableRepositoryActions(repoOwner, repoName);
        gitea.ensureRepositoryActionsVariable(repoOwner, repoName,
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_REPOSITORY, "custom-release-local");
        gitea.enableRepositoryActions(repoOwner, repoName);

        assertThat(gitea.readRepositoryActionsVariable(repoOwner, repoName,
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_REPOSITORY))
                .isEqualTo("custom-release-local");
    }
}
