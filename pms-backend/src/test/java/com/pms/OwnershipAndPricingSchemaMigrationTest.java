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
 * Confirms the V2__ownership_and_pricing.sql migration applied cleanly and produced the
 * vehicles, zones and tariffs tables along with the vehicles(user_id) index design relies on.
 */
@SpringBootTest
class OwnershipAndPricingSchemaMigrationTest extends AbstractPostgresIT {
    @Autowired
    private DataSource dataSource;

    @Test
    void allThreeTablesExist() throws Exception {
        Set<String> tableNames = fetchTableNames();

        assertThat(tableNames).contains("vehicles", "zones", "tariffs");
    }

    @Test
    void vehiclesTableHasUserIdIndex() throws Exception {
        Set<String> indexNames = fetchIndexNames("vehicles");

        assertThat(indexNames).contains("idx_vehicles_user_id");
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
