package dev.promptlm.testutils.gitea;

/**
 * Exception used to signal workflow-specific failures in the Gitea test harness.
 */
public class GiteaWorkflowException extends RuntimeException {

    public GiteaWorkflowException(String message) {
        super(message);
    }

    public GiteaWorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
