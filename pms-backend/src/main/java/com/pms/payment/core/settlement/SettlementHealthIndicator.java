package com.pms.payment.core.settlement;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the settlement job DOWN once its last successful run is older than the configured
 * staleness threshold, so a disabled scheduler or a job wedged on an exhausted connection pool
 * surfaces on {@code /actuator/health} instead of only manifesting as every paying driver being
 * silently blocked.
 */
@Component("settlement")
public class SettlementHealthIndicator implements HealthIndicator {
    private final SettlementLivenessTracker settlementLivenessTracker;
    private final Clock clock;
    private final Duration stalenessThreshold;

    public SettlementHealthIndicator(
            SettlementLivenessTracker settlementLivenessTracker,
            Clock clock,
            @Value("${pms.payment.settlement.staleness-threshold-ms:60000}") long stalenessThresholdMs) {
        this.settlementLivenessTracker = settlementLivenessTracker;
        this.clock = clock;
        this.stalenessThreshold = Duration.ofMillis(stalenessThresholdMs);
    }

    @Override
    public Health health() {
        Instant lastSuccessfulRun = settlementLivenessTracker.lastSuccessfulRun();
        Health.Builder builder = isStale(lastSuccessfulRun) ? Health.down() : Health.up();

        return builder.withDetail("lastSuccessfulRun", lastSuccessfulRun).build();
    }

    private boolean isStale(Instant lastSuccessfulRun) {
        return lastSuccessfulRun.isBefore(clock.instant().minus(stalenessThreshold));
    }
}