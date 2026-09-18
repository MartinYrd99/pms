package com.pms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the R__seed_zones_and_tariffs.sql repeatable migration seeds the reference zones and
 * their current tariffs, and that re-running it never duplicates rows.
 */
@SpringBootTest
class ZonesAndTariffsSeedTest extends AbstractPostgresIT {
    private static final String SEED_MIGRATION_PATH = "db/migration/R__seed_zones_and_tariffs.sql";

    @Autowired
    private DataSource dataSource;

    @Test
    void seededZonesSpanTwoCitiesWithBlueAndGreenRatesAndAnInactiveZone() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(distinctCities(connection)).isGreaterThanOrEqualTo(2);
            assertThat(currentHourlyRate(connection, "Blue Zone")).isEqualByComparingTo(new BigDecimal("2.00"));
            assertThat(currentHourlyRate(connection, "Green Zone")).isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(countInactiveZones(connection)).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void everySeededZoneHasExactlyOneCurrentTariff() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(countZones(connection)).isEqualTo(countZonesWithExactlyOneCurrentTariff(connection));
        }
    }

    @Test
    void reapplyingTheRepeatableMigrationLeavesRowCountsUnchanged() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            long zoneCountBefore = countZones(connection);
            long tariffCountBefore = countTariffs(connection);

            runSeedMigrationAgain(connection);

            assertThat(countZones(connection)).isEqualTo(zoneCountBefore);
            assertThat(countTariffs(connection)).isEqualTo(tariffCountBefore);
        }
    }

    private void runSeedMigrationAgain(Connection connection) throws Exception {
        String seedSql = StreamUtils.copyToString(
                new ClassPathResource(SEED_MIGRATION_PATH).getInputStream(), StandardCharsets.UTF_8);

        try (Statement statement = connection.createStatement()) {
            statement.execute(seedSql);
        }
    }

    private long countZones(Connection connection) throws Exception {
        return scalarLong(connection, "SELECT count(*) FROM zones");
    }

    private long countTariffs(Connection connection) throws Exception {
        return scalarLong(connection, "SELECT count(*) FROM tariffs");
    }

    private long countInactiveZones(Connection connection) throws Exception {
        return scalarLong(connection, "SELECT count(*) FROM zones WHERE active = false");
    }

    private long countZonesWithExactlyOneCurrentTariff(Connection connection) throws Exception {
        return scalarLong(
                connection,
                """
                SELECT count(*) FROM zones z
                WHERE (SELECT count(*) FROM tariffs t WHERE t.zone_id = z.id AND t.valid_to IS NULL) = 1
                """);
    }

    private long distinctCities(Connection connection) throws Exception {
        return scalarLong(connection, "SELECT count(DISTINCT city) FROM zones");
    }

    private BigDecimal currentHourlyRate(Connection connection, String zoneName) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        """
                        SELECT t.hourly_rate FROM tariffs t
                        JOIN zones z ON z.id = t.zone_id
                        WHERE z.name = '%s' AND t.valid_to IS NULL
                        """
                                .formatted(zoneName))) {
            resultSet.next();

            return resultSet.getBigDecimal("hourly_rate");
        }
    }

    private long scalarLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();

            return resultSet.getLong(1);
        }
    }
}
