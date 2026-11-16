package com.iliyanadov.walletledger.settlement;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Reports settlement on {@code /actuator/health} as a duration rather than a flag.
 *
 * <p>Down for two seconds and down for two hours are different situations and a status word
 * cannot tell them apart. Publishing how long it has been failing lets whoever is reading decide
 * where their own threshold is, instead of this service deciding it for them and then being wrong
 * for somebody.
 *
 * <p>Never having run is reported as UNKNOWN rather than as UP. It is not a healthy answer, it is
 * the absence of one, and dressing it up as healthy is how a service that never started its
 * scheduler passes its own health check.
 */
@Component
public class SettlementHealthIndicator implements HealthIndicator {

    private final SettlementHealth health;
    private final Clock clock;

    public SettlementHealthIndicator(SettlementHealth health, Clock clock) {
        this.health = health;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Optional<Instant> failingSince = health.failingSince();
        Optional<Instant> lastSuccess = health.lastSuccess();

        if (failingSince.isPresent()) {
            Instant since = failingSince.get();
            return Health.down()
                    .withDetail("failingSince", since.toString())
                    .withDetail("failingForSeconds", secondsSince(since))
                    .withDetail("consecutiveFailures", health.consecutiveFailures())
                    .withDetail("lastSuccessAt", lastSuccess.map(Instant::toString).orElse("never"))
                    .build();
        }

        if (lastSuccess.isEmpty()) {
            return Health.unknown()
                    .withDetail("reason", "settlement has not run yet")
                    .build();
        }

        return Health.up()
                .withDetail("lastSuccessAt", lastSuccess.get().toString())
                .withDetail("secondsSinceLastSuccess", secondsSince(lastSuccess.get()))
                .build();
    }

    private long secondsSince(Instant moment) {
        return Math.max(0, Duration.between(moment, clock.instant()).toSeconds());
    }
}
