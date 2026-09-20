package com.pms.payment.core.settlement;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Holds the instant of the settlement job's last successful run, so a health indicator can answer
 * "is settlement alive?" without depending on the job itself. Seeded at construction (application
 * startup) rather than left empty, so a fresh app gets the same staleness-threshold grace period
 * as one that is already ticking, instead of being reported as dead before its first tick.
 */
@Component
@Slf4j
public class SettlementLivenessTracker {
    private final Clock clock;
    private final AtomicReference<Instant> lastSuccessfulRun;

    public SettlementLivenessTracker(Clock clock) {
        this.clock = clock;
        this.lastSuccessfulRun = new AtomicReference<>(clock.instant());

        log.info("Seeding settlement liveness at {}", lastSuccessfulRun.get());
    }

    /**
     * Records that a run just completed without throwing, including one that found nothing due to
     * settle.
     */
    void recordSuccess() {
        Instant now = clock.instant();

        lastSuccessfulRun.set(now);
    }

    public Instant lastSuccessfulRun() {
        return lastSuccessfulRun.get();
    }
}
