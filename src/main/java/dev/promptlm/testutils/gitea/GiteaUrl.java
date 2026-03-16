package dev.promptlm.testutils.gitea;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a parameter or field for Gitea URL injection.
 */
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface GiteaUrl {

    /**
     * When true, inject the API URL instead of the web URL.
     *
     * @return {@code true} to inject the API URL
     */
    boolean api() default false;
}
