package com.pms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the V1__identity.sql migration applied cleanly and produced the users and
 * refresh_tokens tables with the columns and types design.
 */
@SpringBootTest
class IdentitySchemaMigrationTest extends AbstractPostgresIT {
    @Autowired
    private DataSource dataSource;

    private record ColumnInfo(String dataType, String isNullable, Integer maxLength) {
    }

    @Test
    void usersTableHasExpectedColumns() throws Exception {
        Map<String, ColumnInfo> columns = fetchColumns("users");

        assertThat(columns.keySet()).containsExactly("id", "username", "password_hash", "created_at");
        assertThat(columns.get("id")).isEqualTo(new ColumnInfo("bigint", "NO", null));
        assertThat(columns.get("username")).isEqualTo(new ColumnInfo("character varying", "NO", null));
        assertThat(columns.get("password_hash")).isEqualTo(new ColumnInfo("character varying", "NO", 60));
        assertThat(columns.get("created_at")).isEqualTo(new ColumnInfo("timestamp with time zone", "NO", null));
    }

    @Test
    void refreshTokensTableHasExpectedColumns() throws Exception {
        Map<String, ColumnInfo> columns = fetchColumns("refresh_tokens");

        assertThat(columns.keySet())
                .containsExactly("id", "user_id", "token_hash", "expires_at", "revoked_at", "created_at");
        assertThat(columns.get("id")).isEqualTo(new ColumnInfo("bigint", "NO", null));
        assertThat(columns.get("user_id")).isEqualTo(new ColumnInfo("bigint", "NO", null));
        assertThat(columns.get("token_hash")).isEqualTo(new ColumnInfo("character varying", "NO", null));
        assertThat(columns.get("expires_at")).isEqualTo(new ColumnInfo("timestamp with time zone", "NO", null));
        assertThat(columns.get("revoked_at")).isEqualTo(new ColumnInfo("timestamp with time zone", "YES", null));
        assertThat(columns.get("created_at")).isEqualTo(new ColumnInfo("timestamp with time zone", "NO", null));
    }

    private Map<String, ColumnInfo> fetchColumns(String tableName) throws Exception {
        var columns = new LinkedHashMap<String, ColumnInfo>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        """
                        SELECT column_name, data_type, is_nullable, character_maximum_length
                        FROM information_schema.columns
                        WHERE table_name = '%s'
                        ORDER BY ordinal_position
                        """.formatted(tableName))) {
            while (resultSet.next()) {
                Integer maxLength = resultSet.getObject("character_maximum_length", Integer.class);
                columns.put(resultSet.getString("column_name"),
                        new ColumnInfo(resultSet.getString("data_type"), resultSet.getString("is_nullable"),
                                maxLength));
            }
        }
        return columns;
    }
}
