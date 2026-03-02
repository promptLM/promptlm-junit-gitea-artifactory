package dev.promptlm.testutils.gitea;

/**
 * Unchecked exception used by the Gitea test harness to signal failures.
 */
public class GiteaHarnessException extends RuntimeException {

    public GiteaHarnessException(String message) {
        super(message);
    }

    public GiteaHarnessException(String message, Throwable cause) {
        super(message, cause);
    }
}
