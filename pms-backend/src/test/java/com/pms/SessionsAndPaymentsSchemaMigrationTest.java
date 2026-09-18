package com.pms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the V3__sessions_and_payments.sql migration applied cleanly and produced the
 * parking_sessions and payments tables along with the five invariant indexes.
 */
@SpringBootTest
class SessionsAndPaymentsSchemaMigrationTest extends AbstractPostgresIT {
    @Autowired
    private DataSource dataSource;

    @Test
    void bothTablesExist() throws Exception {
        Set<String> tableNames = fetchTableNames();

        assertThat(tableNames).contains("parking_sessions", "payments");
    }

    @Test
    void parkingSessionsHasItsThreeIndexes() throws Exception {
        Set<String> indexNames = fetchIndexNames("parking_sessions");

        assertThat(indexNames).contains(
                "uq_parking_sessions_vehicle_unsettled",
                "idx_parking_sessions_user_active",
                "idx_parking_sessions_user_started_at");
    }

    @Test
    void paymentsHasItsTwoIndexes() throws Exception {
        Set<String> indexNames = fetchIndexNames("payments");

        assertThat(indexNames).contains("uq_payments_session_live", "idx_payments_pending_created_at");
    }

    private Set<String> fetchTableNames() throws Exception {
        LinkedHashSet<String> tableNames = new LinkedHashSet<>();

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                        """)) {
            while (resultSet.next()) {
                tableNames.add(resultSet.getString("table_name"));
            }
        }
        return tableNames;
    }

    private Set<String> fetchIndexNames(String tableName) throws Exception {
        LinkedHashSet<String> indexNames = new LinkedHashSet<>();

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        """
                        SELECT indexname
                        FROM pg_indexes
                        WHERE tablename = '%s'
                        """.formatted(tableName))) {
            while (resultSet.next()) {
                indexNames.add(resultSet.getString("indexname"));
            }
        }

        return indexNames;
    }
}
