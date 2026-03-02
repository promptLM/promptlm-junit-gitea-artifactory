package dev.promptlm.testutils.artifactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.mockito.MockedConstruction;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactoryTestExtensionUnitTest {

    private final ArtifactoryTestExtension extension = new ArtifactoryTestExtension();

    @AfterEach
    void clearProperties() {
        System.clearProperty("REPO_REMOTE_URL");
        System.clearProperty("REPO_REMOTE_USERNAME");
        System.clearProperty("REPO_REMOTE_TOKEN");
        System.clearProperty("artifactory.url");
        System.clearProperty("artifactory.maven.repository.url");
        System.clearProperty("artifactory.admin.username");
        System.clearProperty("artifactory.admin.password");
        System.clearProperty("artifactory.deployer.username");
        System.clearProperty("artifactory.deployer.password");
        System.clearProperty("artifactory.maven.repository.name");
        System.clearProperty("artifactory.internal.api.url");
        System.clearProperty("artifactory.runner.api.url");
    }

    @Test
    void startsContainerAndSetsPropertiesForAnnotatedClass() throws Exception {
        ExtensionContext context = extensionContextFor(AnnotatedDefaultTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        when(context.getStore(any())).thenReturn(store);

        try (MockedConstruction<ArtifactoryContainer> construction = mockConstruction(ArtifactoryContainer.class, (mock, c) -> {
            when(mock.withAdminUser(any(), any())).thenReturn(mock);
            when(mock.withDeployerUser(any(), any(), any())).thenReturn(mock);
            when(mock.withMavenRepository(any())).thenReturn(mock);
            when(mock.withNetworkAlias(any())).thenReturn(mock);
            when(mock.getMavenRepositoryUrl()).thenReturn("http://localhost:8081/artifactory/libs-release-local");
            when(mock.getDeployerUsername()).thenReturn("ci-deployer");
            when(mock.getDeployerPassword()).thenReturn("ci-deployer-password");
            when(mock.getApiUrl()).thenReturn("http://localhost:8081/artifactory");
            when(mock.getAdminUsername()).thenReturn("admin");
            when(mock.getAdminPassword()).thenReturn("password");
            when(mock.getMavenRepositoryName()).thenReturn("libs-release-local");
            when(mock.getInternalApiUrl()).thenReturn("http://artifactory:8081/artifactory");
            when(mock.getRunnerAccessibleApiUrl()).thenReturn("http://host.testcontainers.internal:8081/artifactory");
        })) {
            extension.beforeAll(context);

            ArtifactoryContainer container = construction.constructed().get(0);
            verify(container).start();

            assertThat(System.getProperty("artifactory.url")).isEqualTo("http://localhost:8081/artifactory");
            assertThat(System.getProperty("artifactory.maven.repository.url")).isEqualTo("http://localhost:8081/artifactory/libs-release-local");
            assertThat(System.getProperty("artifactory.deployer.username")).isEqualTo("ci-deployer");
            assertThat(System.getProperty("artifactory.deployer.password")).isEqualTo("ci-deployer-password");
        }
    }

    @Test
    void appliesAnnotationOverridesAndLogging() throws Exception {
        ExtensionContext context = extensionContextFor(AnnotatedWithOverridesTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        when(context.getStore(any())).thenReturn(store);

        try (MockedConstruction<ArtifactoryContainer> construction = mockConstruction(ArtifactoryContainer.class, (mock, c) -> {
            when(mock.withAdminUser(any(), any())).thenReturn(mock);
            when(mock.withDeployerUser(any(), any(), any())).thenReturn(mock);
            when(mock.withMavenRepository(any())).thenReturn(mock);
            when(mock.withNetworkAlias(any())).thenReturn(mock);
            when(mock.enableLogging()).thenReturn(mock);
            stubContainerDefaults(mock);
        })) {
            extension.beforeAll(context);

            ArtifactoryContainer container = construction.constructed().get(0);
            verify(container).withAdminUser("admin-x", "pass-x");
            verify(container).withDeployerUser("deploy-x", "deploy-pass", "deploy@example.com");
            verify(container).withMavenRepository("custom-repo");
            verify(container).withNetworkAlias("artifactory");
            verify(container).enableLogging();
        }
    }

    @Test
    void injectsAnnotatedFields() {
        ExtensionContext context = extensionContextFor(AnnotatedFieldInjectionTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        ArtifactoryContainer container = mock(ArtifactoryContainer.class);
        stubContainerDefaults(container);
        when(context.getStore(any())).thenReturn(store);
        when(store.get("container", ArtifactoryContainer.class)).thenReturn(container);

        AnnotatedFieldInjectionTest instance = new AnnotatedFieldInjectionTest();
        extension.postProcessTestInstance(instance, context);

        assertThat(instance.artifactory).isSameAs(container);
    }

    private static void stubContainerDefaults(ArtifactoryContainer container) {
        when(container.getMavenRepositoryUrl()).thenReturn("http://localhost:8081/artifactory/libs-release-local");
        when(container.getDeployerUsername()).thenReturn("ci-deployer");
        when(container.getDeployerPassword()).thenReturn("ci-deployer-password");
        when(container.getApiUrl()).thenReturn("http://localhost:8081/artifactory");
        when(container.getAdminUsername()).thenReturn("admin");
        when(container.getAdminPassword()).thenReturn("password");
        when(container.getMavenRepositoryName()).thenReturn("libs-release-local");
        when(container.getInternalApiUrl()).thenReturn("http://artifactory:8081/artifactory");
        when(container.getRunnerAccessibleApiUrl()).thenReturn("http://host.testcontainers.internal:8081/artifactory");
    }

    @Test
    void rejectsInvalidParameterType() {
        ExtensionContext context = extensionContextFor(AnnotatedDefaultTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        when(context.getStore(any())).thenReturn(store);

        assertThatThrownBy(() -> extension.resolveParameter(new ParameterContextStub("invalidParameterMethod"), context))
                .isInstanceOf(ParameterResolutionException.class)
                .hasMessageContaining("@Artifactory");
    }

    private static ExtensionContext extensionContextFor(Class<?> testClass) {
        ExtensionContext context = mock(ExtensionContext.class);
        when(context.getElement()).thenReturn(Optional.of(testClass));
        when(context.getTestClass()).thenReturn(Optional.of(testClass));
        when(context.getDisplayName()).thenReturn(testClass.getSimpleName());
        when(context.getRoot()).thenReturn(context);
        return context;
    }

    @WithArtifactory
    static class AnnotatedDefaultTest {
    }

    @WithArtifactory(
            adminUsername = "admin-x",
            adminPassword = "pass-x",
            deployerUsername = "deploy-x",
            deployerPassword = "deploy-pass",
            deployerEmail = "deploy@example.com",
            mavenRepository = "custom-repo",
            logContainer = true
    )
    static class AnnotatedWithOverridesTest {
    }

    @WithArtifactory
    static class AnnotatedFieldInjectionTest {
        @Artifactory
        private ArtifactoryContainer artifactory;
    }

    static class ParameterContextStub implements org.junit.jupiter.api.extension.ParameterContext {
        private final Method method;
        private final int parameterIndex;

        ParameterContextStub(String methodName) {
            try {
                Method selected = null;
                for (Method candidate : ParameterMethods.class.getDeclaredMethods()) {
                    if (candidate.getName().equals(methodName)) {
                        selected = candidate;
                        break;
                    }
                }
                if (selected == null) {
                    throw new IllegalStateException("Invalid test setup for method " + methodName);
                }
                this.method = selected;
                this.parameterIndex = 0;
            } catch (SecurityException e) {
                throw new IllegalStateException("Invalid test setup for method " + methodName, e);
            }
        }

        @Override
        public java.lang.reflect.Parameter getParameter() {
            return method.getParameters()[parameterIndex];
        }

        @Override
        public int getIndex() {
            return parameterIndex;
        }

        @Override
        public Optional<Object> getTarget() {
            return Optional.empty();
        }

        @Override
        public java.lang.reflect.Executable getDeclaringExecutable() {
            return method;
        }

        @Override
        public boolean isAnnotated(Class<? extends java.lang.annotation.Annotation> annotationType) {
            return getParameter().isAnnotationPresent(annotationType);
        }

        @Override
        public <A extends java.lang.annotation.Annotation> Optional<A> findAnnotation(Class<A> annotationType) {
            return Optional.ofNullable(getParameter().getAnnotation(annotationType));
        }

        @Override
        public <A extends java.lang.annotation.Annotation> java.util.List<A> findRepeatableAnnotations(Class<A> annotationType) {
            return java.util.List.of();
        }
    }

    static class ParameterMethods {
        @SuppressWarnings("unused")
        static void invalidParameterMethod(@Artifactory String value) {
        }
    }
}
