package dev.promptlm.testutils;

import org.awaitility.core.ConditionTimeoutException;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;

/**
 * Shared polling primitive for acceptance and integration tests that wait on
 * out-of-process resources.
 *
 * <p>Tests frequently need to poll an out-of-process resource (Gitea repository
 * state, Artifactory deployments, ...) until a condition becomes true, with a
 * bounded timeout and diagnostic output on failure. Without a shared primitive,
 * each site re-implements the {@link org.awaitility.Awaitility} +
 * {@link java.util.concurrent.atomic.AtomicReference} scaffolding with subtly
 * different timeout-message shapes.
 *
 * <p>{@link #pollUntil(Duration, Duration, Supplier)} wraps the canonical
 * Awaitility pattern and returns a {@link Result} that callers inspect to
 * either return the value or build a site-specific timeout {@link
 * IllegalStateException}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * AtomicReference<String> lastSeen = new AtomicReference<>();
 *
 * PollingHelper.Result<Order> result = PollingHelper.pollUntil(
 *         Duration.ofMinutes(2),
 *         Duration.ofSeconds(1),
 *         () -> {
 *             Order o = orderService.fetch(orderId);
 *             lastSeen.set(o.status());
 *             return "SHIPPED".equals(o.status()) ? Optional.of(o) : Optional.empty();
 *         });
 *
 * if (result.value().isEmpty()) {
 *     throw new IllegalStateException("Timed out waiting for order " + orderId
 *             + " (last seen status '" + lastSeen.get() + "')");
 * }
 * return result.value().orElseThrow();
 * }</pre>
 *
 * <p>This helper deliberately does <em>not</em> own diagnostic state. Each call
 * site keeps its own {@code AtomicReference} variables for "last seen X" / "last
 * error", which keeps the helper signature small and lets each site shape its
 * own timeout message.
 */
public final class PollingHelper {

    private PollingHelper() {
        // utility class
    }

    /**
     * Polls {@code attempt} on {@code pollInterval} cadence until it returns a
     * non-empty {@link Optional} or {@code timeout} elapses.
     *
     * <p>The first attempt runs after one {@code pollInterval} (Awaitility's
     * default {@code pollDelay}), matching the prior hand-rolled loops.
     *
     * <p>If {@code attempt} throws, the exception is captured in
     * {@link Result#lastError()} and polling continues. The call site is
     * responsible for any per-attempt logging it wants on caught errors --
     * typically by wrapping its own try/catch inside the supplier.
     *
     * @param timeout      maximum wall time to wait
     * @param pollInterval delay between attempts
     * @param attempt      returns {@link Optional#empty()} while still waiting,
     *                     a present value when the condition is satisfied
     * @param <T>          value type
     * @return a {@link Result} carrying either the value (on success) or the
     *         last captured error (on timeout)
     */
    public static <T> Result<T> pollUntil(
            Duration timeout,
            Duration pollInterval,
            Supplier<Optional<T>> attempt) {
        AtomicReference<T> found = new AtomicReference<>();
        AtomicReference<Throwable> lastError = new AtomicReference<>();

        try {
            await()
                    .pollInterval(pollInterval)
                    .atMost(timeout)
                    .until(() -> {
                        try {
                            Optional<T> result = attempt.get();
                            if (result.isPresent()) {
                                found.set(result.get());
                                lastError.set(null);
                                return true;
                            }
                            return false;
                        } catch (RuntimeException error) {
                            lastError.set(error);
                            return false;
                        }
                    });
            return new Result<>(Optional.of(found.get()), Optional.empty(), false);
        } catch (ConditionTimeoutException conditionTimeout) {
            Throwable captured = lastError.get();
            return new Result<>(
                    Optional.empty(),
                    Optional.ofNullable(captured != null ? captured : conditionTimeout),
                    true);
        }
    }

    /**
     * Outcome of a {@link #pollUntil(Duration, Duration, Supplier)} call.
     *
     * <p>On success, {@link #value()} is present and {@link #timedOut()} is
     * {@code false}. On timeout, {@link #value()} is empty, {@link #timedOut()}
     * is {@code true}, and {@link #lastError()} holds either the last
     * {@link RuntimeException} thrown by the attempt supplier or the
     * underlying {@link ConditionTimeoutException}.
     */
    public static final class Result<T> {

        private final Optional<T> value;
        private final Optional<Throwable> lastError;
        private final boolean timedOut;

        private Result(Optional<T> value, Optional<Throwable> lastError, boolean timedOut) {
            this.value = value;
            this.lastError = lastError;
            this.timedOut = timedOut;
        }

        public Optional<T> value() {
            return value;
        }

        public Optional<Throwable> lastError() {
            return lastError;
        }

        public boolean timedOut() {
            return timedOut;
        }
    }
}
