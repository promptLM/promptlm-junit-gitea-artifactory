package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GiteaWorkflowCheckoutOverridesTest {

    @Test
    void workflowCheckoutOverridesExposeOptionalRepoRemoteVariables() {
        GiteaContainer gitea = new GiteaContainer()
                .withAdminUser("checkout-user", "checkout-pass", "checkout@example.com");

        Map<String, String> overrides = gitea.workflowCheckoutOverrides("owner", "demo");
        int port = URI.create(gitea.getWebUrl()).getPort();

        assertThat(overrides)
                .containsEntry(GiteaEnvironmentProperties.REPO_REMOTE_URL,
                        "http://localhost.localtest.me:" + port + "/owner/demo.git")
                .containsEntry(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME, "checkout-user")
                .containsKey(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN);
    }

    @Test
    void buildRunnerAccessibleCloneUrlRejectsBlankOwner() {
        GiteaContainer gitea = new GiteaContainer();

        assertThatThrownBy(() -> gitea.buildRunnerAccessibleCloneUrl(" ", "repo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");
    }

    @Test
    void buildRunnerAccessibleCloneUrlRejectsBlankRepositoryName() {
        GiteaContainer gitea = new GiteaContainer();

        assertThatThrownBy(() -> gitea.buildRunnerAccessibleCloneUrl("owner", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repositoryName");
    }
}
