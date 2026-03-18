package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@WithGitea(createTestRepos = true, testRepoNames = {"standalone-repo"})
class GiteaStandaloneIntegrationTest {

    @Test
    @DisplayName("Gitea should start without Actions runner and expose explicit checkout overrides")
    void shouldStartGiteaAndExposeApi(@Gitea GiteaContainer gitea,
                                      @GiteaUrl String webUrl,
                                      @GiteaUrl(api = true) URI apiUrl) {
        assertThat(gitea).isNotNull();
        assertThat(webUrl).isEqualTo(gitea.getWebUrl());
        assertThat(apiUrl).isEqualTo(URI.create(gitea.getApiUrl()));

        assertThat(gitea.getAdminToken()).isNotBlank();
        assertThat(gitea.repositoryExists("standalone-repo")).isTrue();

        Map<String, String> overrides = gitea.workflowCheckoutOverrides(gitea.getAdminUsername(), "standalone-repo");
        assertThat(overrides)
                .containsEntry(GiteaEnvironmentProperties.REPO_REMOTE_URL,
                        "http://localhost.localtest.me:" + URI.create(gitea.getWebUrl()).getPort() + "/"
                                + gitea.getAdminUsername() + "/standalone-repo.git")
                .containsEntry(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME, gitea.getAdminUsername())
                .containsEntry(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN, gitea.getAdminToken());
    }
}
