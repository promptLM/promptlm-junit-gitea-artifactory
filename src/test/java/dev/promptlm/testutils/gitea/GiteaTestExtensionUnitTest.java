package dev.promptlm.testutils.gitea;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedConstruction;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GiteaTestExtensionUnitTest {

    private final GiteaTestExtension extension = new GiteaTestExtension();

    @AfterEach
    void clearProperties() {
        System.clearProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL);
        System.clearProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME);
        System.clearProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN);
    }

    @Test
    void startsContainerAndSetsPropertiesForAnnotatedClass() {
        ExtensionContext context = extensionContextFor(AnnotatedWithReposTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        when(context.getStore(any())).thenReturn(store);

        try (MockedConstruction<GiteaContainer> construction = mockConstruction(GiteaContainer.class, (mock, c) -> {
            when(mock.withAdminUser(anyString(), anyString(), anyString())).thenReturn(mock);
            when(mock.withActionsEnabled(anyBoolean())).thenReturn(mock);
            when(mock.getApiUrl()).thenReturn("http://localhost:39999/api/v1");
            when(mock.getAdminUsername()).thenReturn("testuser");
            when(mock.getAdminToken()).thenReturn("token-123");
            when(mock.getWebUrl()).thenReturn("http://localhost:39999");
        })) {
            extension.beforeAll(context);

            GiteaContainer container = construction.constructed().get(0);
            verify(container).start();
            verify(container).createRepository("repo-a");
            verify(container).createRepository("repo-b");
            verify(store).put(containerKey(AnnotatedWithReposTest.class), container);
            assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL)).isEqualTo("http://localhost:39999/api/v1");
            assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME)).isEqualTo("testuser");
            assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN)).isEqualTo("token-123");
        }
    }

    @Test
    void usesAliasCredentialsWhenProvided() {
        ExtensionContext context = extensionContextFor(AnnotatedWithAliasCredentialsTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        when(context.getStore(any())).thenReturn(store);

        try (MockedConstruction<GiteaContainer> construction = mockConstruction(GiteaContainer.class, (mock, c) -> {
            when(mock.withAdminUser(anyString(), anyString(), anyString())).thenReturn(mock);
            when(mock.withActionsEnabled(anyBoolean())).thenReturn(mock);
            when(mock.getApiUrl()).thenReturn("http://localhost:39999/api/v1");
            when(mock.getAdminUsername()).thenReturn("alias-user");
            when(mock.getAdminToken()).thenReturn("token-123");
            when(mock.getWebUrl()).thenReturn("http://localhost:39999");
        })) {
            extension.beforeAll(context);

            GiteaContainer container = construction.constructed().get(0);
            verify(container).withAdminUser(eq("alias-user"), eq("alias-pass"), eq("alias@example.com"));
        }
    }

    @Test
    void stopsContainerAndClearsPropertiesInAfterAll() {
        ExtensionContext context = extensionContextFor(AnnotatedDefaultTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        GiteaContainer container = mock(GiteaContainer.class);
        when(context.getStore(any())).thenReturn(store);
        when(store.get(containerKey(AnnotatedDefaultTest.class), GiteaContainer.class)).thenReturn(container);
        when(store.get(propertiesKey(AnnotatedDefaultTest.class), java.util.Map.class)).thenReturn(java.util.Map.of());

        System.setProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL, "foo");
        System.setProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME, "bar");
        System.setProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN, "baz");

        extension.afterAll(context);

        verify(container).stop();
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL)).isNull();
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME)).isNull();
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN)).isNull();
    }

    @Test
    void restoresPreviousPropertiesInAfterAll() {
        ExtensionContext context = extensionContextFor(AnnotatedDefaultTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        GiteaContainer container = mock(GiteaContainer.class);
        when(context.getStore(any())).thenReturn(store);
        when(store.get(containerKey(AnnotatedDefaultTest.class), GiteaContainer.class)).thenReturn(container);
        when(store.get(propertiesKey(AnnotatedDefaultTest.class), java.util.Map.class)).thenReturn(java.util.Map.of(
                GiteaEnvironmentProperties.REPO_REMOTE_URL, "http://prev/api",
                GiteaEnvironmentProperties.REPO_REMOTE_USERNAME, "prev-user",
                GiteaEnvironmentProperties.REPO_REMOTE_TOKEN, "prev-token"));

        System.setProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL, "new-url");
        System.setProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME, "new-user");
        System.setProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN, "new-token");

        extension.afterAll(context);

        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_URL)).isEqualTo("http://prev/api");
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_USERNAME)).isEqualTo("prev-user");
        assertThat(System.getProperty(GiteaEnvironmentProperties.REPO_REMOTE_TOKEN)).isEqualTo("prev-token");
    }

    @Test
    void doesNothingWhenNoWithGiteaAnnotationExists() {
        ExtensionContext context = extensionContextFor(NoAnnotationTest.class);

        try (MockedConstruction<GiteaContainer> construction = mockConstruction(GiteaContainer.class)) {
            extension.beforeAll(context);
            assertThat(construction.constructed()).isEmpty();
        }
    }


    @Test
    void abortsClassWhenDockerNotAvailable() {
        ExtensionContext context = extensionContextFor(AnnotatedDefaultTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        when(context.getStore(any())).thenReturn(store);

        try (MockedConstruction<GiteaContainer> construction = mockConstruction(GiteaContainer.class, (mock, c) -> {
            when(mock.withAdminUser(anyString(), anyString(), anyString())).thenReturn(mock);
            when(mock.withActionsEnabled(anyBoolean())).thenReturn(mock);
            org.mockito.Mockito.doThrow(new IllegalStateException("docker unavailable")).when(mock).start();
        })) {
            assertThatThrownBy(() -> extension.beforeAll(context)).isInstanceOf(TestAbortedException.class);
            verify(store, never()).put(eq(containerKey(AnnotatedDefaultTest.class)), any());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "containerParameterMethod,true",
            "nonContainerParameterMethod,false",
            "giteaAnnotatedContainerMethod,true",
            "giteaUrlStringParameterMethod,true",
            "giteaUrlUriParameterMethod,true"
    })
    void supportsParameterResolution(String methodName, boolean expected) throws Exception {
        ParameterContextStub parameterContext = new ParameterContextStub(methodName);
        ExtensionContext context = extensionContextFor(AnnotatedDefaultTest.class);
        boolean supports = extension.supportsParameter(parameterContext, context);

        assertThat(supports).isEqualTo(expected);
    }

    @Test
    void resolvesParameterFromExtensionStore() {
        ExtensionContext context = extensionContextFor(AnnotatedDefaultTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        GiteaContainer container = mock(GiteaContainer.class);
        when(context.getStore(any())).thenReturn(store);
        when(store.get(containerKey(AnnotatedDefaultTest.class), GiteaContainer.class)).thenReturn(container);

        Object resolved = extension.resolveParameter(new ParameterContextStub("containerParameterMethod"), context);
        assertThat(resolved).isSameAs(container);
    }

    @Test
    void resolvesGiteaUrlParameters() {
        ExtensionContext context = extensionContextFor(AnnotatedDefaultTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        GiteaContainer container = mock(GiteaContainer.class);
        when(context.getStore(any())).thenReturn(store);
        when(store.get(containerKey(AnnotatedDefaultTest.class), GiteaContainer.class)).thenReturn(container);
        when(container.getWebUrl()).thenReturn("http://localhost:39999");
        when(container.getApiUrl()).thenReturn("http://localhost:39999/api/v1");

        Object webUrl = extension.resolveParameter(new ParameterContextStub("giteaUrlStringParameterMethod"), context);
        Object apiUrl = extension.resolveParameter(new ParameterContextStub("giteaUrlUriParameterMethod"), context);

        assertThat(webUrl).isEqualTo("http://localhost:39999");
        assertThat(apiUrl).isEqualTo(URI.create("http://localhost:39999/api/v1"));
    }

    @Test
    void rejectsInvalidGiteaUrlParameterType() {
        ExtensionContext context = extensionContextFor(AnnotatedDefaultTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        GiteaContainer container = mock(GiteaContainer.class);
        when(context.getStore(any())).thenReturn(store);
        when(store.get(containerKey(AnnotatedDefaultTest.class), GiteaContainer.class)).thenReturn(container);
        when(container.getWebUrl()).thenReturn("http://localhost:39999");

        assertThatThrownBy(() -> extension.resolveParameter(new ParameterContextStub("giteaUrlInvalidParameterMethod"), context))
                .isInstanceOf(ParameterResolutionException.class)
                .hasMessageContaining("@GiteaUrl");
    }

    @Test
    void injectsAnnotatedFields() {
        ExtensionContext context = extensionContextFor(AnnotatedFieldInjectionTest.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        GiteaContainer container = mock(GiteaContainer.class);
        when(context.getStore(any())).thenReturn(store);
        when(store.get(containerKey(AnnotatedFieldInjectionTest.class), GiteaContainer.class)).thenReturn(container);
        when(container.getWebUrl()).thenReturn("http://localhost:39999");
        when(container.getApiUrl()).thenReturn("http://localhost:39999/api/v1");

        AnnotatedFieldInjectionTest instance = new AnnotatedFieldInjectionTest();
        extension.postProcessTestInstance(instance, context);

        assertThat(instance.gitea).isSameAs(container);
        assertThat(instance.giteaUrl).isEqualTo("http://localhost:39999");
        assertThat(instance.apiUrl).isEqualTo(URI.create("http://localhost:39999/api/v1"));
    }

    private static ExtensionContext extensionContextFor(Class<?> testClass) {
        ExtensionContext context = mock(ExtensionContext.class);
        when(context.getElement()).thenReturn(Optional.of(testClass));
        when(context.getTestClass()).thenReturn(Optional.of(testClass));
        when(context.getDisplayName()).thenReturn(testClass.getSimpleName());
        when(context.getRoot()).thenReturn(context);
        return context;
    }

    private static String containerKey(Class<?> testClass) {
        return "gitea-container:" + testClass.getName();
    }

    private static String propertiesKey(Class<?> testClass) {
        return "previous-repo-properties:" + testClass.getName();
    }

    @WithGitea
    static class AnnotatedDefaultTest {
    }

    @WithGitea(createTestRepos = true, testRepoNames = {"repo-a", "repo-b"})
    static class AnnotatedWithReposTest {
    }

    @WithGitea(username = "alias-user", password = "alias-pass", adminEmail = "alias@example.com")
    static class AnnotatedWithAliasCredentialsTest {
    }

    @WithGitea
    static class AnnotatedFieldInjectionTest {
        @Gitea
        private GiteaContainer gitea;

        @GiteaUrl
        private String giteaUrl;

        @GiteaUrl(api = true)
        private URI apiUrl;
    }

    static class NoAnnotationTest {
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
        public java.util.Optional<Object> getTarget() {
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
        static void containerParameterMethod(GiteaContainer giteaContainer) {
        }

        @SuppressWarnings("unused")
        static void nonContainerParameterMethod(String value) {
        }

        @SuppressWarnings("unused")
        static void giteaAnnotatedContainerMethod(@Gitea GiteaContainer giteaContainer) {
        }

        @SuppressWarnings("unused")
        static void giteaUrlStringParameterMethod(@GiteaUrl String url) {
        }

        @SuppressWarnings("unused")
        static void giteaUrlUriParameterMethod(@GiteaUrl(api = true) URI url) {
        }

        @SuppressWarnings("unused")
        static void giteaUrlInvalidParameterMethod(@GiteaUrl int value) {
        }
    }
}
