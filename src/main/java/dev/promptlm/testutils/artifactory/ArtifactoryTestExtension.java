package dev.promptlm.testutils.artifactory;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

class ArtifactoryTestExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver, TestInstancePostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(ArtifactoryTestExtension.class);
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create("promptlm", "artifactory");
    private static final String STORE_KEY = "container";
    private static final String ARTIFACTORY_PROPERTY_URL = "artifactory.url";
    private static final String ARTIFACTORY_PROPERTY_MAVEN_REPO_URL = "artifactory.maven.repository.url";
    private static final String ARTIFACTORY_PROPERTY_ADMIN_USERNAME = "artifactory.admin.username";
    private static final String ARTIFACTORY_PROPERTY_ADMIN_PASSWORD = "artifactory.admin.password";
    private static final String ARTIFACTORY_PROPERTY_DEPLOYER_USERNAME = "artifactory.deployer.username";
    private static final String ARTIFACTORY_PROPERTY_DEPLOYER_PASSWORD = "artifactory.deployer.password";
    private static final String ARTIFACTORY_PROPERTY_MAVEN_REPO_NAME = "artifactory.maven.repository.name";
    private static final String ARTIFACTORY_PROPERTY_INTERNAL_API_URL = "artifactory.internal.api.url";
    private static final String ARTIFACTORY_PROPERTY_RUNNER_API_URL = "artifactory.runner.api.url";
    private static final String[] PROPERTY_KEYS = {
            ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_URL,
            ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_REPOSITORY,
            ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_USERNAME,
            ArtifactoryContainer.ACTIONS_VARIABLE_ARTIFACTORY_PASSWORD,
            ARTIFACTORY_PROPERTY_URL,
            ARTIFACTORY_PROPERTY_MAVEN_REPO_URL,
            ARTIFACTORY_PROPERTY_ADMIN_USERNAME,
            ARTIFACTORY_PROPERTY_ADMIN_PASSWORD,
            ARTIFACTORY_PROPERTY_DEPLOYER_USERNAME,
            ARTIFACTORY_PROPERTY_DEPLOYER_PASSWORD,
            ARTIFACTORY_PROPERTY_MAVEN_REPO_NAME,
            ARTIFACTORY_PROPERTY_INTERNAL_API_URL,
            ARTIFACTORY_PROPERTY_RUNNER_API_URL
    };

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        ensureContainerStarted(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        // Do nothing so the container stays alive across the suite.
        clearSystemProperties();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().isAnnotationPresent(Artifactory.class)
                || parameterContext.getParameter().getType().equals(ArtifactoryContainer.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        if (parameterContext.getParameter().isAnnotationPresent(Artifactory.class)
                && !parameterContext.getParameter().getType().equals(ArtifactoryContainer.class)) {
            throw new ParameterResolutionException("@Artifactory can only be applied to ArtifactoryContainer parameters.");
        }
        return requireContainer(extensionContext);
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
        ArtifactoryContainer container = ensureContainerStarted(context);
        injectFields(testInstance, container);
    }

    private ArtifactoryContainer ensureContainerStarted(ExtensionContext context) {
        ExtensionContext.Store store = context.getRoot().getStore(NAMESPACE);
        ArtifactoryContainer container = store.get(STORE_KEY, ArtifactoryContainer.class);

        if (container == null) {
            container = createContainer(context);
            logger.info("Starting shared Artifactory container for tests");
            try {
                container.start();
            } catch (IllegalStateException ex) {
                Assumptions.assumeTrue(false, "Docker not available for Artifactory container: " + ex.getMessage());
            } catch (Exception ex) {
                throw new ExtensionConfigurationException("Failed to start Artifactory container", ex);
            }
            store.put(STORE_KEY, container);
        }

        configureSystemProperties(container);
        return container;
    }

    private ArtifactoryContainer requireContainer(ExtensionContext context) {
        ArtifactoryContainer container = ensureContainerStarted(context);
        if (container == null) {
            throw new ParameterResolutionException("No Artifactory container available.");
        }
        return container;
    }

    private void injectFields(Object testInstance, ArtifactoryContainer container) {
        Class<?> current = testInstance.getClass();
        while (current != Object.class && current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Artifactory.class)) {
                    continue;
                }
                if (!field.getType().equals(ArtifactoryContainer.class)) {
                    throw new ExtensionConfigurationException("@Artifactory can only be applied to ArtifactoryContainer fields.");
                }
                setField(field, testInstance, container);
            }
            current = current.getSuperclass();
        }
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

    private ArtifactoryContainer createContainer(ExtensionContext context) {
        return context.getElement()
                .map(el -> el.getAnnotation(WithArtifactory.class))
                .map(annotation -> {
                    ArtifactoryContainer container = new ArtifactoryContainer()
                            .withAdminUser(annotation.adminUsername(), annotation.adminPassword())
                            .withDeployerUser(annotation.deployerUsername(), annotation.deployerPassword(), annotation.deployerEmail())
                            .withMavenRepository(annotation.mavenRepository())
                            .withNetworkAlias("artifactory");
                    if (annotation.logContainer()) {
                        container.enableLogging();
                    }
                    return container;
                })
                .orElseGet(ArtifactoryContainer::new);
    }

    private void configureSystemProperties(ArtifactoryContainer container) {
        container.standardActionsVariables().forEach(this::setProperty);

        setProperty(ARTIFACTORY_PROPERTY_URL, container.getApiUrl());
        setProperty(ARTIFACTORY_PROPERTY_MAVEN_REPO_URL, container.getMavenRepositoryUrl());
        setProperty(ARTIFACTORY_PROPERTY_ADMIN_USERNAME, container.getAdminUsername());
        setProperty(ARTIFACTORY_PROPERTY_ADMIN_PASSWORD, container.getAdminPassword());
        setProperty(ARTIFACTORY_PROPERTY_DEPLOYER_USERNAME, container.getDeployerUsername());
        setProperty(ARTIFACTORY_PROPERTY_DEPLOYER_PASSWORD, container.getDeployerPassword());
        setProperty(ARTIFACTORY_PROPERTY_MAVEN_REPO_NAME, container.getMavenRepositoryName());
        setProperty(ARTIFACTORY_PROPERTY_INTERNAL_API_URL, container.getInternalApiUrl());
        setProperty(ARTIFACTORY_PROPERTY_RUNNER_API_URL, container.getRunnerAccessibleApiUrl());
    }

    private void clearSystemProperties() {
        for (String key : PROPERTY_KEYS) {
            System.clearProperty(key);
        }
    }

    private void setProperty(String key, String value) {
        System.setProperty(key, value);
    }
}
