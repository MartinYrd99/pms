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
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the constraints the sessions-and-payments migration declares actually reject bad data:
 * two unsettled sessions for one vehicle, two live payments for one session, and each of the
 * three CHECK constraints on parking_sessions.
 */
@SpringBootTest
class SessionsAndPaymentsConstraintsTest extends AbstractPostgresIT {
    @Autowired
    private DataSource dataSource;

    @Test
    void secondUnsettledSessionForSameVehicleViolatesPartialUniqueIndex() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            long userId = insertUser(connection, "driver-one");
            long vehicleId = insertVehicle(connection, userId, "AA-111-BB");
            long zoneId = insertZone(connection, "Downtown", "Sofia");
            long tariffId = insertCurrentTariff(connection, zoneId);

            long firstSessionId = insertSession(connection, userId, vehicleId, zoneId, tariffId);

            assertThatThrownBy(() -> insertSession(connection, userId, vehicleId, zoneId, tariffId))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_parking_sessions_vehicle_unsettled");

            markPaid(connection, firstSessionId);

            assertThatCode(() -> insertSession(connection, userId, vehicleId, zoneId, tariffId))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void secondPendingPaymentForSameSessionViolatesPartialUniqueIndex() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            long userId = insertUser(connection, "driver-two");
            long vehicleId = insertVehicle(connection, userId, "AA-222-BB");
            long zoneId = insertZone(connection, "Downtown", "Sofia");
            long tariffId = insertCurrentTariff(connection, zoneId);
            long sessionId = insertSession(connection, userId, vehicleId, zoneId, tariffId);
            endSession(connection, sessionId, new BigDecimal("5.00"));

            long firstPaymentId = insertPayment(connection, sessionId, "PENDING");

            assertThatThrownBy(() -> insertPayment(connection, sessionId, "PENDING"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_payments_session_live");

            updatePaymentStatus(connection, firstPaymentId, "FAILED");

            assertThatCode(() -> insertPayment(connection, sessionId, "PENDING")).doesNotThrowAnyException();
        }
    }

    @Test
    void endedAtWithoutAmountViolatesCheckConstraint() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            long userId = insertUser(connection, "driver-three");
            long vehicleId = insertVehicle(connection, userId, "AA-333-BB");
            long zoneId = insertZone(connection, "Downtown", "Sofia");
            long tariffId = insertCurrentTariff(connection, zoneId);

            Instant startedAt = Instant.now().minusSeconds(60);
            Instant endedAt = Instant.now();

            assertThatThrownBy(() -> insertSessionWithEndedAtAndAmount(
                            connection, userId, vehicleId, zoneId, tariffId, startedAt, endedAt, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_parking_sessions_ended_amount");
        }
    }

    @Test
    void endedAtBeforeStartedAtViolatesCheckConstraint() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            long userId = insertUser(connection, "driver-four");
            long vehicleId = insertVehicle(connection, userId, "AA-444-BB");
            long zoneId = insertZone(connection, "Downtown", "Sofia");
            long tariffId = insertCurrentTariff(connection, zoneId);

            Instant startedAt = Instant.now();
            Instant endedAt = startedAt.minusSeconds(60);

            assertThatThrownBy(() -> insertSessionWithCustomTimes(
                            connection, userId, vehicleId, zoneId, tariffId, startedAt, endedAt,
                            new BigDecimal("5.00")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_parking_sessions_ended_after_started");
        }
    }

    @Test
    void paidAtWithoutEndedAtViolatesCheckConstraint() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            long userId = insertUser(connection, "driver-five");
            long vehicleId = insertVehicle(connection, userId, "AA-555-BB");
            long zoneId = insertZone(connection, "Downtown", "Sofia");
            long tariffId = insertCurrentTariff(connection, zoneId);

            assertThatThrownBy(() -> insertActiveSessionAlreadyPaid(connection, userId, vehicleId, zoneId, tariffId))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_parking_sessions_paid_requires_ended");
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

    private long insertVehicle(Connection connection, long userId, String plate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vehicles (user_id, plate, brand, model) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setString(2, plate);
            statement.setString(3, "Toyota");
            statement.setString(4, "Corolla");
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                generatedKeys.next();

                return generatedKeys.getLong(1);
            }
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

    private long insertSession(Connection connection, long userId, long vehicleId, long zoneId, long tariffId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO parking_sessions (user_id, vehicle_id, zone_id, tariff_id, started_at)
                VALUES (?, ?, ?, ?, now())
                """,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setLong(2, vehicleId);
            statement.setLong(3, zoneId);
            statement.setLong(4, tariffId);
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                generatedKeys.next();

                return generatedKeys.getLong(1);
            }
        }
    }

    private void insertSessionWithEndedAtAndAmount(
            Connection connection,
            long userId,
            long vehicleId,
            long zoneId,
            long tariffId,
            Instant startedAt,
            Instant endedAt,
            BigDecimal amount)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO parking_sessions
                    (user_id, vehicle_id, zone_id, tariff_id, started_at, ended_at, amount)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, userId);
            statement.setLong(2, vehicleId);
            statement.setLong(3, zoneId);
            statement.setLong(4, tariffId);
            statement.setTimestamp(5, Timestamp.from(startedAt));
            statement.setTimestamp(6, Timestamp.from(endedAt));
            statement.setBigDecimal(7, amount);
            statement.executeUpdate();
        }
    }

    private void insertSessionWithCustomTimes(
            Connection connection,
            long userId,
            long vehicleId,
            long zoneId,
            long tariffId,
            Instant startedAt,
            Instant endedAt,
            BigDecimal amount)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO parking_sessions
                    (user_id, vehicle_id, zone_id, tariff_id, started_at, ended_at, amount)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, userId);
            statement.setLong(2, vehicleId);
            statement.setLong(3, zoneId);
            statement.setLong(4, tariffId);
            statement.setTimestamp(5, Timestamp.from(startedAt));
            statement.setTimestamp(6, Timestamp.from(endedAt));
            statement.setBigDecimal(7, amount);
            statement.executeUpdate();
        }
    }

    private void insertActiveSessionAlreadyPaid(
            Connection connection, long userId, long vehicleId, long zoneId, long tariffId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO parking_sessions (user_id, vehicle_id, zone_id, tariff_id, started_at, paid_at)
                VALUES (?, ?, ?, ?, now(), now())
                """)) {
            statement.setLong(1, userId);
            statement.setLong(2, vehicleId);
            statement.setLong(3, zoneId);
            statement.setLong(4, tariffId);
            statement.executeUpdate();
        }
    }

    private void endSession(Connection connection, long sessionId, BigDecimal amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE parking_sessions SET ended_at = now(), amount = ? WHERE id = ?")) {
            statement.setBigDecimal(1, amount);
            statement.setLong(2, sessionId);
            statement.executeUpdate();
        }
    }

    private void markPaid(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE parking_sessions SET ended_at = now(), amount = ?, paid_at = now() WHERE id = ?")) {
            statement.setBigDecimal(1, new BigDecimal("5.00"));
            statement.setLong(2, sessionId);
            statement.executeUpdate();
        }
    }

    private long insertPayment(Connection connection, long sessionId, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO payments (session_id, amount, status) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, sessionId);
            statement.setBigDecimal(2, new BigDecimal("5.00"));
            statement.setString(3, status);
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                generatedKeys.next();

                return generatedKeys.getLong(1);
            }
        }
    }

    private void updatePaymentStatus(Connection connection, long paymentId, String status) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("UPDATE payments SET status = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setLong(2, paymentId);
            statement.executeUpdate();
        }
    }
}
