package dev.promptlm.testutils.artifactory;

/**
 * Exception thrown when the Artifactory Testcontainers harness fails to initialize
 * required resources such as repositories, users, or permissions.
 */
public class ArtifactoryInitializationException extends RuntimeException {

    public ArtifactoryInitializationException(String message) {
        super(message);
    }

    public ArtifactoryInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
