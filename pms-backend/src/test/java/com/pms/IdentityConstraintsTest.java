package com.pms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the constraints the identity migration declares actually reject bad data at the
 * database level: duplicate usernames, duplicate refresh-token hashes and orphan refresh tokens.
 */
@SpringBootTest
class IdentityConstraintsTest extends AbstractPostgresIT {
    @Autowired
    private DataSource dataSource;

    @Test
    void duplicateUsernameViolatesUniqueConstraint() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            insertUser(connection, "driver-duplicate");

            assertThatThrownBy(() -> insertUser(connection, "driver-duplicate"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_users_username");
        }
    }

    @Test
    void duplicateTokenHashViolatesUniqueConstraint() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            long userId = insertUser(connection, "driver-with-token");
            insertRefreshToken(connection, userId, "duplicate-token-hash");

            assertThatThrownBy(() -> insertRefreshToken(connection, userId, "duplicate-token-hash"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_refresh_tokens_token_hash");
        }
    }

    @Test
    void refreshTokenWithUnknownUserFailsForeignKey() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> insertRefreshToken(connection, -1L, "orphan-token-hash"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_refresh_tokens_user");
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

    private void insertRefreshToken(Connection connection, long userId, String tokenHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO refresh_tokens (user_id, token_hash, expires_at) "
                        + "VALUES (?, ?, now() + interval '48 hours')")) {
            statement.setLong(1, userId);
            statement.setString(2, tokenHash);
            statement.executeUpdate();
        }
    }
}
