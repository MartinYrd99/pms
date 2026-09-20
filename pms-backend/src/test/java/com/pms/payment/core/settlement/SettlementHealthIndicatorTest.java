package com.pms.payment.core.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Verifies the health indicator's staleness rule in isolation from the settlement job itself: a
 * recent last successful run reports UP with the timestamp as a detail, while one older than the
 * configured threshold reports DOWN.
 */
class SettlementHealthIndicatorTest {
    private static final Instant LAST_SUCCESSFUL_RUN = Instant.parse("2024-01-01T00:00:00Z");
    private static final long STALENESS_THRESHOLD_MS = 60_000L;

    @Test
    void reportsUpWithTheLastSuccessfulRunAsADetailWhenItIsRecent() {
        SettlementLivenessTracker tracker = trackerThatLastSucceededAt(LAST_SUCCESSFUL_RUN);
        Clock now = Clock.fixed(LAST_SUCCESSFUL_RUN.plusSeconds(30), ZoneOffset.UTC);

        Health health = new SettlementHealthIndicator(tracker, now, STALENESS_THRESHOLD_MS).health();

        assertThat(Objects.requireNonNull(health).getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("lastSuccessfulRun", LAST_SUCCESSFUL_RUN);
    }

    @Test
    void reportsDownWhenTheLastSuccessfulRunIsOlderThanTheStalenessThreshold() {
        SettlementLivenessTracker tracker = trackerThatLastSucceededAt(LAST_SUCCESSFUL_RUN);
        Clock now = Clock.fixed(LAST_SUCCESSFUL_RUN.plusSeconds(61), ZoneOffset.UTC);

        Health health = new SettlementHealthIndicator(tracker, now, STALENESS_THRESHOLD_MS).health();

        assertThat(Objects.requireNonNull(health).getStatus()).isEqualTo(Status.DOWN);
    }

    private SettlementLivenessTracker trackerThatLastSucceededAt(Instant instant) {
        SettlementLivenessTracker tracker = new SettlementLivenessTracker(Clock.fixed(instant, ZoneOffset.UTC));
        tracker.recordSuccess();

        return tracker;
    }
}