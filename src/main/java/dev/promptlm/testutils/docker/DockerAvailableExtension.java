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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fail-fast precondition for Testcontainers-backed tests.
 *
 * <p>Pings the Docker daemon before any {@code @WithGitea} / {@code @WithArtifactory}
 * container starts. If Docker is unreachable, the test class is <em>skipped</em>
 * via {@link Assumptions} with an actionable remediation hint, instead of
 * timing out ~4 minutes into container init with a confusing Testcontainers
 * stack trace.
 *
 * <p>Wired automatically by {@code @WithGitea} and {@code @WithArtifactory} —
 * consumers do not need to add {@code @ExtendWith(DockerAvailableExtension.class)}
 * themselves. The ping has a 5-second hard cap and uses
 * {@link DockerClientFactory} so the check honours the same socket discovery
 * logic Testcontainers itself will use.
 */
public final class DockerAvailableExtension implements BeforeAllCallback {

    private static final Logger log = LoggerFactory.getLogger(DockerAvailableExtension.class);

    private static final long PING_TIMEOUT_SECONDS = 5;

    private static final String REMEDIATION_HINT = """
            Docker is not reachable from this JVM. Tests using @WithGitea / @WithArtifactory \
            need a working Docker daemon for Testcontainers.

            To diagnose:
              docker info

            If you see "Cannot connect to the Docker daemon at unix:///var/run/docker.sock", \
            create the symlink:
              sudo ln -sf "$HOME/.docker/run/docker.sock" /var/run/docker.sock

            Then re-run the test.
            Cause: %s""";

    @Override
    public void beforeAll(ExtensionContext context) {
        CompletableFuture<Void> ping = CompletableFuture.runAsync(() ->
                DockerClientFactory.instance().client().pingCmd().exec());
        try {
            ping.get(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.debug("Docker daemon reachable; proceeding with Testcontainers-backed test {}",
                    context.getDisplayName());
        }
        catch (TimeoutException ex) {
            ping.cancel(true);
            skip("ping timed out after " + PING_TIMEOUT_SECONDS + "s");
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            skip(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            skip("interrupted while waiting for Docker ping");
        }
    }

    private static void skip(String cause) {
        Assumptions.assumeTrue(false, REMEDIATION_HINT.formatted(cause));
    }
}
