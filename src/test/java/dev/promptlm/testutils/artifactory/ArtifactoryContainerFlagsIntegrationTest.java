package dev.promptlm.testutils.artifactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactoryContainerFlagsIntegrationTest {

    private static final String REUSE_FLAG = "testcontainers.reuse.enable";
    private static final String RYUK_FLAG = "testcontainers.ryuk.disabled";

    private static String previousReuseFlag;
    private static String previousRyukFlag;

    @BeforeAll
    static void enableTestcontainersFlags() {
        previousReuseFlag = System.getProperty(REUSE_FLAG);
        previousRyukFlag = System.getProperty(RYUK_FLAG);

        System.setProperty(REUSE_FLAG, "true");
        System.setProperty(RYUK_FLAG, "true");
    }

    @AfterAll
    static void restoreTestcontainersFlags() {
        restoreProperty(REUSE_FLAG, previousReuseFlag);
        restoreProperty(RYUK_FLAG, previousRyukFlag);
    }

    @Test
    void shouldExposeContainerIdWhenFlagsAreEnabled() throws Exception {
        ArtifactoryContainer artifactory = new ArtifactoryContainer();
        try {
            artifactory.start();

            String containerId = getContainerId(artifactory);
            assertThat(containerId).isNotBlank();
            assertThat(artifactory.getApiUrl()).isNotBlank();
            assertThat(artifactory.getRunnerAccessibleApiUrl())
                    .contains("host.testcontainers.internal");
        } finally {
            artifactory.stop();
        }
    }

    private static String getContainerId(ArtifactoryContainer artifactory) throws Exception {
        Field field = ArtifactoryContainer.class.getDeclaredField("container");
        field.setAccessible(true);
        GenericContainer<?> container = (GenericContainer<?>) field.get(artifactory);
        return container.getContainerId();
    }

    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }
}
