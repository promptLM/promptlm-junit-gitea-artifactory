package dev.promptlm.testutils.artifactory;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to enable Artifactory container for integration tests.
 * 
 * Usage:
 * <pre>
 * {@code
 * @WithArtifactory
 * class MyIntegrationTest {
 *     // Test methods can access Artifactory via injected container
 * }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ArtifactoryTestExtension.class)
public @interface WithArtifactory {
    
    /**
     * Admin username for Artifactory
     *
     * @return admin username
     */
    String adminUsername() default "admin";
    
    /**
     * Admin password for Artifactory
     *
     * @return admin password
     */
    String adminPassword() default "password";
    
    /**
     * CI deployer username
     *
     * @return deployer username
     */
    String deployerUsername() default "ci-deployer";
    
    /**
     * CI deployer password
     *
     * @return deployer password
     */
    String deployerPassword() default "ci-deployer-password";
    
    /**
     * CI deployer email
     *
     * @return deployer email
     */
    String deployerEmail() default "ci-deployer@example.com";
    
    /**
     * Maven repository name
     *
     * @return Maven repository key
     */
    String mavenRepository() default "ci-maven-local";

    /**
     * When true, stream container logs to SLF4J during tests.
     *
     * @return {@code true} to enable container log streaming
     */
    boolean logContainer() default false;
}
