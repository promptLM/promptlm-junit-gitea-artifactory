package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GiteaRunnerNodeIntegrationTest {

    @Test
    @DisplayName("Actions runner installs Node.js and cleanup stops containers")
    void installsNodeAndStopsRunner() {
        GiteaContainer gitea = new GiteaContainer().withActionsEnabled(true);
        try {
            try {
                gitea.start();
            } catch (IllegalStateException ex) {
                Assumptions.assumeTrue(false, "Docker not available for Gitea container: " + ex.getMessage());
            }

            var nodeVersion = gitea.execInRunner("sh", "-lc", "node --version");
            assertThat(nodeVersion.getExitCode()).isZero();
            assertThat(nodeVersion.getStdout()).contains("v");
        } finally {
            gitea.stop();
        }

        assertThat(gitea.isRunnerRunning()).isFalse();
        assertThat(gitea.isContainerRunning()).isFalse();
    }
}
