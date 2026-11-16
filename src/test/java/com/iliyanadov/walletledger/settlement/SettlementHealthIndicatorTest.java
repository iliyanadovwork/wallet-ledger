package com.iliyanadov.walletledger.settlement;

import com.iliyanadov.walletledger.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the health endpoint says about settlement.
 *
 * <p>The thing being checked is that it reports a duration rather than a flag. Down for two
 * seconds during a failover and down for two hours because something is wedged are the same
 * boolean, and only one of them is worth waking somebody up for.
 */
class SettlementHealthIndicatorTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
    private final SettlementHealth health = new SettlementHealth(clock);
    private final SettlementHealthIndicator indicator = new SettlementHealthIndicator(health, clock);

    @Test
    @DisplayName("never having run is reported as unknown, not as healthy")
    void nothingHasHappenedYet() {
        Health reported = indicator.health();

        assertThat(reported.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(reported.getDetails()).containsEntry("reason", "settlement has not run yet");
    }

    @Test
    @DisplayName("a healthy report says when settlement last worked")
    void lastSuccessIsPublished() {
        health.recordSuccess();
        clock.advance(Duration.ofSeconds(30));

        Health reported = indicator.health();

        assertThat(reported.getStatus()).isEqualTo(Status.UP);
        assertThat(reported.getDetails()).containsEntry("secondsSinceLastSuccess", 30L);
        assertThat(reported.getDetails()).containsEntry("lastSuccessAt", "2026-01-01T12:00:00Z");
    }

    @Test
    @DisplayName("a failing report says how long it has been failing for, not just that it is")
    void failureIsReportedAsADuration() {
        health.recordSuccess();
        clock.advance(Duration.ofMinutes(1));

        health.recordFailure();
        clock.advance(Duration.ofMinutes(4));
        health.recordFailure();

        Health reported = indicator.health();

        assertThat(reported.getStatus()).isEqualTo(Status.DOWN);
        assertThat(reported.getDetails()).containsEntry("failingForSeconds", 240L);
        assertThat(reported.getDetails()).containsEntry("consecutiveFailures", 2);
        assertThat(reported.getDetails()).containsEntry("lastSuccessAt", "2026-01-01T12:00:00Z");
    }

    @Test
    @DisplayName("the moment the trouble started does not slide forward with each new failure")
    void failingSinceIsTheFirstFailureNotTheLatest() {
        health.recordFailure();
        Instant troubleStarted = health.failingSince().orElseThrow();

        clock.advance(Duration.ofMinutes(10));
        health.recordFailure();

        assertThat(health.failingSince()).contains(troubleStarted);
        assertThat(indicator.health().getDetails()).containsEntry("failingForSeconds", 600L);
    }

    @Test
    @DisplayName("one success clears the failure and the clock starts again from there")
    void recoveryResetsEverything() {
        health.recordFailure();
        clock.advance(Duration.ofMinutes(5));

        health.recordSuccess();

        assertThat(health.failingSince()).isEmpty();
        assertThat(health.consecutiveFailures()).isZero();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
