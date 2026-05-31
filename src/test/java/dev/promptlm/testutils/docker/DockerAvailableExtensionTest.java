/*
 * Copyright 2025 promptLM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.promptlm.testutils.docker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DockerAvailableExtension}.
 *
 * <p>These exercise the precondition's failure paths without a real Docker daemon by injecting
 * a {@link Runnable} ping action into the package-private constructor. The skip-with-remediation
 * behaviour is what consumers of {@code @WithGitea} / {@code @WithArtifactory} see on a developer
 * machine where Docker is unreachable (the canonical case being macOS Docker Desktop with a
 * broken {@code /var/run/docker.sock} symlink, equivalent in effect to {@code DOCKER_HOST=tcp://localhost:1}).
 */
class DockerAvailableExtensionTest {

    @Test
    void skipsWithRemediationHintWhenDockerPingFails() {
        // Equivalent to DOCKER_HOST=tcp://localhost:1 — the docker-java client surfaces an
        // unreachable daemon as a runtime exception inside the ping invocation.
        Runnable connectionRefused = () -> {
            throw new RuntimeException("Connection refused");
        };
        DockerAvailableExtension extension =
                new DockerAvailableExtension(connectionRefused, 5);
        ExtensionContext context = newExtensionContext();

        assertThatThrownBy(() -> extension.beforeAll(context))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("Docker is not reachable from this JVM")
                .hasMessageContaining("Tests using @WithGitea / @WithArtifactory")
                .hasMessageContaining("docker info")
                .hasMessageContaining("Cannot connect to the Docker daemon at unix:///var/run/docker.sock")
                .hasMessageContaining("sudo ln -sf \"$HOME/.docker/run/docker.sock\" /var/run/docker.sock")
                .hasMessageContaining("Cause: RuntimeException: Connection refused");
    }

    @Test
    void skipsWithTimeoutCauseWhenPingExceedsHardCap() {
        // Sleep longer than the (small, test-only) timeout so the CompletableFuture.get(..)
        // call surfaces a TimeoutException and the extension routes through the timeout branch.
        Runnable slowPing = () -> {
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        DockerAvailableExtension extension =
                new DockerAvailableExtension(slowPing, 1);
        ExtensionContext context = newExtensionContext();

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> extension.beforeAll(context))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("Docker is not reachable from this JVM")
                .hasMessageContaining("sudo ln -sf \"$HOME/.docker/run/docker.sock\" /var/run/docker.sock")
                .hasMessageContaining("Cause: ping timed out after 1s");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        // Hard cap is the timeout itself plus a small margin for CompletableFuture scheduling.
        // We do NOT want the slow-ping to run to completion (2s) — that would mean the cancel
        // didn't take effect and the timeout branch was bypassed.
        assertThat(elapsedMs)
                .as("timeout branch should fire within ~ timeoutSeconds, not wait for the ping to finish")
                .isLessThan(1_900);
    }

    @Test
    void healthyPingReturnsWithoutThrowing() {
        // No-op runnable simulates a successful, fast Docker ping. The extension should proceed
        // silently — no TestAbortedException, no measurable overhead beyond CompletableFuture
        // scheduling.
        DockerAvailableExtension extension =
                new DockerAvailableExtension(() -> {
                }, 5);
        ExtensionContext context = newExtensionContext();

        long startNanos = System.nanoTime();
        extension.beforeAll(context);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        // <50 ms healthy-host overhead is the AC-2 claim. Allow a generous margin for CI noise;
        // the point is to detect catastrophic regression (e.g. someone making the success path
        // synchronously block for seconds), not to micro-benchmark.
        assertThat(elapsedMs)
                .as("healthy-host overhead must remain trivially small")
                .isLessThan(500);
    }

    private static ExtensionContext newExtensionContext() {
        ExtensionContext context = mock(ExtensionContext.class);
        when(context.getDisplayName()).thenReturn("DockerAvailableExtensionTest");
        return context;
    }
}
