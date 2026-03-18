package dev.promptlm.testutils.gitea;

/**
 * Optional repository Actions variable names for workflow checkout overrides.
 *
 * <p>These are intentionally not published as global defaults by the harness.
 * Downstream workflows should use platform-provided checkout values by default
 * and opt in to these variables only for nonstandard runner/network topologies.
 */
final class GiteaEnvironmentProperties {

    static final String REPO_REMOTE_URL = "REPO_REMOTE_URL";
    static final String REPO_REMOTE_USERNAME = "REPO_REMOTE_USERNAME";
    static final String REPO_REMOTE_TOKEN = "REPO_REMOTE_TOKEN";

    private GiteaEnvironmentProperties() {
        // Utility class
    }
}
