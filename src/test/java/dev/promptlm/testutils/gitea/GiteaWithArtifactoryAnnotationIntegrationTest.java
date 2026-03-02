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
}
