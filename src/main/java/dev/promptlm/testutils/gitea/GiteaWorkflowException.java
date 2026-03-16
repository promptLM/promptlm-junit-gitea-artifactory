package dev.promptlm.testutils.gitea;

/**
 * Exception used to signal workflow-specific failures in the Gitea test harness.
 */
public class GiteaWorkflowException extends RuntimeException {

    /**
     * Structured diagnostics captured for the failed workflow, if available.
     */
    private final GiteaActionsDiagnostics diagnostics;

    /**
     * Create a workflow exception with a message.
     *
     * @param message failure description
     */
    public GiteaWorkflowException(String message) {
        super(message);
        this.diagnostics = null;
    }

    /**
     * Create a workflow exception with a message and cause.
     *
     * @param message failure description
     * @param cause root cause
     */
    public GiteaWorkflowException(String message, Throwable cause) {
        super(message, cause);
        this.diagnostics = null;
    }

    /**
     * Create a workflow exception with a message, cause, and diagnostics.
     *
     * @param message failure description
     * @param cause root cause
     * @param diagnostics structured diagnostics captured during the failure
     */
    public GiteaWorkflowException(String message, Throwable cause, GiteaActionsDiagnostics diagnostics) {
        super(message, cause);
        this.diagnostics = diagnostics;
    }

    /**
     * Get structured diagnostics associated with this workflow failure.
     *
     * @return diagnostics snapshot or {@code null}
     */
    public GiteaActionsDiagnostics getDiagnostics() {
        return diagnostics;
    }
}
