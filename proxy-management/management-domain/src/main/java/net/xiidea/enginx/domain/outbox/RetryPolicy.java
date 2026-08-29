package net.xiidea.enginx.domain.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * How long to wait before another attempt.
 *
 * <p>The schedule is short at first, because most failures are a restarting agent, and then long,
 * because anything still failing after ten minutes needs a person rather than another request.
 */
public final class RetryPolicy {

    private static final List<Duration> BACKOFF = List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30));

    private RetryPolicy() {
    }

    public static int maxAttempts() {
        return BACKOFF.size();
    }

    public static boolean exhausted(int attempts) {
        return attempts >= BACKOFF.size();
    }

    public static Instant nextAttemptAt(int attempts, Instant now) {
        int index = Math.min(Math.max(attempts, 0), BACKOFF.size() - 1);
        return now.plus(BACKOFF.get(index));
    }
}
