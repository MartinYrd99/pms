package com.pms.parking.core;

import com.pms.zone.core.tariff.Tariff;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Prices a stay under the hourly rule: every started hour is a full hour, with a minimum of one
 * hour.
 */
@Component
public class PricingService {
    private static final long MILLIS_PER_HOUR = 3_600_000L;

    public BigDecimal price(Instant startedAt, Instant endedAt, Tariff tariff) {
        return switch (tariff.getRuleType()) {
            case HOURLY -> priceHourly(startedAt, endedAt, tariff.getHourlyRate());
        };
    }

    private BigDecimal priceHourly(Instant startedAt, Instant endedAt, BigDecimal hourlyRate) {
        long millis = endedAt.toEpochMilli() - startedAt.toEpochMilli();
        long hours = Math.max(1, Math.ceilDiv(millis, MILLIS_PER_HOUR));

        return BigDecimal.valueOf(hours).multiply(hourlyRate).setScale(2, RoundingMode.UNNECESSARY);
    }
}