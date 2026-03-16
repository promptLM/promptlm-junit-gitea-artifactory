package dev.promptlm.testutils.artifactory;

/**
 * Exception thrown when the Artifactory Testcontainers harness fails to initialize
 * required resources such as repositories, users, or permissions.
 */
public class ArtifactoryInitializationException extends RuntimeException {

    /**
     * Create an initialization exception with a message.
     *
     * @param message failure description
     */
    public ArtifactoryInitializationException(String message) {
        super(message);
    }

    /**
     * Create an initialization exception with a message and cause.
     *
     * @param message failure description
     * @param cause root cause
     */
    public ArtifactoryInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
