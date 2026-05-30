package dev.promptlm.testutils;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PollingHelperTest {

    @Test
    void returnsValueOnceAttemptSucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        PollingHelper.Result<String> result = PollingHelper.pollUntil(
                Duration.ofSeconds(2),
                Duration.ofMillis(20),
                () -> attempts.incrementAndGet() >= 3
                        ? Optional.of("ready")
                        : Optional.empty());

        assertThat(result.timedOut()).isFalse();
        assertThat(result.value()).contains("ready");
        assertThat(result.lastError()).isEmpty();
        assertThat(attempts.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void timesOutWhenConditionNeverSatisfied() {
        PollingHelper.Result<String> result = PollingHelper.pollUntil(
                Duration.ofMillis(200),
                Duration.ofMillis(20),
                Optional::empty);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.value()).isEmpty();
    }

    @Test
    void capturesLastErrorWhenAttemptKeepsThrowing() {
        PollingHelper.Result<String> result = PollingHelper.pollUntil(
                Duration.ofMillis(200),
                Duration.ofMillis(20),
                () -> {
                    throw new IllegalStateException("boom");
                });

        assertThat(result.timedOut()).isTrue();
        assertThat(result.value()).isEmpty();
        assertThat(result.lastError())
                .get()
                .isInstanceOf(IllegalStateException.class)
                .extracting(Throwable::getMessage)
                .isEqualTo("boom");
    }

    @Test
    void clearsCapturedErrorAfterEventualSuccess() {
        AtomicInteger attempts = new AtomicInteger();

        PollingHelper.Result<String> result = PollingHelper.pollUntil(
                Duration.ofSeconds(2),
                Duration.ofMillis(20),
                () -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("transient");
                    }
                    return Optional.of("ok");
                });

        assertThat(result.timedOut()).isFalse();
        assertThat(result.value()).contains("ok");
        assertThat(result.lastError()).isEmpty();
    }
}
