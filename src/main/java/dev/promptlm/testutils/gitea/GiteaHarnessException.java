package dev.promptlm.testutils.gitea;

/**
 * Unchecked exception used by the Gitea test harness to signal failures.
 */
public class GiteaHarnessException extends RuntimeException {

    /**
     * Create a harness exception with a message.
     *
     * @param message failure description
     */
    public GiteaHarnessException(String message) {
        super(message);
    }

    /**
     * Create a harness exception with a message and cause.
     *
     * @param message failure description
     * @param cause root cause
     */
    public GiteaHarnessException(String message, Throwable cause) {
        super(message, cause);
    }
}
