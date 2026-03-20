package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.net.URI;

/**
 * JUnit 5 extension that manages Gitea container lifecycle for tests annotated with @WithGitea
 */
class GiteaTestExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver, TestInstancePostProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(GiteaTestExtension.class);
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create("gitea-test");
    private static final String KEY_CONTAINER = "gitea-container";
    
    @Override
    public void beforeAll(ExtensionContext context) {
        ensureContainerStarted(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        ExtensionContext.Store store = getStore(context);
        String containerKey = containerKey(context);
        GiteaContainer giteaContainer = store.get(containerKey, GiteaContainer.class);
        if (giteaContainer != null) {
            logger.info("Stopping Gitea container for test class: {}", context.getDisplayName());
            giteaContainer.stop();
        }
    }
    
    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Parameter parameter = parameterContext.getParameter();
        return parameter.isAnnotationPresent(Gitea.class)
                || parameter.isAnnotationPresent(GiteaUrl.class)
                || parameter.getType().equals(GiteaContainer.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Parameter parameter = parameterContext.getParameter();
        if (parameter.isAnnotationPresent(Gitea.class)) {
            if (!parameter.getType().equals(GiteaContainer.class)) {
                throw new ParameterResolutionException("@Gitea can only be applied to GiteaContainer parameters.");
            }
            return requireContainer(extensionContext);
        }
        if (parameter.isAnnotationPresent(GiteaUrl.class)) {
            GiteaUrl annotation = parameter.getAnnotation(GiteaUrl.class);
            return resolveUrlParameter(annotation, parameter.getType(), requireContainer(extensionContext));
        }
        if (parameter.getType().equals(GiteaContainer.class)) {
            return requireContainer(extensionContext);
        }
        throw new ParameterResolutionException("Unsupported parameter for GiteaTestExtension: " + parameter);
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
        GiteaContainer container = ensureContainerStarted(context);
        if (container == null) {
            return;
        }
        injectFields(testInstance, container);
    }

    private WithGitea getWithGiteaAnnotation(ExtensionContext context) {
        return context.getTestClass()
                .map(clazz -> clazz.getAnnotation(WithGitea.class))
                .orElse(null);
    }

    private GiteaContainer ensureContainerStarted(ExtensionContext context) {
        WithGitea annotation = getWithGiteaAnnotation(context);
        if (annotation == null) {
            return null;
        }

        ExtensionContext.Store store = getStore(context);
        String containerKey = containerKey(context);
        GiteaContainer existing = store.get(containerKey, GiteaContainer.class);
        if (existing != null) {
            return existing;
        }

        logger.info("Starting Gitea container for test class: {}", context.getDisplayName());

        String adminUsername = firstNonBlank(annotation.username(), annotation.adminUsername(), "testuser");
        String adminPassword = firstNonBlank(annotation.password(), annotation.adminPassword(), "testpass123");
        String adminEmail = defaultIfBlank(annotation.adminEmail(), "test@example.com");

        GiteaContainer giteaContainer = new GiteaContainer()
                .withAdminUser(adminUsername, adminPassword, adminEmail)
                .withActionsEnabled(annotation.actionsEnabled())
                .withRunnerImage(annotation.runnerImage());

        try {
            giteaContainer.start();
        } catch (IllegalStateException ex) {
            Assumptions.assumeTrue(false, "Docker not available for Gitea container: " + ex.getMessage());
        }

        // Create test repositories if requested
        if (annotation.createTestRepos() && annotation.testRepoNames().length > 0) {
            for (String repoName : annotation.testRepoNames()) {
                giteaContainer.createRepository(repoName);
            }
        }

        store.put(containerKey, giteaContainer);

        logger.info("Gitea container started successfully at: {}", giteaContainer.getWebUrl());
        return giteaContainer;
    }

    private GiteaContainer requireContainer(ExtensionContext context) {
        GiteaContainer container = ensureContainerStarted(context);
        if (container == null) {
            throw new ParameterResolutionException("No Gitea container available. Did you forget @WithGitea?");
        }
        return container;
    }

    private void injectFields(Object testInstance, GiteaContainer container) {
        Class<?> current = testInstance.getClass();
        while (current != Object.class && current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Gitea.class)) {
                    if (!field.getType().equals(GiteaContainer.class)) {
                        throw new ExtensionConfigurationException("@Gitea can only be applied to GiteaContainer fields.");
                    }
                    setField(field, testInstance, container);
                } else if (field.isAnnotationPresent(GiteaUrl.class)) {
                    GiteaUrl annotation = field.getAnnotation(GiteaUrl.class);
                    Object value = resolveUrlParameter(annotation, field.getType(), container);
                    setField(field, testInstance, value);
                }
            }
            current = current.getSuperclass();
        }
    }

    private Object resolveUrlParameter(GiteaUrl annotation, Class<?> targetType, GiteaContainer container) {
        String resolved = annotation.api() ? container.getApiUrl() : container.getWebUrl();
        if (targetType.equals(String.class)) {
            return resolved;
        }
        if (targetType.equals(URI.class)) {
            return URI.create(resolved);
        }
        throw new ParameterResolutionException("@GiteaUrl supports String or URI targets only.");
    }

    private void setField(Field field, Object testInstance, Object value) {
        try {
            field.setAccessible(true);
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.set(null, value);
            } else {
                field.set(testInstance, value);
            }
        } catch (IllegalAccessException e) {
            throw new ExtensionConfigurationException("Failed to inject field: " + field, e);
        }
    }

    private String firstNonBlank(String primary, String fallback, String defaultValue) {
        if (!isBlank(primary)) {
            return primary;
        }
        if (!isBlank(fallback)) {
            return fallback;
        }
        return defaultValue;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE);
    }

    private String containerKey(ExtensionContext context) {
        return KEY_CONTAINER + ":" + testClassName(context);
    }

    private String testClassName(ExtensionContext context) {
        return context.getTestClass()
                .map(Class::getName)
                .orElseThrow(() -> new ExtensionConfigurationException("GiteaTestExtension requires a test class context."));
    }

}
