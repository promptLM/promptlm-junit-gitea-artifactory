package dev.promptlm.testutils.artifactory;

import dev.promptlm.testutils.gitea.GiteaContainer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ArtifactoryContainerContractTest {

    @Test
    void standardActionsVariablesExposeDocumentedWorkflowContract() {
        ArtifactoryContainer artifactory = contractContainer();

        Map<String, String> variables = artifactory.standardActionsVariables();

        assertThat(variables)
                .containsEntry(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_URL,
                        "http://host.testcontainers.internal:8081/artifactory")
                .containsEntry(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_REPOSITORY, "ci-maven-local")
                .containsEntry(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_USERNAME, "ci-deployer")
                .containsEntry(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_PASSWORD, "ci-deployer-password");

        variables.put(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_URL, "http://override.test/artifactory");
        assertThat(variables.get(ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_URL))
                .isEqualTo("http://override.test/artifactory");
    }

    @Test
    void configureRepositoryActionsVariablesAppliesStandardContractToGitea() {
        ArtifactoryContainer artifactory = contractContainer();
        GiteaContainer gitea = mock(GiteaContainer.class);

        artifactory.configureRepositoryActionsVariables(gitea, "owner", "repo");

        verify(gitea).ensureRepositoryActionsVariable("owner", "repo",
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_URL,
                "http://host.testcontainers.internal:8081/artifactory");
        verify(gitea).ensureRepositoryActionsVariable("owner", "repo",
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_REPOSITORY,
                "ci-maven-local");
        verify(gitea).ensureRepositoryActionsVariable("owner", "repo",
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_USERNAME,
                "ci-deployer");
        verify(gitea).ensureRepositoryActionsVariable("owner", "repo",
                ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_PASSWORD,
                "ci-deployer-password");
    }

    private ArtifactoryContainer contractContainer() {
        ArtifactoryContainer artifactory = mock(ArtifactoryContainer.class, CALLS_REAL_METHODS);
        doReturn("http://host.testcontainers.internal:8081/artifactory")
                .when(artifactory).getRunnerAccessibleApiUrl();
        doReturn("ci-maven-local").when(artifactory).getMavenRepositoryName();
        doReturn("ci-deployer").when(artifactory).getDeployerUsername();
        doReturn("ci-deployer-password").when(artifactory).getDeployerPassword();
        return artifactory;
    }
}
