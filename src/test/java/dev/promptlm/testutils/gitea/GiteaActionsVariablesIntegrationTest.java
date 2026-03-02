package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@WithGitea(createTestRepos = true, testRepoNames = {"env-repo"}, actionsEnabled = true)
class GiteaActionsVariablesIntegrationTest {

    @Test
    @DisplayName("Gitea repository should accept and return Actions variables")
    void shouldCreateAndUpdateActionsVariable(GiteaContainer gitea) throws Exception {
        String repoName = "env-repo";
        gitea.waitForRepository(repoName);

        assertThat(gitea.getRunnerRegistrationToken()).isNotBlank();

        String initialValue = "http://example.test/artifactory";
        gitea.ensureRepositoryActionsVariable(gitea.getAdminUsername(), repoName, "ARTIFACTORY_URL", initialValue);
        assertThat(gitea.readRepositoryActionsVariable(gitea.getAdminUsername(), repoName, "ARTIFACTORY_URL"))
                .isEqualTo(initialValue);

        String updatedValue = "http://example.test/artifactory-updated";
        gitea.ensureRepositoryActionsVariable(gitea.getAdminUsername(), repoName, "ARTIFACTORY_URL", updatedValue);
        assertThat(gitea.readRepositoryActionsVariable(gitea.getAdminUsername(), repoName, "ARTIFACTORY_URL"))
                .isEqualTo(updatedValue);
    }
}
