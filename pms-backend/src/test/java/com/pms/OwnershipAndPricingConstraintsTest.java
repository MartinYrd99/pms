package com.pms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the constraints the ownership-and-pricing migration declares actually reject bad data:
 * a plate shared by two vehicles, and a zone with more than one current tariff.
 */
@SpringBootTest
class OwnershipAndPricingConstraintsTest extends AbstractPostgresIT {
    @Autowired
    private DataSource dataSource;

    @Test
    void duplicatePlateAcrossDifferentUsersViolatesUniqueConstraint() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            long firstOwner = insertUser(connection, "owner-one");
            long secondOwner = insertUser(connection, "owner-two");

            insertVehicle(connection, firstOwner, "AA-000-BB");

            assertThatThrownBy(() -> insertVehicle(connection, secondOwner, "AA-000-BB"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_vehicles_plate");
        }
    }

    @Test
    void secondCurrentTariffForSameZoneViolatesPartialUniqueIndex() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            long zoneId = insertZone(connection, "Downtown", "Sofia");
            long firstTariffId = insertCurrentTariff(connection, zoneId);

            assertThatThrownBy(() -> insertCurrentTariff(connection, zoneId))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_tariffs_zone_current");

            closeTariff(connection, firstTariffId);

            assertThatCode(() -> insertCurrentTariff(connection, zoneId)).doesNotThrowAnyException();
        }
    }

    private long insertUser(Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (username, password_hash) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username);
            statement.setString(2, "$2a$10$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTU");
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                generatedKeys.next();

                return generatedKeys.getLong(1);
            }
        }
    }

    private void insertVehicle(Connection connection, long userId, String plate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicles (user_id, plate, brand, model) VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, userId);
            statement.setString(2, plate);
            statement.setString(3, "Toyota");
            statement.setString(4, "Corolla");
            statement.executeUpdate();
        }
    }

    private long insertZone(Connection connection, String name, String city) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO zones (name, city) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, city);
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                generatedKeys.next();

                return generatedKeys.getLong(1);
            }
        }
    }

    private long insertCurrentTariff(Connection connection, long zoneId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO tariffs (zone_id, rule_type, hourly_rate, valid_from) VALUES (?, ?, ?, now())",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, zoneId);
            statement.setString(2, "HOURLY");
            statement.setBigDecimal(3, new BigDecimal("2.50"));
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                generatedKeys.next();

                return generatedKeys.getLong(1);
            }
        }
    }

    private void closeTariff(Connection connection, long tariffId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("UPDATE tariffs SET valid_to = now() WHERE id = ?")) {
            statement.setLong(1, tariffId);
            statement.executeUpdate();
        }
    }
}
