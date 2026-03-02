package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GiteaEnvironmentPropertiesTest {

    @AfterEach
    void cleanup() {
        System.clearProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL);
        System.clearProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME);
        System.clearProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN);
    }

    @Test
    void snapshotApplyAndRestoreRoundTrip() {
        System.setProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL, "http://prev/api");
        System.setProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME, "prev-user");
        System.setProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN, "prev-token");

        Map<String, String> previous = GiteaEnvironmentProperties.snapshot();

        GiteaContainer container = mock(GiteaContainer.class);
        when(container.getApiUrl()).thenReturn("http://new/api");
        when(container.getAdminUsername()).thenReturn("new-user");
        when(container.getAdminToken()).thenReturn("new-token");
        GiteaEnvironmentProperties.applyFrom(container);

        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL)).isEqualTo("http://new/api");
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME)).isEqualTo("new-user");
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN)).isEqualTo("new-token");

        GiteaEnvironmentProperties.restore(previous);
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL)).isEqualTo("http://prev/api");
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME)).isEqualTo("prev-user");
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN)).isEqualTo("prev-token");
    }

    @Test
    void restoreClearsPropertiesWhenNoPreviousValuesExist() {
        System.setProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL, "temp");
        System.setProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME, "temp");
        System.setProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN, "temp");

        GiteaEnvironmentProperties.restore(Map.of());

        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL)).isNull();
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME)).isNull();
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN)).isNull();
    }
}
