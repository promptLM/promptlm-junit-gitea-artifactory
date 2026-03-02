package dev.promptlm.testutils.artifactory;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactoryContainerLoggingTest {

    @Test
    void doesNotAttachLogsWhenContainerIdMissing() {
        GenericContainer<?> container = mock(GenericContainer.class);
        ArtifactoryContainer artifactory = new ArtifactoryContainer(container).enableLogging();

        when(container.getContainerId()).thenReturn(null);

        artifactory.attachLogsIfEnabled();

        verify(container, never()).followOutput(any());
    }

    @Test
    void attachesLogsWhenContainerIdPresent() {
        GenericContainer<?> container = mock(GenericContainer.class);
        ArtifactoryContainer artifactory = new ArtifactoryContainer(container).enableLogging();

        when(container.getContainerId()).thenReturn("container-123");

        artifactory.attachLogsIfEnabled();

        verify(container).followOutput(any());
    }
}
