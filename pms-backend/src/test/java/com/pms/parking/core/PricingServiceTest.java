package com.pms.parking.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.pms.zone.core.tariff.RuleType;
import com.pms.zone.core.tariff.Tariff;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Verifies the hourly pricing rule against its boundary set: every started hour is a full hour,
 * minimum one, with no intermediate rounding to minutes.
 */
class PricingServiceTest {
    private static final Instant STARTED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final PricingService pricingService = new PricingService();

    @ParameterizedTest(name = "{0} ms -> {1} hour(s)")
    @CsvSource({
        "0, 1",
        "3600000, 1",
        "3600001, 2",
        "7200000, 2",
        "7200001, 3",
    })
    void hourlyRuleChargesEveryStartedHourAsAFullHour(long durationMillis, long expectedHours) {
        Tariff tariff = hourlyTariff(BigDecimal.ONE);
        Instant endedAt = STARTED_AT.plusMillis(durationMillis);

        BigDecimal amount = pricingService.price(STARTED_AT, endedAt, tariff);

        assertThat(amount).isEqualByComparingTo(BigDecimal.valueOf(expectedHours));
    }

    @Test
    void sixtyMinutesAndThirtySecondsPricesAsTwoHoursNotOne() {
        Tariff tariff = hourlyTariff(BigDecimal.ONE);
        Instant endedAt = STARTED_AT.plusMillis(3_630_000L);

        BigDecimal amount = pricingService.price(STARTED_AT, endedAt, tariff);

        assertThat(amount).isEqualByComparingTo(BigDecimal.valueOf(2));
    }

    @Test
    void twoHoursFiveMinutesAtBlueZoneRateYieldsExactlySixEuros() {
        Tariff tariff = hourlyTariff(new BigDecimal("2.00"));
        Instant endedAt = STARTED_AT.plus(Duration.ofHours(2).plusMinutes(5));

        BigDecimal amount = pricingService.price(STARTED_AT, endedAt, tariff);

        assertThat(amount).isEqualByComparingTo(new BigDecimal("6.00"));
        assertThat(amount.scale()).isEqualTo(2);
    }

    private static Tariff hourlyTariff(BigDecimal hourlyRate) {
        return new Tariff().setRuleType(RuleType.HOURLY).setHourlyRate(hourlyRate);
    }
}