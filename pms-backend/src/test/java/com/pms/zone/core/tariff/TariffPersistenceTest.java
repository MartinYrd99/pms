package com.pms.zone.core.tariff;

import static org.assertj.core.api.Assertions.assertThat;

import com.pms.AbstractPostgresIT;
import com.pms.zone.core.Zone;
import com.pms.zone.core.ZoneRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TariffPersistenceTest extends AbstractPostgresIT {
    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private TariffRepository tariffRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void ruleTypeIsPersistedAsItsEnumNameNotAnOrdinal() throws Exception {
        Zone zone = zoneRepository.save(new Zone().setName("Persistence Zone").setCity("Varna").setActive(true));

        Tariff tariff = tariffRepository.save(new Tariff()
                .setZoneId(zone.getId())
                .setRuleType(RuleType.HOURLY)
                .setHourlyRate(new BigDecimal("3.00"))
                .setCurrency("EUR")
                .setValidFrom(Instant.now()));

        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT rule_type FROM tariffs WHERE id = ?")) {
            statement.setLong(1, tariff.getId());

            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();

                assertThat(resultSet.getString("rule_type")).isEqualTo("HOURLY");
            }
        }
    }
}