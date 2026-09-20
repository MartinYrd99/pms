package com.pms.zone.core.tariff;

import static org.assertj.core.api.Assertions.assertThat;

import com.pms.AbstractPostgresIT;
import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TariffRepositoryTest extends AbstractPostgresIT {
    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private TariffRepository tariffRepository;

    @Test
    void findByZoneIdAndValidToIsNullReturnsOnlyTheOpenTariff() {
        Zone zone = zoneRepository.save(new Zone().setName("Repo Zone").setCity("Burgas").setActive(true));

        Tariff closedTariff = tariffRepository.save(new Tariff()
                .setZoneId(zone.getId())
                .setRuleType(RuleType.HOURLY)
                .setHourlyRate(new BigDecimal("1.50"))
                .setCurrency("EUR")
                .setValidFrom(Instant.now().minus(30, ChronoUnit.DAYS))
                .setValidTo(Instant.now().minus(1, ChronoUnit.DAYS)));

        Tariff currentTariff = tariffRepository.save(new Tariff()
                .setZoneId(zone.getId())
                .setRuleType(RuleType.HOURLY)
                .setHourlyRate(new BigDecimal("2.00"))
                .setCurrency("EUR")
                .setValidFrom(Instant.now()));

        Optional<Tariff> found = tariffRepository.findByZoneIdAndValidToIsNull(zone.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(currentTariff.getId());
        assertThat(found.get().getId()).isNotEqualTo(closedTariff.getId());
    }
}