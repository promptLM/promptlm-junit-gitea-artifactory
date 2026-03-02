package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@WithGitea(createTestRepos = true, testRepoNames = {"standalone-repo"})
class GiteaStandaloneIntegrationTest {

    @Test
    @DisplayName("Gitea should start without Actions runner and expose injection + system properties")
    void shouldStartGiteaAndExposeApi(@Gitea GiteaContainer gitea,
                                      @GiteaUrl String webUrl,
                                      @GiteaUrl(api = true) URI apiUrl) {
        assertThat(gitea).isNotNull();
        assertThat(webUrl).isEqualTo(gitea.getWebUrl());
        assertThat(apiUrl).isEqualTo(URI.create(gitea.getApiUrl()));

        assertThat(gitea.getAdminToken()).isNotBlank();
        assertThat(gitea.repositoryExists("standalone-repo")).isTrue();

        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL)).isEqualTo(gitea.getApiUrl());
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME)).isEqualTo(gitea.getAdminUsername());
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN)).isEqualTo(gitea.getAdminToken());
    }
}
