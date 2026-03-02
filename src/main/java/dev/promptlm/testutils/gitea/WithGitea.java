package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to automatically start and configure a Gitea container for tests.
 * This annotation is supported on test classes only.
 * 
 * Usage:
 * <pre>
 * {@code
 * @WithGitea
 * class MyTest {
 *     
 *     @Test
 *     void testWithGitea(GiteaContainer gitea) {
 *         // Gitea is automatically started and configured
 *         String giteaUrl = gitea.getApiUrl();
 *         String token = gitea.getAdminToken();
 *         // ... test code
 *     }
 * }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(GiteaTestExtension.class)
public @interface WithGitea {

    /**
     * The admin username to create in Gitea
     */
    String adminUsername() default "testuser";

    /**
     * Alias for {@link #adminUsername()}.
     */
    String username() default "";

    /**
     * The admin password to create in Gitea
     */
    String adminPassword() default "testpass123";

    /**
     * Alias for {@link #adminPassword()}.
     */
    String password() default "";

    /**
     * The admin email to create in Gitea
     */
    String adminEmail() default "test@example.com";

    /**
     * Whether to create repositories for testing
     */
    boolean createTestRepos() default false;

    /**
     * Test repository names to create (only if createTestRepos = true)
     */
    String[] testRepoNames() default {};

    /**
     * Whether to enable GitHub Actions support with a dedicated runner
     */
    boolean actionsEnabled() default false;
}
