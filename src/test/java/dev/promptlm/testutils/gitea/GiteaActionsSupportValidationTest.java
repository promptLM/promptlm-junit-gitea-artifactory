package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class GiteaActionsSupportValidationTest {

    private final GiteaActionsSupport support = new GiteaActionsSupport(
            mock(HttpClient.class),
            mock(Logger.class),
            () -> "http://localhost:3000/api/v1",
            () -> "token");

    @Test
    void rejectsBlankWorkflowFile() {
        assertThatThrownBy(() -> support.waitForWorkflowTaskResult(
                "owner",
                "repo",
                "   ",
                Duration.ofSeconds(1),
                Duration.ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflowFile");
    }

    @Test
    void rejectsBlankRepoOwnerWhenEnablingActions() {
        assertThatThrownBy(() -> support.enableRepositoryActions(" ", "repo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repoOwner");
    }

    @Test
    void rejectsBlankRepoNameWhenEnablingActions() {
        assertThatThrownBy(() -> support.enableRepositoryActions("owner", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repoName");
    }
}
