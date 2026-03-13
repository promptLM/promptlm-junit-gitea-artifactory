package dev.promptlm.testutils.gitea;

/**
 * Exception used to signal workflow-specific failures in the Gitea test harness.
 */
public class GiteaWorkflowException extends RuntimeException {

    private final GiteaActionsDiagnostics diagnostics;

    public GiteaWorkflowException(String message) {
        super(message);
        this.diagnostics = null;
    }

    public GiteaWorkflowException(String message, Throwable cause) {
        super(message, cause);
        this.diagnostics = null;
    }

    public GiteaWorkflowException(String message, Throwable cause, GiteaActionsDiagnostics diagnostics) {
        super(message, cause);
        this.diagnostics = diagnostics;
    }

    public GiteaActionsDiagnostics getDiagnostics() {
        return diagnostics;
    }
}
